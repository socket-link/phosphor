package link.socket.phosphor.trace

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import link.socket.phosphor.choreography.AtmosphereTransitionSpec
import link.socket.phosphor.field.Choreography
import link.socket.phosphor.lumos.CognitiveLookSwitch
import link.socket.phosphor.lumos.FramePhases
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosForm
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.LumosSequence
import link.socket.phosphor.lumos.LumosSequencePlayer
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.runtime.AgentDescriptor
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase

/**
 * Drives a live simulation and transduces each tick into a `VoxelFrame`.
 *
 * The bench instrument opposite [Oscilloscope]: the oscilloscope records a
 * signal, the generator produces one. It owns the two halves of the live Lumos
 * path — a [CognitiveSceneRuntime] and a [VoxelFrameBuilder] — and steps them
 * together, so the frames it emits are the same frames a trace is recorded from.
 * [Oscilloscope.capture] drives one of these; so does [LumosSource.Live]. That
 * shared path is what makes replay-equals-live a property of the code rather
 * than a coincidence of two implementations.
 *
 * **The timestep is fixed at `1 / fps` and never reads the wall clock.** Phase
 * accumulators inside `VoxelFrameBuilder` integrate `dt` as `Float`, so the
 * frame stream is a function of how many times it was stepped and nothing else.
 * Feeding it a measured frame delta instead would make output depend on machine
 * load, and a recording of it would never replay identically. Wall time decides
 * *when* [frames] takes a step, never *how big* the step is.
 *
 * Stateful and single-threaded, like the builder it wraps: one generator per
 * stream, driven from one coroutine.
 *
 * ### The body
 *
 * [setPhase], [setActivity] and [setEscalated] drive the orb's look from
 * cognitive signal. They write to one agent in the scene — the one
 * [LumosRenderConfig.agentId] names, or else the scene's first agent by id,
 * the one `VoxelFrameBuilder` reads activity and escalation from — adding it
 * if the scene has none, and the first of them installs a
 * [CognitiveLookSwitch] on the frame builder. Until one is called the
 * generator reads no agent and wears its look by hand, so a generator that
 * never calls them produces the frames it always has. [setLook] picks a look
 * directly and needs no switch.
 *
 * ### Sequences
 *
 * [play] puts the generator on a [LumosSequence]: at each step boundary it
 * applies what the step names — [setAtmospherePreset], [morphTo], [setLook],
 * [queueGlyph] — through the one path [LiveFrameSource.follow] implements, so
 * `Oscilloscope` captures a sequence and a live orb plays it frame for frame.
 * `play(sequence)` makes the generator the clock; `play(player)` follows a
 * [LumosSequencePlayer] someone else advances, such as the orb's `OrbStepper`,
 * which lets a scroll position or any external driver own time.
 *
 * @param configuration Scene to simulate. Atmosphere must be enabled — it is the
 *  only subsystem a `VoxelFrame` reads.
 * @param renderConfig Embedder knobs forwarded to the frame builder.
 * @param fps Steps per simulated second; also the cadence [frames] paces at.
 * @param probe Per-phase timing sink forwarded to the builder.
 * @param lookSwitch The switch the first of [setPhase], [setActivity] and
 *  [setEscalated] installs; null installs a [CognitiveLookSwitch] with the
 *  confirmed table. Nothing is installed before one of them is called.
 */
