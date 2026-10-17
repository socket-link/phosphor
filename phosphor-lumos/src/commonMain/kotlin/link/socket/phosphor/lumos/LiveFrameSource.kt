package link.socket.phosphor.lumos

import link.socket.phosphor.choreography.AtmosphereChoreographer
import link.socket.phosphor.choreography.AtmosphereTransitionSpec
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.AtmosphereTransition
import link.socket.phosphor.signal.LumosInput

/**
 * A live scene as a [LumosFrameSource]: a [CognitiveSceneRuntime] and the [VoxelFrameBuilder] that
 * transduces its snapshots into frames, stepped together by whichever driver owns the clock.
 *
 * Wrap the pair you already have. Whatever sets its atmosphere or fires its glyphs keeps doing so on
 * [runtime] and [builder] directly; this source only steps them, and reads them to answer the
 * driver:
 *
 * - [still] lands any atmosphere transition on its target at once — re-set with
 *   [AtmosphereTransitionSpec.Immediate], so switching reduced motion off cannot jump back — and
 *   draws [VoxelFrameBuilder.buildStill]: the pose at the current phases, with a glyph at full
 *   strength for its window.
 * - [revision] changes when an atmosphere is set. Firing a glyph does not change it, so a paused
 *   orb never freezes on a glyph it cannot finish.
 * - [lookKey] is the name of the preset the scene is in, or heading to, with the builder's [Look]
 *   after a slash when it is not [LumosLook.BASE]; a custom atmosphere has none.
 * - [align] puts the builder's phases where a steady scene at the clock's time would hold them.
 *   Mid-transition it moves nothing, and during a look blend it sets the incoming look's clock
 *   alone.
 * - [alignLook] sets the incoming look's clock alone, in a look blend's first moments, so an orb
 *   whose look switched takes up its siblings' phase without its spin, its breath or the look it
 *   is leaving jumping. A switch-driven change ([VoxelFrameBuilder.lookSwitch]) and
 *   [VoxelFrameBuilder.lookTo] both blend; a manual [VoxelFrameBuilder.look] cut and an
 *   atmosphere change alone do not, and leave every phase where it is.
 * - [setInput] hands the pointer to [runtime], whose next snapshot carries it to [builder].
 *
 * ### Following a sequence
 *
 * [follow] puts the source on a [LumosSequencePlayer]: before every frame it reads the player's
 * snapshot and, on entering a step, applies what the step names — the preset through
 * [CognitiveSceneRuntime.setAtmospherePreset], the form through [VoxelFrameBuilder.morphTo], the
 * look through [VoxelFrameBuilder.lookTo], the glyph through [VoxelFrameBuilder.queueGlyph]. A
 * step that names the preset, form or look the orb already shows applies nothing, so a loop step
 * can follow the one-shot that landed its state. Who advances the player is the caller's choice:
 * `clock = true` has this source advance it by each frame's `dt` *after* building the frame, so
 * frame `k` of a fixed-step stream is the sequence at `k / fps` — what `Oscilloscope` captures —
 * while `clock = false` leaves the clock to a driver such as `OrbStepper`.
 *
 * Stateful and single-threaded, like the pair it wraps.
 *
 * @property runtime The scene. Its atmosphere subsystem must be enabled; it is the only one a
 *  frame reads.
 * @property builder The frame builder, whose lattice and phases this source steps.
 * @throws IllegalArgumentException when [runtime] was configured without an atmosphere.
 */