class SignalGenerator(
    configuration: SceneConfiguration,
    renderConfig: LumosRenderConfig = LumosRenderConfig(),
    val fps: Int = DEFAULT_FPS,
    probe: FrameProbe = FrameProbe.Disabled,
    private val lookSwitch: CognitiveLookSwitch? = null,
) {
    init {
        require(fps > 0) { "fps must be > 0, was $fps" }
        require(configuration.enableAtmosphere) {
            "SignalGenerator requires SceneConfiguration.enableAtmosphere = true"
        }
    }

    private val runtime = CognitiveSceneRuntime(configuration)

    private val builder =
        VoxelFrameBuilder(
            initialResolution = configuration.initialAtmosphere.resolution,
            config = renderConfig,
            probe = probe,
        )

    // One stepping path whoever steps: nextFrame() at the fixed 1 / fps, a driver through
    // asFrameSource() at its own pace.
    private val live = LiveFrameSource(runtime, builder)

    private val configuredAgentId: String? = renderConfig.agentId

    // The agent the signal setters write to, chosen and the switch installed by the first of them.
    private var signalAgentId: String? = null

    private val dt: Float = 1f / fps

    /** Wall-clock duration of one simulated step, `1 / fps`. */
    val frameDuration: Duration = (1.seconds / fps)

    /** How many frames this generator has produced. */
    var frameCount: Long = 0L
        private set

    /**
     * Transition the scene toward [state]. Takes effect on the next step.
     *
     * @param state New atmosphere value.
     * @param spec Overrides the tabled duration and easing; null consults the
     *  table. [AtmosphereTransitionSpec.Immediate] snaps instead of interpolating,
     *  which is how a tuning surface drives a live preview from a slider.
     */
    fun setAtmosphere(
        state: AtmosphereState,
        spec: AtmosphereTransitionSpec? = null,
    ) {
        runtime.setAtmosphere(state, spec)
    }

    /**
     * Transition the scene toward the named preset.
     *
     * @param name Registered preset name, matched case-insensitively.
     * @param spec Overrides the tabled duration and easing; null consults the table.
     * @throws IllegalArgumentException when no preset carries that name.
     */
    fun setAtmospherePreset(
        name: String,
        spec: AtmosphereTransitionSpec? = null,
    ) {
        runtime.setAtmospherePreset(name, spec)
    }

    /**
     * Reshape the orb into [form] from the next step on, every cell springing
     * from where it is to its matched position; see `VoxelFrameBuilder.morphTo`.
     *
     * @param form The form to morph to.
     * @param count Cells to sample it at; null keeps the current count.
     * @param choreography The arc cells travel on.
     * @param durationSeconds How long the spring takes to settle; `0` snaps.
     * @param easingName Easing for the arc envelope only, by `Easing.byName`; null is linear.
     */
    fun morphTo(
        form: LumosForm,
        count: Int? = null,
        choreography: Choreography = Choreography.FLOW,
        durationSeconds: Float = VoxelFrameBuilder.DEFAULT_MORPH_SECONDS,
        easingName: String? = null,
    ) {
        builder.morphTo(
            form = form,
            count = count ?: builder.currentCount,
            choreography = choreography,
            durationSeconds = durationSeconds,
            easingName = easingName,
        )
    }

    /** Whether a [morphTo] is still settling. */
    val isMorphing: Boolean get() = builder.isMorphing

    /**
     * Fire a glyph, carved into the orb over [durationSeconds]. Replaces any
     * glyph already burning.
     */
    fun queueGlyph(
        glyph: LumosGlyph,
        durationSeconds: Float = DEFAULT_GLYPH_SECONDS,
    ) {
        builder.queueGlyph(glyph, durationSeconds)
    }

    /**
     * Set the agent's cognitive phase. Takes effect on the next step.
     *
     * The first call to this, [setActivity] or [setEscalated] installs the
     * look switch, and the frame after it is the switch's baseline: the orb
     * wears that frame's look outright and fires no glyph. From then on a
     * change blends over `VoxelFrameBuilder.LOOK_BLEND_SECONDS` and fires the
     * glyph its look carries; see `VoxelFrameBuilder.lookSwitch`. A caller that
     * wants its first change to blend sets the starting signal before the
     * first frame.
     */
    fun setPhase(phase: CognitivePhase) {
        runtime.agents.updateAgentCognitivePhase(signalAgent(), phase)
    }

    /** Set the agent's activity. Takes effect on the next step; see [setPhase]. */
    fun setActivity(state: AgentActivityState) {
        runtime.agents.updateAgentState(signalAgent(), state)
    }

    /**
     * Raise or clear the agent's escalation, which wins over phase and
     * activity while it holds. Takes effect on the next step; see [setPhase].
     */
    fun setEscalated(escalated: Boolean) {
        runtime.agents.updateAgentEscalated(signalAgent(), escalated)
    }

    /**
     * Blend to [look] (`VoxelFrameBuilder.lookTo`). With a switch installed,
     * the next signal change takes over.
     */
    fun setLook(look: Look) {
        builder.lookTo(look)
    }

    /**
     * Play [sequence] from its start, this generator being the clock: every
     * [nextFrame] advances it by `1 / fps` after building, so frame `k` is the
     * sequence at `k / fps`. Its first step is applied at once, against what the
     * scene shows now. Replaces any sequence being played.
     *
     * @return The player, to pause, seek or [LumosSequencePlayer.control].
     */
    fun play(sequence: LumosSequence): LumosSequencePlayer {
        val player = LumosSequencePlayer(sequence)
        live.follow(player, clock = true)
        return player
    }

    /**
     * Follow [player] without advancing it: whoever drives the clock — an orb
     * through [asFrameSource], a scroll position holding
     * [LumosSequencePlayer.control] — decides where the sequence is, and each
     * frame applies the step it falls in. Replaces any sequence being played.
     */
    fun play(player: LumosSequencePlayer) {
        live.follow(player, clock = false)
    }

    /** Leave the sequence; the scene keeps the state its last step put it in. */
    fun stopSequence() {
        live.follow(null)
    }

    /** The sequence player this generator follows, or null. */
    val sequencePlayer: LumosSequencePlayer? get() = live.sequence

    // The agent the setters write to, chosen on first use: the configured one, else the scene's first
    // by id — the one the builder reads — else a new AGENT_ID. Adds it if the scene lacks it, and
    // installs the switch.
    private fun signalAgent(): String {
        signalAgentId?.let { return it }
        val agents = runtime.agents
        val id = configuredAgentId ?: agents.allAgents.minOfOrNull { it.id } ?: AGENT_ID
        if (agents.getAgent(id) == null) {
            agents.addAgent(AgentDescriptor(id = id, position = Vector2.ZERO).toVisualState())
        }
        builder.lookSwitch = lookSwitch ?: CognitiveLookSwitch()
        signalAgentId = id
        return id
    }

    /** Step the simulation once and transduce the snapshot into a frame. */
    fun nextFrame(): VoxelFrame {
        frameCount++
        return live.next(dt)
    }

    /**
     * This generator as a [LumosFrameSource], for a driver such as `LumosOrb`
     * that owns the clock.
     *
     * The driver steps the scene by whatever time it measures, scaled by its
     * speed — a variable step, which is right for a display and wrong for a
     * recording, since nothing replays a stream whose step followed the
     * machine's load. [nextFrame] and [frames] keep the fixed `1 / fps` step
     * [Oscilloscope] records. Drive a generator one way at a time; driven both
     * ways, its scene steps twice. [frameCount] counts fixed steps only.
     *
     * Reduced motion, the paused redraw, and the shared look clock behave as
     * [LiveFrameSource] documents: the source is one over this generator's
     * scene and frame builder.
     */
    fun asFrameSource(): LumosFrameSource = live

    /**
     * The [frames] frames this generator would have produced before the one
     * [nextFrame] last returned, had the atmosphere it is in now always been
     * active: its steady state, run backwards.
     *
     * Oldest first, so the last element sits one step before the current frame.
     * Each is built at the current atmosphere with no transition and no glyph,
     * at the phases the builder would have held that many steps ago
     * ([FramePhases.advanced] by a negative `dt`), with tick and timestamp
     * counted back from the current frame's. Nothing in the generator moves.
     *
     * This is the lead-in [Oscilloscope] fades a loop's tail toward when
     * `CaptureConfig.seamBlendFrames` is set. Read at the loop's first frame, it
     * lets a loop start the frame after a transition lands: the seam closes onto
     * the loop's own motion rather than onto whatever the transition was still
     * carrying, and no recorded frame has to be given over to a settled hold.
     *
     * @throws IllegalArgumentException when [frames] is negative.
     */
    internal fun steadyStateLeadIn(frames: Int): List<VoxelFrame> {
        require(frames >= 0) { "frames must be >= 0, was $frames" }
        val atmosphere =
            checkNotNull(runtime.currentAtmosphere) {
                "SignalGenerator requires SceneConfiguration.enableAtmosphere = true"
            }
        val now = runtime.snapshot()
        val phases = builder.phases
        return List(frames) { index ->
            val stepsBack = frames - index
            builder.buildSteadyState(
                atmosphere = atmosphere,
                phases = phases.advanced(atmosphere, -stepsBack * dt),
                tick = now.frameIndex - stepsBack,
                timestampEpochMillis = ((now.elapsedTimeSeconds - stepsBack * dt) * 1_000.0).toLong(),
            )
        }
    }

    /**
     * Emit the live simulation as a frame stream paced by [timeSource].
     *
     * Exactly one fixed step per emission, with the sleep between steps
     * shortened to absorb drift — so the stream tracks wall time without ever
     * handing the simulation a variable `dt`. A collector that falls behind gets
     * frames late, never frames stepped by a different amount.
     *
     * @param timeSource Clock the pacing reads. Affects only *when* frames are
     *  emitted; the frames themselves are identical under any clock, which is
     *  why a replay can match them.
     */
    fun frames(timeSource: TimeSource = TimeSource.Monotonic): Flow<VoxelFrame> =
        flow {
            val mark = timeSource.markNow()
            var stepped = 0L
            while (true) {
                emit(nextFrame())
                stepped++
                val target = frameDuration * stepped.toDouble()
                val elapsed = mark.elapsedNow()
                if (target > elapsed) {
                    delay(target - elapsed)
                }
            }
        }

    companion object {
        /** Steps per second when the caller does not say. One frame per 60 Hz refresh. */
        const val DEFAULT_FPS: Int = 60

        /** Glyph display window when the caller does not say, in seconds. */
        const val DEFAULT_GLYPH_SECONDS: Float = 1.5f

        /**
         * Id of the agent the signal setters add when the scene has none and
         * [LumosRenderConfig.agentId] names none.
         */
        const val AGENT_ID: String = "lumos"
    }
}