class LiveFrameSource(
    val runtime: CognitiveSceneRuntime,
    val builder: VoxelFrameBuilder,
) : LumosFrameSource {
    /**
     * Build the pair from the runtime's inputs.
     *
     * @param configuration Scene to simulate; atmosphere must be enabled.
     * @param renderConfig Embedder knobs forwarded to the frame builder.
     * @param probe Per-phase timing sink forwarded to the frame builder.
     */
    constructor(
        configuration: SceneConfiguration,
        renderConfig: LumosRenderConfig = LumosRenderConfig(),
        probe: FrameProbe = FrameProbe.Disabled,
    ) : this(
        runtime = CognitiveSceneRuntime(configuration),
        builder =
            VoxelFrameBuilder(
                initialResolution = configuration.initialAtmosphere.resolution,
                config = renderConfig,
                probe = probe,
            ),
    )

    /**
     * A scene with nothing in it but an atmosphere — the one subsystem a frame reads — starting
     * at [atmosphere]: what an orb on its own needs.
     *
     * @param atmosphere Where the scene starts, such as `AtmospherePresets.THINKING`.
     * @param renderConfig Embedder knobs forwarded to the frame builder.
     * @param probe Per-phase timing sink forwarded to the frame builder.
     */
    constructor(
        atmosphere: AtmosphereState,
        renderConfig: LumosRenderConfig = LumosRenderConfig(),
        probe: FrameProbe = FrameProbe.Disabled,
    ) : this(
        configuration =
            SceneConfiguration(
                width = 1,
                height = 1,
                enableWaveform = false,
                enableParticles = false,
                enableFlow = false,
                enableEmitters = false,
                enableCamera = false,
                enableAtmosphere = true,
                initialAtmosphere = atmosphere,
            ),
        renderConfig = renderConfig,
        probe = probe,
    )

    private val choreographer: AtmosphereChoreographer =
        requireNotNull(runtime.atmosphereChoreographer) {
            "LiveFrameSource requires SceneConfiguration.enableAtmosphere = true"
        }

    private var seenTransition: AtmosphereTransition? = null
    private var seenAtmosphere: AtmosphereState? = null
    private var changes: Long = 0L

    /** The sequence player this source follows, or null; see [follow]. */
    var sequence: LumosSequencePlayer? = null
        private set

    private var clocked: Boolean = false
    private var releaseConnection: (() -> Unit)? = null
    private var appliedCycle: Int = -1
    private var appliedStep: Int = -1
    private var appliedPreset: String? = null
    private var appliedForm: String? = null
    private var appliedLook: Look? = null

    /**
     * Follow [player], or stop following with null.
     *
     * The current step is applied at once, against what the orb shows now: the preset the scene
     * is in, the form and look the builder wears. A step naming those applies nothing.
     *
     * @param player The clock to read, or null to leave the sequence.
     * @param clock Whether this source advances [player] by each frame's `dt`, holding a
     *  connection on it while it follows. False leaves time to the driver.
     */
    fun follow(
        player: LumosSequencePlayer?,
        clock: Boolean = false,
    ) {
        releaseConnection?.invoke()
        releaseConnection = null
        sequence = player
        clocked = clock && player != null
        appliedCycle = -1
        appliedStep = -1
        if (player == null) return
        appliedPreset = presetNameOf(choreographer.activeTransition?.to ?: choreographer.currentState)
        appliedForm = formNameOf(builder.form)
        appliedLook = builder.look
        if (clock) releaseConnection = player.connect()
        applyStep(player.snapshot)
    }

    override fun next(dt: Float): VoxelFrame {
        sequence?.let { applyStep(it.snapshot) }
        val frame = builder.build(runtime.update(dt), dt)
        if (clocked) sequence?.advance(dt)
        return frame
    }

    override fun still(dt: Float): VoxelFrame {
        sequence?.let { applyStep(it.snapshot) }
        choreographer.activeTransition?.let { transition ->
            choreographer.setAtmosphere(transition.to, transition.toPresetName, AtmosphereTransitionSpec.Immediate)
        }
        val frame = builder.buildStill(runtime.update(dt), dt)
        if (clocked) sequence?.advance(dt)
        return frame
    }

    // Apply the snapshot's step if it is not the one applied last; a wrapped sequence re-enters
    // its steps, so the cycle counts too.
    private fun applyStep(snapshot: LumosSequenceSnapshot) {
        if (snapshot.cycle == appliedCycle && snapshot.stepIndex == appliedStep) return
        appliedCycle = snapshot.cycle
        appliedStep = snapshot.stepIndex
        val step = snapshot.step
        val transition = step.transition
        var applied = false

        step.atmospherePreset?.lowercase()?.let { preset ->
            if (preset != appliedPreset) {
                appliedPreset = preset
                applied = true
                runtime.setAtmospherePreset(
                    preset,
                    transition?.let { AtmosphereTransitionSpec(it.durationSeconds, it.easingName) },
                )
            }
        }
        step.form?.lowercase()?.let { formName ->
            if (formName != appliedForm) {
                appliedForm = formName
                applied = true
                val form =
                    if (formName == LumosStep.LATTICE_FORM_NAME) {
                        Forms.Lattice((choreographer.activeTransition?.to ?: choreographer.currentState).resolution)
                    } else {
                        requireNotNull(Forms.byName(formName)) { "Unknown form: '$formName'" }
                    }
                if (transition == null) {
                    builder.morphTo(form)
                } else {
                    builder.morphTo(
                        form = form,
                        choreography = transition.choreography,
                        durationSeconds = transition.durationSeconds,
                        easingName = transition.easingName,
                    )
                }
            }
        }
        step.look?.let { look ->
            if (look != appliedLook) {
                appliedLook = look
                applied = true
                builder.lookTo(look, transition?.durationSeconds ?: VoxelFrameBuilder.LOOK_BLEND_SECONDS)
            }
        }
        step.glyph?.let { glyph ->
            val window = transition?.durationSeconds?.takeIf { it > 0f } ?: DEFAULT_GLYPH_SECONDS
            builder.queueGlyph(glyph, window)
        }
        if (applied) changes++
    }

    private fun presetNameOf(state: AtmosphereState): String? =
        AtmospherePresets.ALL.firstOrNull { (_, preset) -> preset == state }?.first

    private fun formNameOf(form: LumosForm): String? =
        if (form is Forms.Lattice) {
            LumosStep.LATTICE_FORM_NAME
        } else {
            Forms.All.firstOrNull { (_, catalogued) -> catalogued === form }?.first
        }

    // The choreographer allocates a transition each time an atmosphere is set, and replaces it only
    // when the runtime steps, so while a driver is paused identity alone says whether one was set.
    override val revision: Long
        get() {
            val transition = choreographer.activeTransition
            val atmosphere = choreographer.currentState
            if (transition !== seenTransition || atmosphere !== seenAtmosphere) {
                seenTransition = transition
                seenAtmosphere = atmosphere
                changes++
            }
            return changes
        }

    override val lookKey: String?
        get() {
            val heading = choreographer.activeTransition?.to ?: choreographer.currentState
            val preset = AtmospherePresets.ALL.firstOrNull { (_, preset) -> preset == heading }?.first ?: return null
            val look = builder.look
            return if (look.look == LumosLook.BASE) preset else "$preset/${look.key}"
        }

    override fun align(lookTime: Double) {
        if (builder.isLookBlending) {
            builder.phases = builder.phases.copy(lookSeconds = lookTime)
            return
        }
        if (choreographer.activeTransition != null) return
        builder.phases = FramePhases.at(choreographer.currentState, lookTime)
    }

    override fun alignLook(lookTime: Double) {
        builder.alignLook(lookTime)
    }

    override fun setInput(input: LumosInput) = runtime.setInput(input)

    companion object {
        /** How long a sequence step's glyph burns when the step says nothing: the builder's own default. */
        const val DEFAULT_GLYPH_SECONDS: Float = 1.5f
    }
}
