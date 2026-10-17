package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.field.Choreography
import link.socket.phosphor.field.Force
import link.socket.phosphor.field.FormMatcher
import link.socket.phosphor.field.FormMorph
import link.socket.phosphor.field.VoxelSphere
import link.socket.phosphor.lumos.look.CellModulation
import link.socket.phosphor.lumos.look.LookModulator
import link.socket.phosphor.lumos.look.Looks
import link.socket.phosphor.lumos.look.ViewPoint
import link.socket.phosphor.lumos.look.ViewRotation
import link.socket.phosphor.lumos.probe.FramePhase
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.lumos.probe.measure
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.AtmospherePattern
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.AtmosphereTransition
import link.socket.phosphor.signal.CognitivePhase
import link.socket.phosphor.signal.LumosInput
import link.socket.phosphor.timeline.Easing

/**
 * Stateful translator from Phosphor's runtime state into a [VoxelFrame].
 *
 * The builder owns continuous phase accumulators ([phases]) and the current
 * [form] so the surrounding pull-based runtime can call [build] each tick
 * without re-allocating geometry or resetting animation phase. Atmosphere
 * frequency changes can be interpolated through transitions without phase
 * discontinuities because the accumulators persist across calls and are
 * advanced by the current state's rate.
 *
 * The builder is intentionally mutable and single-threaded: one builder per
 * renderer, called from the render loop. Sharing across threads or sharing
 * across renderers will produce torn output.
 *
 * @param initialResolution Starting resolution for the default [Forms.Lattice]
 *  form. Rebuilt when [AtmosphereState.resolution] changes.
 * @param config Optional embedder knobs; see [LumosRenderConfig].
 * @param probe Per-phase timing sink. Defaults to [FrameProbe.Disabled], which
 *  adds no per-frame cost; pass a [link.socket.phosphor.lumos.probe.RingBufferFrameProbe]
 *  to capture BUILD timings.
 */
class VoxelFrameBuilder(
    initialResolution: Int,
    private val config: LumosRenderConfig = LumosRenderConfig(),
    private val probe: FrameProbe = FrameProbe.Disabled,
) {
    /**
     * The phase accumulators as of the most recent [build]: breath pulse,
     * pattern, and the orb's two rotations.
     *
     * Each persists across calls and is advanced by the current state's rate
     * ([FramePhases.advanced]), so a frequency that interpolates through a
     * transition produces no phase discontinuity. Read them to reconstruct
     * frames the builder never produced; see [buildSteadyState].
     *
     * Set them to re-anchor the accumulators — to the phase a shared clock says
     * every orb of this look holds, for one ([LiveFrameSource.align]). The next
     * [build] advances from what was set.
     */
    var phases: FramePhases = FramePhases.ZERO

    /** Continuous breath-pulse phase in radians; [FramePhases.pulsePhase]. */
    val pulsePhase: Float get() = phases.pulsePhase

    /** Continuous pattern-evaluation phase; [FramePhases.patternPhase]. */
    val patternPhase: Float get() = phases.patternPhase

    /** Integrated X-axis rotation in radians. Exposed via [VoxelAmbient.orbRotationX]. */
    val orbRotationX: Float get() = phases.orbRotationX

    /**
     * Integrated Y-axis rotation in radians. Exposed via [VoxelAmbient.orbRotationY], unless
     * the [look] owns the yaw ([link.socket.phosphor.lumos.look.LookModulator.yaw]), which the
     * frame carries instead.
     */
    val orbRotationY: Float get() = phases.orbRotationY

    private var lattice: Forms.Lattice = Forms.Lattice(initialResolution)

    /**
     * Where the cells sit: the geometry [build] reads its rest positions from.
     *
     * Defaults to [Forms.Lattice] at the initial resolution, and while the form
     * is a lattice it follows [AtmosphereState.resolution]: a resolution change
     * replaces it with the lattice at the new resolution, exactly as the voxel
     * sphere was rebuilt before forms existed. Any other form is left alone by
     * a resolution change and is sampled with the new resolution's cell count.
     *
     * Sampled once per frame with the count of the lattice at the atmosphere's
     * resolution, the [look]'s idiom, and the frame's time in seconds; see
     * [LumosForm.sample]. A [ViewForm] is also handed the rotation the frame
     * will be projected with. Setting it takes effect on the next [build].
     *
     * Setting it is a cut: the next frame is the new form at rest, cell `i` is
     * whatever the new form's cell `i` is, and any morph in flight is dropped.
     * [morphTo] is the way to change form while keeping each cell's identity.
     */
    var form: LumosForm
        get() = activeForm
        set(value) {
            adoptForm(value)
            permutation = null
            morph = null
        }

    private var activeForm: LumosForm = lattice

    private fun adoptForm(value: LumosForm) {
        activeForm = value
        if (value is Forms.Lattice) lattice = value
    }

    /**
     * How the cells move: the idiom the orb is wearing, [LumosLook.BASE] by
     * default.
     *
     * Each frame, every cell is rotated into view space and handed to the look's
     * [LookModulator], which scales it, fades it, moves it and lights it as the
     * idiom says, on the look's shared clock ([FramePhases.lookSeconds]). The
     * modulator and its tables are built once from the current [form]'s sample,
     * and again when the look or the sample changes. [LumosLook.BASE] without
     * [LumosRenderConfig.rimFade] builds none, and the frame is exactly what the
     * builder produced before looks existed. Setting it takes effect on the next
     * [build].
     *
     * Setting it is a cut: the next frame wears the new look outright, and any
     * blend in flight and any look waiting behind it are dropped. [lookTo] is
     * the way to change look over time. Mid-blend this reads the look the blend
     * is heading to.
     */
    var look: Look
        get() = worn
        set(value) {
            if (value != worn) {
                worn = value
                modulator = null
            }
            landBlend()
            pendingLook = null
        }

    private var worn: Look = Look(LumosLook.BASE)

    private var modulator: LookModulator? = null
    private var modulatorSample: FormSample? = null

    // Reused by every cell of every frame; a modulator reads them and keeps nothing.
    private val rotation = ViewRotation()
    private val view = ViewPoint()
    private val modulation = CellModulation()
    private val outgoingModulation = CellModulation()

    /**
     * What drives the look from the scene's cognitive signal, or null — the
     * default — to leave [look] manual, exactly as it was before the switch
     * existed: no frame reads an agent, and every preset, trace and golden file
     * is byte-identical.
     *
     * With a switch, [build] and [buildStill] read the phase, activity and
     * escalation of one agent from the snapshot — the one
     * [LumosRenderConfig.agentId] names, or the scene's dominant phase with the
     * first agent's activity and escalation — together with
     * [LumosRenderConfig.unattended], and ask the switch only when one of those
     * four has changed. The first frame a builder sees is a baseline: it wears
     * the choice's look outright and fires no glyph, so an orb mounted on a
     * finished run does not flash a check. From then on a changed choice blends
     * to its look with [lookTo] if that is not already the look worn, and fires
     * its glyph if it carries one. A manual [look] set in between sticks until
     * the signal next changes.
     *
     * While the selected agent reads [AgentActivityState.SPAWNING], the orb's
     * presence fades in from 0 over [SPAWN_SECONDS] of `dt` — `AgentLayer`'s own
     * spawn ramp, so the two agree — multiplying every cell's alpha and the
     * ambient glow on top of whichever look is worn. [buildStill] lands it at 1
     * at once.
     *
     * Setting the switch makes the next frame a baseline again.
     */
    var lookSwitch: CognitiveLookSwitch? = null
        set(value) {
            field = value
            signalSeen = false
            lastChoice = null
        }

    // The look a blend is leaving, with its modulator over outgoingSample and its own clock; null
    // when no blend is in flight. blendSample is the sample the blend began over: a sample change
    // mid-blend is a cut, since the outgoing modulator's tables are sized for the old count.
    private var outgoingLook: Look? = null
    private var outgoingModulator: LookModulator? = null
    private var outgoingSample: FormSample? = null
    private var outgoingSeconds: Double = 0.0
    private var blendSample: FormSample? = null
    private var blendElapsed: Float = 0f
    private var blendDuration: Float = LOOK_BLEND_SECONDS

    // Whether the blend in flight has taken its incoming look's shared clock; see alignLook.
    private var blendAligned: Boolean = false

    // The look asked for mid-blend, taken up when the blend lands.
    private var pendingLook: Look? = null
    private var pendingDuration: Float = LOOK_BLEND_SECONDS

    // The signal as last read, so the switch is asked only when it changes; see lookSwitch.
    private var signalSeen: Boolean = false
    private var signalPhase: CognitivePhase = CognitivePhase.NONE
    private var signalActivity: AgentActivityState? = null
    private var signalEscalated: Boolean = false
    private var signalUnattended: Boolean = false
    private var lastChoice: CognitiveLook? = null

    // The spawn fade: seconds of dt since SPAWNING was first read, and the presence it gives.
    private var spawnElapsed: Float = 0f
    private var presence: Float = 1f

    /** Whether a [lookTo] is in flight: two looks are being drawn, mixed by the blend's weight. */
    val isLookBlending: Boolean get() = outgoingLook != null

    /**
     * How far the blend in flight has come, `0..1`, eased: the weight on the
     * incoming look. 1 when no blend is in flight. Never decreases over a blend
     * and lands at exactly 1.
     */
    val lookBlendProgress: Float
        get() = if (outgoingLook == null) 1f else smoothstep(blendElapsed / blendDuration)

    /**
     * Change [look] over time: blend from the look worn now to [look] over
     * [durationSeconds] of `dt`.
     *
     * Each frame of the blend, both looks' modulators run on the same view, and
     * every cell's radius, alpha, extent, push and lighting mix by
     * `w = smoothstep(elapsed / duration)`. [LumosLook.BASE] without
     * [LumosRenderConfig.rimFade] mixes as the identity. The outgoing look keeps
     * its own clock, advanced by `dt`; [FramePhases.lookSeconds] is the incoming
     * look's, so [look] — and `LiveFrameSource.lookKey` — name the target at
     * once. A look that owns the yaw blends it along the short arc with the
     * other look's, or the atmosphere's, and the rate the spin carries cells at
     * blends the same way, so entering or leaving such a look never jumps the
     * spin.
     *
     * Asked for mid-blend: back to the look being left reverses in place —
     * `elapsed` becomes `duration − elapsed` and the two looks swap, and since
     * the easing is symmetric nothing jumps; the duration in flight is kept.
     * Any other look waits, and the blend to the latest one asked for starts
     * when this one lands. Looks therefore never jump and change at most once
     * per blend, so a 50 ms signal does not flicker past. A sample change
     * mid-blend — a resolution change, or a form that lays out differently per
     * look — drops the outgoing look: a cut, as it was before blends existed.
     *
     * Setting [look] is still a cut, and drops any blend and pending look.
     * [buildStill] lands a blend at once; [buildSteadyState] draws the incoming
     * look alone.
     *
     * @param look Where to head; [look] reads it from now on.
     * @param durationSeconds How long the blend takes, [LOOK_BLEND_SECONDS] by
     *  default. `0` cuts, as setting [look] does.
     * @throws IllegalArgumentException when [durationSeconds] is negative.
     */
    fun lookTo(
        look: Look,
        durationSeconds: Float = LOOK_BLEND_SECONDS,
    ) {
        require(durationSeconds >= 0f && durationSeconds.isFinite()) {
            "durationSeconds must be >= 0, was $durationSeconds"
        }
        if (durationSeconds == 0f) {
            this.look = look
            return
        }
        val outgoing = outgoingLook
        if (outgoing == null) {
            if (look != worn) beginBlend(look, durationSeconds)
            return
        }
        pendingLook = null
        when (look) {
            worn -> Unit
            outgoing -> reverseBlend(outgoing)
            else -> {
                pendingLook = look
                pendingDuration = durationSeconds
            }
        }
    }

    private fun beginBlend(
        target: Look,
        durationSeconds: Float,
    ) {
        outgoingLook = worn
        outgoingModulator = modulator
        outgoingSample = modulatorSample
        outgoingSeconds = phases.lookSeconds
        blendSample = null
        blendElapsed = 0f
        blendDuration = durationSeconds
        blendAligned = false
        worn = target
        modulator = null
        modulatorSample = null
    }

    /** Head back to [outgoing]: the two looks swap and the blend runs the other way from here. */
    private fun reverseBlend(outgoing: Look) {
        val fromModulator = outgoingModulator
        val fromSample = outgoingSample
        val fromSeconds = outgoingSeconds
        outgoingLook = worn
        outgoingModulator = modulator
        outgoingSample = modulatorSample
        outgoingSeconds = phases.lookSeconds
        worn = outgoing
        modulator = fromModulator
        modulatorSample = fromSample
        phases = phases.copy(lookSeconds = fromSeconds)
        blendElapsed = blendDuration - blendElapsed
    }

    /**
     * Set the incoming look's clock, [FramePhases.lookSeconds], to [seconds] — the shared
     * [LookClock]'s time for the look a blend is heading to — and nothing else: pulse, pattern,
     * rotation and the outgoing look's clock are left where they are, so neither the spin nor the
     * look being left jumps.
     *
     * Only in a blend's first moments, once per blend: while the incoming look weighs less than
     * [LOOK_ALIGN_WEIGHT]. A driver sees the new look a frame after the blend begins, at about 1 %
     * weight at 60 fps, so the re-anchor does not show. A reversal is the same blend, so it does
     * not align again; the look it heads back to has kept its own clock throughout. At any other
     * time — no blend, a blend already aligned or too far in — this does nothing.
     *
     * @return Whether the clock was set.
     */
    internal fun alignLook(seconds: Double): Boolean {
        if (outgoingLook == null || blendAligned || lookBlendProgress >= LOOK_ALIGN_WEIGHT) return false
        phases = phases.copy(lookSeconds = seconds)
        blendAligned = true
        return true
    }

    /** Drop the outgoing look: the incoming look is worn alone from the next frame. */
    private fun landBlend() {
        outgoingLook = null
        outgoingModulator = null
        outgoingSample = null
        blendSample = null
        blendElapsed = 0f
    }

    /**
     * Advance the blend in flight by [dt]; when it lands, start the blend to the
     * look waiting behind it, if any.
     */
    private fun stepBlend(dt: Float) {
        if (outgoingLook == null) return
        blendElapsed += dt
        outgoingSeconds += dt
        if (blendElapsed >= blendDuration) {
            landBlend()
            val next = pendingLook ?: return
            pendingLook = null
            if (next != worn) beginBlend(next, pendingDuration)
        }
    }

    /**
     * Read the signal [lookSwitch] routes from [snapshot] and apply its choice;
     * nothing when there is no switch. See [lookSwitch].
     */
    private fun readSignal(snapshot: SceneSnapshot) {
        val switch = lookSwitch ?: return
        val agents = snapshot.agentStates
        val agentId = config.agentId
        var agent: AgentVisualState? = null
        if (agentId == null) {
            if (agents.isNotEmpty()) agent = agents[0]
        } else {
            for (i in agents.indices) {
                val candidate = agents[i]
                if (candidate.id == agentId) {
                    agent = candidate
                    break
                }
            }
        }
        val phase = if (agentId == null) snapshot.choreographyPhase else agent?.cognitivePhase ?: CognitivePhase.NONE
        val activity = agent?.state
        val escalated = agent?.escalated ?: false
        val unattended = config.unattended
        if (signalSeen &&
            phase == signalPhase &&
            activity == signalActivity &&
            escalated == signalEscalated &&
            unattended == signalUnattended
        ) {
            return
        }
        signalPhase = phase
        signalActivity = activity
        signalEscalated = escalated
        signalUnattended = unattended

        val choice = switch.lookFor(phase, activity, escalated, unattended)
        val previous = lastChoice
        lastChoice = choice
        if (!signalSeen) {
            // The baseline: wear the choice, fire nothing.
            signalSeen = true
            look = choice.look
            return
        }
        if (choice == previous) return
        if (choice.look != worn) lookTo(choice.look)
        val glyph = choice.glyph
        if (glyph != null) queueGlyph(glyph)
    }

    /** Advance the spawn fade by [dt]: presence ramps while the selected agent is spawning. */
    private fun stepSpawn(dt: Float) {
        if (lookSwitch != null && signalActivity == AgentActivityState.SPAWNING) {
            spawnElapsed += dt
            presence = smoothstep(spawnElapsed / SPAWN_SECONDS)
        } else {
            spawnElapsed = 0f
            presence = 1f
        }
    }

    /**
     * The lattice at the atmosphere's current resolution. Rebuilt when
     * [AtmosphereState.resolution] changes and stable across ticks otherwise —
     * no per-frame allocation. It is the geometry being rendered only while
     * [form] is a lattice, which it is by default.
     */
    val voxelSphere: VoxelSphere get() = lattice.sphere

    private val matcher = FormMatcher()

    // Which sample index rendered cell i reads: identity when null. Set by morphTo so a cell keeps
    // its index across a form change, cleared by anything that cuts (setting form, a resolution change).
    private var permutation: IntArray? = null

    // The body: in flight after a morphTo or a burst, or standing at rest for the pointer to push.
    // Null when frames read the sample directly.
    private var morph: FormMorph? = null

    // The framing the morph left from, blended toward the new form's by the morph's progress.
    private var morphFramingFrom: Framing = Framing.NONE

    // The pattern the morph left from, blended toward the new form's the same way; see MixFrom.
    private var morphMixFrom: MixFrom? = null

    // The last frame time the form was sampled at, so morphTo samples the form where build did.
    private var lastTimeSeconds: Float = 0f

    // The pointer the last frame's form was sampled with, so morphTo samples a gazing form where build did.
    private var gaze: Vector2? = null

    // The one repel the pointer aims, re-aimed every frame and on the body's forces only while it pushes.
    private val repel = Force.Repel()

    // Carries the pointer back into the body's space by the frame's own angles; see aimRepel.
    private val aimRotation = ViewRotation()

    // The last body stood for the pointer, reused by the next while the count holds.
    private var restingBody: FormMorph? = null

    private var activeGlyph: GlyphLifecycle? = null

    // The last still built and what it was built from; see buildStill.
    private var stillFrame: VoxelFrame? = null
    private var stillAtmosphere: AtmosphereState? = null
    private var stillPhases: FramePhases? = null
    private var stillGlyph: LumosGlyph? = null
    private var stillSample: FormSample? = null
    private var stillPermutation: IntArray? = null
    private var stillLook: Look? = null

    /** True if a glyph is currently being rendered. */
    val hasActiveGlyph: Boolean get() = activeGlyph != null

    /** Queue a glyph for display. Replaces any currently active glyph. */
    fun queueGlyph(
        glyph: LumosGlyph,
        durationSeconds: Float = 1.5f,
    ) {
        require(durationSeconds > 0f) { "durationSeconds must be > 0" }
        activeGlyph =
            GlyphLifecycle(
                glyph = glyph,
                totalDurationSeconds = durationSeconds,
                ageSeconds = 0f,
            )
    }

    /**
     * Whether a [morphTo] is in flight: cells are still springing to the new form's positions. A
     * body standing at rest under the pointer is not a morph; see [build].
     */
    val isMorphing: Boolean get() = morph?.isSettling == true

    /** The body frames are read from, in flight or at rest, or null when they are read from the sample. */
    internal val currentBody: FormMorph? get() = morph

    /**
     * How many cells the current [form] yields at the atmosphere's budget: the
     * count [morphTo] keeps unless told otherwise.
     */
    val currentCount: Int get() = currentSample().count

    /**
     * Reshape the orb into [form], keeping every cell's identity: cell `i` of the
     * frames that follow is cell `i` of the frames before, sprung from where it
     * was to the nearest free position of the new form rather than cut there.
     *
     * The match is [FormMatcher]'s greedy nearest-free assignment, run once
     * here; the motion is a [FormMorph] critically damped spring stepped by
     * every [build], bent by [choreography]'s arc. The spring runs in
     * unit-sphere space before the atmosphere's pulse, squash and bump, which
     * keep applying on top: the skin rides the body. A [FramedForm]'s scale and
     * cell size blend across with the spring's own progress, and so does a
     * [FramedForm.rigid] form's hold on the skin. So does each cell's colour:
     * the atmosphere's pattern is read where the cell sat in the form it left
     * and where it is heading, and mixed by the same progress, so a form whose
     * pattern reads differently does not recolour the orb on the first frame.
     * A spawned cell starts from the colour of the cell it springs out of.
     * Once settled, the builder renders the new form exactly as it renders any
     * form at rest, with no per-frame cost and with its cells in the matched order — which is what
     * keeps a trace's per-index delta coding and a crossfade's cell pairing
     * working across the change.
     *
     * Equal counts are the common case. With a smaller [count], the cells with
     * the highest indices are dropped and the rest are matched, unless
     * [survivors] names the ones to keep. On the lattice the highest indices
     * are one side of the orb, so dropping a spread of cells takes a
     * [survivors] list at an even stride. With a larger
     * one, the cells that exist are matched first and the new form's leftover
     * positions become new cells, which start at the current position of the
     * nearest existing cell and spring out from there. Either way, the frame on
     * which the count changes is a cut for anything that pairs cells by index.
     *
     * Calling this mid-morph starts a new trip from wherever the cells are,
     * keeping their velocity and any [burst] still pushing them. So does
     * calling it while the cells stand parted under the pointer ([build]): the
     * trip starts where the pointer pushed them, and the pointer goes on
     * pushing the new form. A
     * [LumosConfig.reducedMotion] host draws through [buildStill], which
     * snaps the morph to its destination. A resolution
     * change during a morph is a cut: the lattice's count changes, so the morph
     * is dropped and identity reset, as a resolution change always has been.
     *
     * No-op when [form] is already the current form at the current count and
     * nothing is in flight.
     *
     * @param form Where the cells should rest from now on; [form] becomes it. A
     *  [Forms.Lattice] fixes its own count and ignores [count].
     * @param count Cells to sample the new form at; defaults to [currentCount].
     *  A count other than the lattice's is pinned with [counted], so the form
     *  keeps it when the atmosphere's resolution changes.
     * @param choreography The arc cells travel on; see [Choreography].
     * @param durationSeconds How long the spring takes to settle. `0` snaps.
     * @param easingName An `Easing.byName` identifier that reshapes the arc's
     *  envelope only; null or unknown leaves it linear. The spring is not eased.
     * @param survivors When the new form has fewer cells than the orb, the
     *  cells that stay, by index, ascending: survivor `i` becomes cell `i`.
     *  Null, the default, keeps the lowest indices. Ignored unless the count
     *  shrinks.
     * @throws IllegalArgumentException when [count] is not positive,
     *  [durationSeconds] is negative, or [survivors] is not as many strictly
     *  ascending indices of today's cells as the new form has.
     */
    fun morphTo(
        form: LumosForm,
        count: Int = currentCount,
        choreography: Choreography = Choreography.FLOW,
        durationSeconds: Float = DEFAULT_MORPH_SECONDS,
        easingName: String? = null,
        survivors: IntArray? = null,
    ) {
        require(count >= 1) { "count must be >= 1, was $count" }
        require(durationSeconds >= 0f && durationSeconds.isFinite()) {
            "durationSeconds must be >= 0, was $durationSeconds"
        }
        val target = if (form is Forms.Lattice || count == lattice.count) form else form.counted(count)
        if (target === this.form && !isMorphing) return

        val fromSample = currentSample()
        val fromPermutation = permutation
        val fromMorph = morph
        val fromCount = fromSample.count
        val toSample = sampleForm(target, lastTimeSeconds, phases, gaze = gaze)
        val toCount = toSample.count
        val matched = minOf(fromCount, toCount)
        // Which of today's cells each surviving cell was: the lowest indices, or the survivors given.
        val kept = survivors?.takeIf { toCount < fromCount }
        if (kept != null) {
            require(kept.size == toCount) { "survivors must name $toCount cells, named ${kept.size}" }
            for (i in kept.indices) {
                require(kept[i] in 0 until fromCount && (i == 0 || kept[i] > kept[i - 1])) {
                    "survivors must be ascending indices below $fromCount, found ${kept[i]} at $i"
                }
            }
        }
        val origins =
            List(matched) { k ->
                val i = kept?.get(k) ?: k
                if (fromMorph != null) {
                    Vector3(fromMorph.positions[3 * i], fromMorph.positions[3 * i + 1], fromMorph.positions[3 * i + 2])
                } else {
                    fromSample.positions[fromPermutation?.get(i) ?: i]
                }
            }
        val framingFrom = effectiveFraming(fromSample)

        val assignment = matcher.match(origins, toSample.positions)
        val nextPermutation = IntArray(toCount)
        assignment.copyInto(nextPermutation)
        // The sample index each cell was coloured by in the form being left; a spawned cell takes its parent's.
        val mixFromIndex = IntArray(toCount) { -1 }
        for (k in 0 until matched) {
            val i = kept?.get(k) ?: k
            mixFromIndex[k] = fromPermutation?.get(i) ?: i
        }
        val body = FormMorph(toCount)
        for (k in 0 until matched) {
            val origin = origins[k]
            body.positions[3 * k] = origin.x
            body.positions[3 * k + 1] = origin.y
            body.positions[3 * k + 2] = origin.z
            if (fromMorph != null) {
                val i = kept?.get(k) ?: k
                body.velocities[3 * k] = fromMorph.velocities[3 * i]
                body.velocities[3 * k + 1] = fromMorph.velocities[3 * i + 1]
                body.velocities[3 * k + 2] = fromMorph.velocities[3 * i + 2]
            }
        }
        // A burst keeps its own clock, so moving it to the new body carries it on where it was. The
        // pointer's repel moves with it, and the next build aims it at the new body.
        if (fromMorph != null) body.forces.addAll(fromMorph.forces)
        if (toCount > fromCount) {
            spawnLeftovers(nextPermutation, assignment, origins, toSample.positions, body, mixFromIndex)
        }

        // Not through the public setter, which would reset the identity just built.
        adoptForm(target)
        permutation = nextPermutation
        if (durationSeconds == 0f) {
            morph = null
            morphMixFrom = null
            return
        }
        morphFramingFrom = framingFrom
        morphMixFrom = MixFrom(body, fromSample, mixFromIndex)
        body.retarget(
            targets = toSample.positions,
            assignment = nextPermutation,
            choreography = choreography,
            durationSeconds = durationSeconds,
            arcEasing = easingName?.let(Easing::byName),
        )
        morph = body
    }

    /**
     * Push every cell out from the body's centroid for a moment and let the
     * spring bring it home: a [Force.Burst], which Dots fires before every
     * scene morph so the body gathers itself before it reshapes.
     *
     * With a morph in flight, the burst acts on it. A [morphTo] called after
     * this one carries the burst into the new trip, so burst-then-morph is
     * this call followed by that one, in the same frame or a later one.
     *
     * With nothing in flight, there is nothing for the cells to spring back
     * on, so this starts one: a [Choreography.DIRECT] trip from where the
     * cells are to where they rest, `max(0.3, durationSeconds)` long — from
     * their rest positions, or from where the pointer has parted them. [form]
     * does not change, but [isMorphing] is true until the cells are home, so
     * [buildSteadyState] refuses until then, as it does mid-morph.
     *
     * Either way the trip stays in flight until the spring has brought the
     * cells home after the burst, so the frame on which it settles is the form
     * at rest with no jump. A [LumosConfig.reducedMotion] host draws through
     * [buildStill], which drops the morph and the burst with it; so do a
     * resolution change and setting [form] directly, which are cuts.
     *
     * @param strength Outward speed at the moment of firing, units per second,
     *  falling linearly to nothing over [durationSeconds]. A unit is the orb's
     *  radius; see [Force.Burst] for converting from pixels. Dots' burst is
     *  about 6.
     * @param durationSeconds How long the push lasts.
     * @throws IllegalArgumentException when [strength] is not finite or
     *  [durationSeconds] is not positive and finite.
     */
    fun burst(
        strength: Float,
        durationSeconds: Float = Force.Burst.DEFAULT_DURATION_SECONDS,
    ) {
        val burst = Force.Burst(strength, durationSeconds)
        val body = morph?.takeIf { it.isSettling } ?: startRestingTrip(max(MIN_BURST_TRIP_SECONDS, durationSeconds))
        body.forces.add(burst)
    }

    /**
     * Start a [Choreography.DIRECT] trip from where every cell is to where it
     * rests, so a force has a spring to act against: from rest, or on the body
     * standing under the pointer, which keeps its cells where they were pushed.
     */
    private fun startRestingTrip(durationSeconds: Float): FormMorph {
        val sample = currentSample()
        val order = permutation
        val body =
            morph?.takeIf { it.count == sample.count }
                ?: FormMorph(sample.count).also { it.snapTo(sample.positions, order) }
        body.retarget(
            targets = sample.positions,
            assignment = order,
            choreography = Choreography.DIRECT,
            durationSeconds = durationSeconds,
        )
        morphFramingFrom = framing(sample)
        morphMixFrom = null
        morph = body
        return body
    }

    /**
     * Give every target no origin was matched to a new cell, starting where the
     * nearest existing cell is now so it springs out of the body rather than
     * appearing in place.
     */
    private fun spawnLeftovers(
        nextPermutation: IntArray,
        assignment: IntArray,
        origins: List<Vector3>,
        targets: List<Vector3>,
        body: FormMorph,
        mixFromIndex: IntArray,
    ) {
        val taken = BooleanArray(targets.size)
        for (j in assignment) taken[j] = true
        var cell = assignment.size
        for (j in targets.indices) {
            if (taken[j]) continue
            nextPermutation[cell] = j
            val target = targets[j]
            var nearest = Vector3.ZERO
            var nearestDistance = Float.POSITIVE_INFINITY
            var parent = -1
            for (k in origins.indices) {
                val d = (origins[k] - target).lengthSquared()
                if (d < nearestDistance) {
                    nearestDistance = d
                    nearest = origins[k]
                    parent = k
                }
            }
            if (parent >= 0) mixFromIndex[cell] = mixFromIndex[parent]
            body.positions[3 * cell] = nearest.x
            body.positions[3 * cell + 1] = nearest.y
            body.positions[3 * cell + 2] = nearest.z
            cell++
        }
    }

    /** The current form sampled where the last build sampled it. */
    private fun currentSample(): FormSample = sampleForm(form, lastTimeSeconds, phases, gaze = gaze)

    /**
     * [form] at the lattice's budget, the [look]'s idiom and [timeSeconds]. A
     * [ViewForm] is handed the rotation of a frame at [phases], and a
     * [GazingForm] the pointer, [gaze]; every other form is sampled exactly as
     * it always was. A form that is both is sampled as a view form.
     *
     * The frame's yaw is a look's own when it owns one, mixed across a blend
     * when [live] (a steady state or a still wears one look), and the looks'
     * modulators are built from the sample. So the form is sampled with the
     * yaw the modulators already held say, the modulators are resolved over
     * that sample, and if the yaw they give differs — a modulator was just
     * built, on the frame a look or the sample changed — the form is sampled
     * once more with it. A view form rewrites its sample in place, so that
     * costs a pass and no allocation.
     */
    private fun sampleForm(
        form: LumosForm,
        timeSeconds: Float,
        phases: FramePhases,
        live: Boolean = true,
        gaze: Vector2? = null,
    ): FormSample {
        if (form is GazingForm && form !is ViewForm) return form.sample(lattice.count, look.look, timeSeconds, gaze)
        val viewForm = form as? ViewForm ?: return form.sample(lattice.count, look.look, timeSeconds)
        val outgoingTime = outgoingSeconds.toFloat()
        val weight = if (live) lookBlendProgress else 1f
        val guess = frameYaw(phases, modulator, outgoingModulator, outgoingTime, weight)
        val sample = viewForm.sample(lattice.count, look.look, timeSeconds, viewRotation(phases, guess))
        val outgoing = if (weight < 1f) outgoingModulatorFor(sample) else null
        val yaw = frameYaw(phases, modulatorFor(sample), outgoing, outgoingTime, weight)
        if (yaw == guess) return sample
        return viewForm.sample(lattice.count, look.look, timeSeconds, viewRotation(phases, yaw))
    }

    /**
     * [rotation] set to the angles a frame at [phases] turning by [yaw] is
     * projected with. Rates are left at zero: a form needs only the angles,
     * and the look pass sets the frame's own rates before it reads them.
     */
    private fun viewRotation(
        phases: FramePhases,
        yaw: Float,
    ): ViewRotation {
        rotation.set(phases.orbRotationX, yaw, 0f, 0f)
        return rotation
    }

    /**
     * The yaw a frame at [phases] turns by: [modulator]'s own when it owns
     * one, else the atmosphere's. Mid-blend, with [weight] below 1, the frame
     * turns between [outgoing]'s yaw at its own [outgoingTime] (or the
     * atmosphere's) and that one, the short way round.
     */
    private fun frameYaw(
        phases: FramePhases,
        modulator: LookModulator?,
        outgoing: LookModulator?,
        outgoingTime: Float,
        weight: Float,
    ): Float {
        val yaw = modulator?.yaw(phases.lookSeconds.toFloat()) ?: phases.orbRotationY
        if (weight >= 1f) return yaw
        val yawFrom = outgoing?.yaw(outgoingTime) ?: phases.orbRotationY
        return wrapTwoPi(yawFrom + shortArc(yaw - yawFrom) * weight)
    }

    /**
     * Produce a [VoxelFrame] from [snapshot], advancing phase accumulators
     * and rotations by [dt] seconds.
     *
     * [snapshot]'s [input][SceneSnapshot.input] reaches the frame three ways,
     * and nothing else: the atmosphere, the phases and the look clock never
     * see it.
     *
     * - **The light.** The frame carries it as [VoxelFrame.input], unless it is
     *   [LumosInput.NONE], for the surface to light the orb by.
     * - **The gaze.** A [GazingForm] is sampled looking toward its pointer.
     * - **The push.** With [LumosRenderConfig.interactive], the cells near the
     *   pointer's line of sight part around it ([Force.Repel]). The line is
     *   the pointer carried back into the body's space by the frame's own
     *   rotation and pulse and the form's scale, ignoring the atmosphere's
     *   squash, jitter and bump — under 5% in `y` on every preset — and it
     *   runs through the whole orb, so the cells behind are parted too. The
     *   force acts on the morph in flight, or on a body the builder stands at
     *   rest for it and keeps only while it has something to answer. Once the
     *   pointer leaves, the cells spring home, and when every one is home and
     *   no trip is in flight the body is dropped: frames are read straight
     *   from the form again, exactly as for an orb nobody touched. A body at
     *   rest is not a morph: [isMorphing] stays false, and [buildSteadyState]
     *   still renders the form at rest. [morphTo] starts its trip from where
     *   the pointer pushed the cells; a cut — setting [form], a resolution
     *   change — and [buildStill] drop the body.
     *
     * With no pointer, every frame is exactly the frame the builder produced
     * before input existed.
     *
     * @throws IllegalStateException when the atmosphere subsystem is disabled
     *  on the runtime that produced [snapshot]
     *  (`SceneConfiguration.enableAtmosphere = false`).
     */
    fun build(
        snapshot: SceneSnapshot,
        dt: Float,
    ): VoxelFrame = probe.measure(FramePhase.BUILD) { buildFrame(snapshot, dt) }

    /**
     * The frame this builder would produce in steady state: [atmosphere] with no
     * transition and no glyph, at [phases]. Nothing the builder owns moves — its
     * own accumulators, glyph, and lattice are left as they were; the [look]'s
     * modulator, if one is needed and not yet built, is built here and kept, as
     * [build] would have.
     *
     * With [FramePhases.advanced] this reconstructs frames the builder never
     * produced. A loop recorded straight after a transition fades its tail
     * toward the frames it *would* have shown before it started had its
     * atmosphere always been active: read [phases] at the loop's first frame,
     * rewind by `k · dt`, and build here. In steady state the result is the frame
     * [build] returned at those phases, up to float rounding; `Oscilloscope` in
     * `:phosphor-trace` is the caller this exists for.
     *
     * Cells are the current [form]'s at rest, in the order the live stream
     * emits them, sampled at the frame's time with the lattice's budget; a
     * lattice form must match [atmosphere] in resolution, since the lattice is
     * the atmosphere's. The form must be settled: a frame mid-[morphTo] is not
     * a steady state, and this is where a loop's lead-in comes from. The
     * [look]'s modulator is the live one too, built here if the live stream has
     * not needed it yet. Not measured by the probe, which times the live render
     * loop.
     *
     * @param tick [VoxelFrame.tick] of the result.
     * @param timestampEpochMillis [VoxelFrame.timestampEpochMillis] of the result.
     * @throws IllegalArgumentException when [form] is a [Forms.Lattice] whose
     *  resolution differs from [atmosphere]'s.
     * @throws IllegalStateException when a morph is in flight; see [isMorphing].
     */
    fun buildSteadyState(
        atmosphere: AtmosphereState,
        phases: FramePhases,
        tick: Long,
        timestampEpochMillis: Long,
    ): VoxelFrame {
        val current = form
        if (current is Forms.Lattice) {
            require(atmosphere.resolution == current.resolution) {
                "buildSteadyState renders on the builder's lattice: atmosphere resolution ${atmosphere.resolution} " +
                    "differs from the current ${current.resolution}"
            }
        }
        check(!isMorphing) { "buildSteadyState has no steady state to render while a morph is in flight" }
        val sample = sampleForm(current, timestampEpochMillis / 1_000f, phases, live = false)
        return frameAt(
            atmosphere = atmosphere,
            transition = null,
            phases = phases,
            glyphLifecycle = null,
            sample = sample,
            permutation = permutation,
            morphPositions = null,
            framing = framing(sample),
            modulator = modulatorFor(sample),
            outgoing = null,
            outgoingTime = 0f,
            weight = 1f,
            presence = 1f,
            input = null,
            tick = tick,
            timestampEpochMillis = timestampEpochMillis,
        )
    }

    /**
     * The frame reduced motion draws: a pose, held still.
     *
     * [snapshot]'s atmosphere — or, while a transition is in flight, the one it
     * is heading to — rendered at the current [phases], which this does not
     * advance: no breath, no pattern drift, no spin. A queued glyph still ages
     * by [dt], so it leaves when its window closes, but it is carved at full
     * strength the whole time instead of fading in and out; its
     * [VoxelGlyphState.progress] reads 0.5, inside the envelope's hold. The
     * [form] follows the atmosphere's resolution, as in [build].
     *
     * With no transition and no glyph this is [buildSteadyState] at [phases].
     * A still whose inputs have not changed — the same atmosphere, phases,
     * glyph, and form sample — is returned as the same instance, so a driver
     * can tell nothing moved without comparing cells; its tick and timestamp
     * stay those of the call that built it. Only a still that is actually
     * built is measured by the probe.
     *
     * [snapshot]'s input is not read: a still neither parts around the pointer
     * nor gazes at it, drops any body standing under it, and carries no
     * [VoxelFrame.input], so it stays the same instance while the pointer
     * moves. A surface that lights the orb by the pointer under reduced
     * motion reads the pointer itself.
     *
     * @throws IllegalStateException when the atmosphere subsystem is disabled
     *  on the runtime that produced [snapshot].
     */
    fun buildStill(
        snapshot: SceneSnapshot,
        dt: Float,
    ): VoxelFrame {
        val atmosphere =
            snapshot.atmosphereTransition?.to
                ?: checkNotNull(snapshot.atmosphere) {
                    "VoxelFrameBuilder requires SceneConfiguration.enableAtmosphere = true"
                }

        followResolution(atmosphere)
        // Reduced motion snaps: the body is already where the morph was heading, the look is the
        // one the blend was heading to, and a spawning agent is fully present. Nothing is pushed
        // and nothing gazes: those are motion.
        morph = null
        readSignal(snapshot)
        landBlend()
        spawnElapsed = SPAWN_SECONDS
        presence = 1f

        val glyph = advanceGlyph(dt)?.glyph
        lastTimeSeconds = snapshot.elapsedTimeSeconds
        gaze = null
        val sample = sampleForm(form, snapshot.elapsedTimeSeconds, phases)
        stillFrame?.let { held ->
            if (atmosphere === stillAtmosphere &&
                phases == stillPhases &&
                glyph == stillGlyph &&
                sample === stillSample &&
                permutation === stillPermutation &&
                worn == stillLook &&
                held.resolution == lattice.resolution
            ) {
                return held
            }
        }

        val frame =
            probe.measure(FramePhase.BUILD) {
                frameAt(
                    atmosphere = atmosphere,
                    transition = null,
                    phases = phases,
                    glyphLifecycle =
                        glyph?.let {
                            GlyphLifecycle(it, totalDurationSeconds = 1f, ageSeconds = STILL_GLYPH_PROGRESS)
                        },
                    sample = sample,
                    permutation = permutation,
                    morphPositions = null,
                    framing = framing(sample),
                    modulator = modulatorFor(sample),
                    outgoing = null,
                    outgoingTime = 0f,
                    weight = 1f,
                    presence = 1f,
                    input = null,
                    tick = snapshot.frameIndex,
                    timestampEpochMillis = millisFromElapsed(snapshot.elapsedTimeSeconds),
                )
            }
        stillFrame = frame
        stillAtmosphere = atmosphere
        stillPhases = phases
        stillGlyph = glyph
        stillSample = sample
        stillPermutation = permutation
        stillLook = worn
        return frame
    }

    private fun buildFrame(
        snapshot: SceneSnapshot,
        dt: Float,
    ): VoxelFrame {
        val atmosphere =
            checkNotNull(snapshot.atmosphere) {
                "VoxelFrameBuilder requires SceneConfiguration.enableAtmosphere = true"
            }

        followResolution(atmosphere)
        readSignal(snapshot)

        val glyphLifecycle = advanceGlyph(dt)
        phases = phases.advanced(atmosphere, dt)
        stepBlend(dt)
        stepSpawn(dt)

        val input = snapshot.input
        lastTimeSeconds = snapshot.elapsedTimeSeconds
        gaze = input.pointer
        var sample = sampleForm(form, snapshot.elapsedTimeSeconds, phases, gaze = gaze)
        if (outgoingLook != null) {
            val began = blendSample
            if (began == null) {
                blendSample = sample
            } else if (began !== sample) {
                // The sample moved under the blend: the outgoing tables are the old count's. A cut.
                landBlend()
                // A view form was handed the blend's yaw; the cut turns the frame by the incoming look's.
                if (form is ViewForm) sample = sampleForm(form, snapshot.elapsedTimeSeconds, phases)
            }
        }
        followCount(sample)
        val modulator = modulatorFor(sample)
        val outgoing = outgoingModulatorFor(sample)
        val outgoingTime = outgoingSeconds.toFloat()
        val weight = lookBlendProgress
        val pointer = if (config.interactive) input.pointer else null
        val push = pointer != null && aimRepel(pointer, atmosphere, modulator, outgoing, outgoingTime, weight)
        val body = stepMorph(sample, dt, push)
        return frameAt(
            atmosphere = atmosphere,
            transition = snapshot.atmosphereTransition,
            phases = phases,
            glyphLifecycle = glyphLifecycle,
            sample = sample,
            permutation = permutation,
            morphPositions = body?.positions,
            framing = if (body == null) framing(sample) else effectiveFraming(sample),
            mixFrom = morphMixFrom?.takeIf { it.body === body && it.body.isSettling },
            modulator = modulator,
            outgoing = outgoing,
            outgoingTime = outgoingTime,
            weight = weight,
            presence = presence,
            input = input.takeUnless { it == LumosInput.NONE },
            tick = snapshot.frameIndex,
            timestampEpochMillis = millisFromElapsed(snapshot.elapsedTimeSeconds),
        )
    }

    /**
     * Advance the body by [dt] toward [sample]'s positions and return it, or
     * null when there is none or it has nothing left to do — no trip in flight,
     * no force acting, every cell home — from which frame on the sample is
     * rendered directly, so a form at rest costs nothing.
     *
     * When [push], the aimed [repel] acts on the body: the morph in flight, or
     * else a body stood at rest for it ([standBody]). Otherwise the repel comes
     * off, and a body it pushed is stepped until its cells are home.
     */
    private fun stepMorph(
        sample: FormSample,
        dt: Float,
        push: Boolean,
    ): FormMorph? {
        var body = morph
        if (push) {
            if (body == null) body = standBody(sample)
            if (repel !in body.forces) body.forces.add(repel)
        } else {
            if (body == null) return null
            body.forces.remove(repel)
        }
        body.step(dt, sample.positions)
        if (!body.isSettling && body.forces.isEmpty() && !body.isDisturbed) {
            morph = null
            return null
        }
        return body
    }

    /**
     * Stand a body at rest on [sample]'s positions, read through the
     * permutation, for the pointer to push: the last one stood when its count
     * still fits, so a pointer that comes and goes allocates nothing.
     */
    private fun standBody(sample: FormSample): FormMorph {
        val body = restingBody?.takeIf { it.count == sample.count } ?: FormMorph(sample.count)
        body.snapTo(sample.positions, permutation)
        restingBody = body
        morphMixFrom = null
        morph = body
        return body
    }

    /**
     * Aim [repel] along [pointer]'s line of sight, carried back into the body's
     * own space, and say whether it can push.
     *
     * A cell is drawn at `rest · pulse · formScale`, rotated by the frame's own
     * angles — the ones the ambient carries to the surfaces, a look's yaw
     * included — so the pointer is divided by this frame's pulse and form scale
     * and un-rotated by those angles: that is the line's point. Its direction
     * is the view axis carried back the same way, `(−sin y, sin x · cos y,
     * cos x · cos y)`. The atmosphere's squash, jitter and bump are ignored.
     * Writes into the repel's floats and allocates nothing.
     *
     * @return False, with the repel left as it was, when the line is not finite:
     *  a pointer so far past the orb that carrying it back overflows, or a pulse
     *  of zero.
     */
    @Suppress("LongParameterList")
    private fun aimRepel(
        pointer: Vector2,
        atmosphere: AtmosphereState,
        modulator: LookModulator?,
        outgoing: LookModulator?,
        outgoingTime: Float,
        weight: Float,
    ): Boolean {
        aimRotation.set(phases.orbRotationX, frameYaw(phases, modulator, outgoing, outgoingTime, weight), 0f, 0f)
        val scale = drawScale(atmosphere)
        val ox = pointer.x / scale
        val oy = pointer.y / scale
        val x = aimRotation.unrotateX(ox, oy)
        val y = aimRotation.unrotateY(ox, oy)
        val z = aimRotation.unrotateZ(ox, oy)
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        repel.aim(
            x = x,
            y = y,
            z = z,
            dx = -aimRotation.sinY,
            dy = aimRotation.sinX * aimRotation.cosY,
            dz = aimRotation.cosX * aimRotation.cosY,
        )
        return true
    }

    /**
     * How much larger than its rest position a cell is drawn this frame, before
     * the squash: the pulse times the form's scale, as [frameAt] draws them.
     * The framing is [effectiveFraming]'s, worked without building a
     * [Framing] — the form's own, or mid-morph the blend from where the morph
     * left — and a rigid form holds the pulse off by its rigidity.
     */
    private fun drawScale(atmosphere: AtmosphereState): Float {
        val framed = form as? FramedForm
        var formScale = framed?.scale ?: 1f
        var rigidity = if (framed?.rigid == true) 1f else 0f
        val body = morph?.takeIf { it.isSettling }
        if (body != null) {
            val from = morphFramingFrom
            val t = body.progress
            formScale = from.formScale + (formScale - from.formScale) * t
            rigidity = from.rigidity + (rigidity - from.rigidity) * t
        }
        val pulseAmplitude =
            if (rigidity == 0f) atmosphere.pulseAmplitude else atmosphere.pulseAmplitude * (1f - rigidity)
        return (1f + sin(phases.pulsePhase) * pulseAmplitude) * formScale
    }

    /** Drop the body when the form's count moved under it: a cut, as before morphs existed. */
    private fun followCount(sample: FormSample) {
        val body = morph ?: return
        if (body.count == sample.count) return
        morph = null
        permutation = null
    }

    /** The framing to draw: the form's own, or mid-morph the blend from where the morph left. */
    private fun effectiveFraming(sample: FormSample): Framing {
        val body = morph?.takeIf { it.isSettling } ?: return framing(sample)
        val to = framing(sample)
        val from = morphFramingFrom
        val t = body.progress
        return Framing(
            formScale = from.formScale + (to.formScale - from.formScale) * t,
            cellScale = from.cellScale + (to.cellScale - from.cellScale) * t,
            rigidity = from.rigidity + (to.rigidity - from.rigidity) * t,
        )
    }

    /**
     * How the current [form] is framed, for [sample]: a [FramedForm]'s scale and
     * its cell size relative to the lattice's, `cellRadius · 2 · resolution`,
     * so a renderer sizing cells by the atmosphere's resolution draws them the
     * size the form asked for, and whether it holds the skin off. The lattice,
     * and any form that is not framed, is drawn as is.
     */
    private fun framing(sample: FormSample): Framing {
        val framed = form as? FramedForm ?: return Framing.NONE
        val cellScale = framed.cellRadius(sample.count) * 2f * max(lattice.resolution, 1)
        val rigidity = if (framed.rigid) 1f else 0f
        return if (framed.scale == 1f && cellScale == 1f && rigidity == 0f) {
            Framing.NONE
        } else {
            Framing(framed.scale, cellScale, rigidity)
        }
    }

    /**
     * The [look]'s modulator over [sample]: the one already built when neither has
     * changed, a new one otherwise. Null when [LumosLook.BASE] has nothing to do.
     */
    private fun modulatorFor(sample: FormSample): LookModulator? {
        if (look.look == LumosLook.BASE && !config.rimFade) return null
        val current = modulator
        if (current != null && modulatorSample === sample) return current
        return Looks.forLook(look, sample).also {
            modulator = it
            modulatorSample = sample
        }
    }

    /**
     * The outgoing look's modulator over [sample], while a blend is in flight:
     * the one it left with when the sample is still the one it was built over,
     * a new one when it was never built. Null when there is no blend, or the
     * outgoing look is [LumosLook.BASE] with nothing to do: the identity.
     */
    private fun outgoingModulatorFor(sample: FormSample): LookModulator? {
        val outgoing = outgoingLook ?: return null
        if (outgoing.look == LumosLook.BASE && !config.rimFade) return null
        val current = outgoingModulator
        if (current != null && outgoingSample === sample) return current
        return Looks.forLook(outgoing, sample).also {
            outgoingModulator = it
            outgoingSample = sample
        }
    }

    /**
     * How fast [modulator]'s own yaw turns at [t], radians per second: a central
     * difference over [YAW_RATE_STEP] either side, taken the short way round.
     */
    private fun yawRate(
        modulator: LookModulator,
        t: Float,
    ): Float {
        val ahead = modulator.yaw(t + YAW_RATE_STEP) ?: return 0f
        val behind = modulator.yaw(t - YAW_RATE_STEP) ?: return 0f
        var arc = ahead - behind
        if (arc > PI_F) arc -= FramePhases.TWO_PI
        if (arc < -PI_F) arc += FramePhases.TWO_PI
        return arc / (2f * YAW_RATE_STEP)
    }

    /**
     * The pure core of a build: every input named, nothing read from the
     * builder but its lattice resolution, [config] and the three scratch
     * objects the look pass reuses. [build] advances state and calls this;
     * [buildSteadyState] calls it as it is. One cell per entry of [sample]:
     * cell `i` reads sample entry `permutation[i]`, or `i` when [permutation]
     * is null, and sits at [morphPositions]' entry `i` when a morph is in
     * flight, else at the sample's rest position. Positioned at
     * [Framing.formScale] times the form's and sized at [Framing.cellScale]
     * times the lattice's cell. [Framing.rigidity] holds the atmosphere's
     * jitter, bump, pulse and squash off by that share; at `0` they are read
     * exactly as they always were.
     *
     * With a [modulator], each cell is rotated into view space by the frame's
     * own angles — the rotation the surfaces apply again at projection — from
     * where it rests this frame, mid-morph or not, and modulated in the form's
     * own unit space, before the atmosphere's jitter, bump, pulse and squash
     * and before the framing scales it. A modulator that owns the yaw turns the
     * frame by its own in place of the atmosphere's, and the ambient carries
     * that yaw to the surfaces. The modulator is keyed by sample index,
     * so a cell keeps its place in the walk's table across a morph. Without
     * one, the loop is exactly what it was before looks existed.
     *
     * With [weight] below 1 a blend is in flight ([lookTo]): [outgoing] — null
     * for the identity — runs on the same view at its own [outgoingTime], and
     * every cell's modulation is `outgoing · (1 − weight) + incoming · weight`;
     * the yaw and its rate mix the same way, the yaw along the short arc.
     * [presence] multiplies every cell's alpha and the ambient glow; at 1 it
     * leaves both exactly as they were. [input] rides on the frame as
     * [VoxelFrame.input], and is read by nothing here.
     */
    @Suppress("LongParameterList")
    private fun frameAt(
        atmosphere: AtmosphereState,
        transition: AtmosphereTransition?,
        phases: FramePhases,
        glyphLifecycle: GlyphLifecycle?,
        sample: FormSample,
        permutation: IntArray?,
        morphPositions: FloatArray?,
        framing: Framing,
        mixFrom: MixFrom? = null,
        modulator: LookModulator?,
        outgoing: LookModulator?,
        outgoingTime: Float,
        weight: Float,
        presence: Float,
        input: LumosInput?,
        tick: Long,
        timestampEpochMillis: Long,
    ): VoxelFrame {
        val blending = weight < 1f
        val formScale = framing.formScale
        val cellScale = framing.cellScale
        // The skin: what of the atmosphere's jitter, bump, pulse and squash a rigid form lets through.
        val rigidity = framing.rigidity
        val skin = 1f - rigidity
        val noise = if (rigidity == 0f) atmosphere.noise else atmosphere.noise * skin
        val surfaceBump = if (rigidity == 0f) atmosphere.surfaceBump else atmosphere.surfaceBump * skin
        val pulseAmplitude = if (rigidity == 0f) atmosphere.pulseAmplitude else atmosphere.pulseAmplitude * skin
        val ySquash = if (rigidity == 0f) atmosphere.ySquash else atmosphere.ySquash * skin + rigidity
        val pulse = 1f + sin(phases.pulsePhase) * pulseAmplitude
        // voxelGap is the fraction of a lattice cell left empty between cubes, so the
        // cube itself fills what remains. Clamped because an overshoot easing can carry
        // an interpolated gap briefly past its endpoints, and a negative fill would
        // invert the cube.
        val voxelFill = (1f - atmosphere.voxelGap).coerceIn(0f, 1f)
        // The host's override is a format adjustment, not skin: a rigid form keeps it.
        val effectiveYSquash = ySquash * (config.globalYSquashOverride ?: 1f)
        val glyphColor =
            glyphLifecycle
                ?.takeIf { config.enableGlyphCarving }
                ?.let { NeutralColor.fromHsl(it.glyph.hue, it.glyph.saturation, it.glyph.lightness) }
        val glyphShape = glyphLifecycle?.takeIf { config.enableGlyphCarving }?.let { GlyphShape.forGlyph(it.glyph) }
        val glyphVisibility = glyphLifecycle?.takeIf { config.enableGlyphCarving }?.visibility ?: 0f
        val lookTime = phases.lookSeconds.toFloat()
        // A look that owns the yaw replaces the atmosphere's wherever this frame reads it.
        val lookYaw = modulator?.yaw(lookTime)
        val yaw = frameYaw(phases, modulator, outgoing, outgoingTime, weight)
        if (modulator != null || blending) {
            var rateY = if (lookYaw != null) yawRate(modulator, lookTime) else atmosphere.rotationY
            if (blending) {
                // Mid-blend the spin carries cells at the rate between the two looks', as the yaw turns
                // between theirs (frameYaw); the outgoing look reads its own clock.
                val outgoingYaw = outgoing?.yaw(outgoingTime)
                val rateFrom = if (outgoingYaw != null) yawRate(outgoing, outgoingTime) else atmosphere.rotationY
                rateY = rateFrom * (1f - weight) + rateY * weight
            }
            rotation.set(phases.orbRotationX, yaw, atmosphere.rotationX, rateY)
            modulator?.beginFrame(lookTime, rotation)
            if (blending) outgoing?.beginFrame(outgoingTime, rotation)
        }
        val glyphRotation = Vector3(phases.orbRotationX, yaw, 0f)

        val positions = sample.positions
        val directions = sample.unitDirections
        val jitters = sample.jitter
        val thetas = sample.theta
        val phis = sample.phi
        val cells = ArrayList<VoxelCell>(sample.count)
        for (cell in 0 until sample.count) {
            val index = permutation?.get(cell) ?: cell
            var mix = computeMix(atmosphere, transition, sample, index, phases.patternPhase)
            if (mixFrom != null) {
                // Mid-morph a cell's pattern turns from the form it left to the one it is heading for,
                // with the spring, so its colour travels with it rather than cutting on the first frame.
                val fromIndex = mixFrom.indices[cell]
                if (fromIndex >= 0) {
                    val left = computeMix(atmosphere, transition, mixFrom.sample, fromIndex, phases.patternPhase)
                    mix = left + (mix - left) * mixFrom.body.progress
                }
            }
            val boundaryShrink = computeBoundaryShrink(atmosphere.bipolarStrength, mix)
            val baseColor = computeVoxelColor(atmosphere, transition, mix)
            val direction = directions[index]
            val rotatedDirection = glyphShape?.let { direction.rotatedBy(glyphRotation) }
            // Facing the camera is the rotated normal's z past the threshold, as `Voxel.facingCamera` tests it.
            val isGlyphMember =
                glyphShape != null &&
                    rotatedDirection != null &&
                    rotatedDirection.z > GLYPH_FACING_THRESHOLD &&
                    glyphShape.contains(rotatedDirection.x, rotatedDirection.y)
            val color =
                if (isGlyphMember && glyphColor != null) {
                    NeutralColor.lerp(baseColor, glyphColor, glyphVisibility)
                } else {
                    baseColor
                }
            val glyphShrink =
                if (glyphLifecycle != null && config.enableGlyphCarving && !isGlyphMember) {
                    1f - GLYPH_BACKGROUND_SHRINK * glyphVisibility
                } else {
                    1f
                }
            // Where the cell rests this frame: on the way to the form mid-morph, else at the form.
            var restX: Float
            var restY: Float
            var restZ: Float
            if (morphPositions != null) {
                restX = morphPositions[3 * cell]
                restY = morphPositions[3 * cell + 1]
                restZ = morphPositions[3 * cell + 2]
            } else {
                val position = positions[index]
                restX = position.x
                restY = position.y
                restZ = position.z
            }
            var lookRadius = 1f
            var lookAlpha = 1f
            var lit = 0f
            if (modulator != null || blending) {
                modulation.reset()
                rotation.place(view, restX, restY, restZ, direction.x, direction.y, direction.z)
                modulator?.modulate(index, view, lookTime, modulation)
                if (blending) {
                    outgoingModulation.reset()
                    outgoing?.modulate(index, view, outgoingTime, outgoingModulation)
                    modulation.mixFrom(outgoingModulation, weight)
                }
                val extent = modulation.extent
                restX *= extent
                restY *= extent
                restZ *= extent
                val ox = modulation.ox
                val oy = modulation.oy
                if (ox != 0f || oy != 0f) {
                    restX += rotation.unrotateX(ox, oy)
                    restY += rotation.unrotateY(ox, oy)
                    restZ += rotation.unrotateZ(ox, oy)
                }
                lookRadius = modulation.radius
                lookAlpha = modulation.alpha.coerceIn(0f, 1f)
                lit = modulation.lit.coerceIn(0f, 1f)
            }
            val scale = voxelFill * pulse * boundaryShrink * glyphShrink * lookRadius

            // Omission reads the atmosphere's own scale: a form's cell size changes how big a
            // cell is drawn, not whether it is worth drawing.
            if (config.omitBelowScale > 0f && scale < config.omitBelowScale) continue

            val jitter = jitters[index]
            val px = restX + jitter.x * noise * JITTER_GAIN
            val py = restY + jitter.y * noise * JITTER_GAIN
            val pz = restZ + jitter.z * noise * JITTER_GAIN
            val bumpAmount =
                surfaceBump *
                    sin(thetas[index] * BUMP_THETA + phis[index] * BUMP_PHI + phases.patternPhase * BUMP_PHASE)

            val ux = direction.x
            val uy = direction.y
            val uz = direction.z

            val finalX = (px + ux * bumpAmount) * pulse * formScale
            val finalY = (py + uy * bumpAmount) * effectiveYSquash * pulse * formScale
            val finalZ = (pz + uz * bumpAmount) * pulse * formScale

            val litColor = if (lit > 0f) NeutralColor.lerp(color, NeutralColor.WHITE, lit * LIT_MIX) else color
            // At full presence `x * 1f` is `x`, so a cell with no look alpha still carries none.
            val cellAlpha = lookAlpha * presence
            cells +=
                VoxelCell(
                    x = finalX,
                    y = finalY,
                    z = finalZ,
                    scale = scale * cellScale,
                    red = litColor.red,
                    green = litColor.green,
                    blue = litColor.blue,
                    alpha = if (cellAlpha != 1f) cellAlpha else null,
                )
        }

        val ambient = computeAmbient(atmosphere, phases.orbRotationX, yaw, presence)

        return VoxelFrame(
            tick = tick,
            timestampEpochMillis = timestampEpochMillis,
            resolution = lattice.resolution,
            cells = cells,
            ambient = ambient,
            glyph =
                glyphLifecycle
                    ?.takeIf { config.enableGlyphCarving }
                    ?.let { lifecycle ->
                        checkNotNull(glyphColor)
                        VoxelGlyphState(
                            glyphName = lifecycle.glyph.name,
                            progress = lifecycle.progress,
                            red = glyphColor.red,
                            green = glyphColor.green,
                            blue = glyphColor.blue,
                        )
                    },
            input = input,
        )
    }

    /**
     * Rebuild the lattice when [atmosphere]'s resolution moved. A lattice form
     * is the atmosphere's and follows it; any other form keeps its shape.
     */
    private fun followResolution(atmosphere: AtmosphereState) {
        if (atmosphere.resolution != lattice.resolution) {
            lattice = Forms.Lattice(atmosphere.resolution)
            if (form is Forms.Lattice) {
                form = lattice
            } else {
                // The budget moved under a form that follows it: the count may change, so the
                // identity built on the old count cannot hold. A cut, as it was before morphs.
                permutation = null
                morph = null
            }
        }
    }

    private fun advanceGlyph(dt: Float): GlyphLifecycle? {
        val next = activeGlyph?.advance(dt) ?: return null
        activeGlyph = next.takeUnless { it.isComplete }
        return activeGlyph
    }

    private fun computeMix(
        atmosphere: AtmosphereState,
        transition: AtmosphereTransition?,
        sample: FormSample,
        index: Int,
        patternPhase: Float,
    ): Float {
        val toMix = evaluatePattern(atmosphere.pattern, sample, index, patternPhase)
        if (transition == null || transition.from.pattern == transition.to.pattern) {
            return toMix
        }
        val fromMix = evaluatePattern(transition.from.pattern, sample, index, patternPhase)
        return fromMix * (1f - transition.progressLinear) + toMix * transition.progressLinear
    }

    private fun computeBoundaryShrink(
        bipolarStrength: Float,
        mix: Float,
    ): Float {
        if (bipolarStrength <= 0f) return 1f
        val band = BAND_GAIN * bipolarStrength
        if (band <= 0f) return 1f
        val distance = abs(mix - 0.5f)
        if (distance >= band) return 1f
        return smoothstep(distance / band)
    }

    private fun computeVoxelColor(
        atmosphere: AtmosphereState,
        transition: AtmosphereTransition?,
        mix: Float,
    ): NeutralColor {
        val crossfade =
            transition?.takeIf { it.from.bipolarStrength != it.to.bipolarStrength }
                ?: return singleStateColor(atmosphere, mix)

        val fromColor = singleStateColor(crossfade.from, mix)
        val toColor = singleStateColor(crossfade.to, mix)
        return NeutralColor.lerpOklab(fromColor, toColor, crossfade.progressLinear)
    }

    private fun singleStateColor(
        state: AtmosphereState,
        mix: Float,
    ): NeutralColor {
        if (state.bipolarStrength > 0f) {
            val band = BAND_GAIN * state.bipolarStrength
            return when {
                mix < 0.5f - band ->
                    NeutralColor.fromHsl(state.primaryHue, state.saturation, state.lightness)
                mix > 0.5f + band ->
                    NeutralColor.fromHsl(state.secondaryHue, state.saturation, state.lightness)
                mix <= 0.5f ->
                    NeutralColor.fromHsl(state.primaryHue, state.saturation, state.lightness)
                else ->
                    NeutralColor.fromHsl(state.secondaryHue, state.saturation, state.lightness)
            }
        }
        val hue = lerpHueShortest(state.primaryHue, state.secondaryHue, mix)
        return NeutralColor.fromHsl(hue, state.saturation, state.lightness)
    }

    private fun computeAmbient(
        atmosphere: AtmosphereState,
        orbRotationX: Float,
        orbRotationY: Float,
        presence: Float,
    ): VoxelAmbient {
        val primary = NeutralColor.fromHsl(atmosphere.primaryHue, atmosphere.saturation, atmosphere.lightness)
        val secondary = NeutralColor.fromHsl(atmosphere.secondaryHue, atmosphere.saturation, atmosphere.lightness)
        val glow = NeutralColor.lerpOklab(primary, secondary, 0.5f)
        return VoxelAmbient(
            glowRed = glow.red,
            glowGreen = glow.green,
            glowBlue = glow.blue,
            glowIntensity = atmosphere.glow * presence,
            orbRotationX = orbRotationX,
            orbRotationY = orbRotationY,
            orbRotationZ = 0f,
        )
    }

    /**
     * Where a morph's colours start: the sample the form being left was drawn from, and the index
     * each of [body]'s cells read in it, or -1. Applies only while [body] is the morph in flight.
     */
    private class MixFrom(
        val body: FormMorph,
        val sample: FormSample,
        val indices: IntArray,
    )

    /**
     * A [FramedForm]'s framing resolved against the lattice: [formScale]
     * multiplies every position, [cellScale] every cell's scale, and
     * [rigidity], `0..1`, is how much of the atmosphere's skin is held off:
     * `1` for a [FramedForm.rigid] form at rest. [NONE] is the lattice's own,
     * and leaves every frame as it was before forms were framed.
     */

    private class Framing(val formScale: Float, val cellScale: Float, val rigidity: Float = 0f) {
        companion object {
            val NONE = Framing(1f, 1f)
        }
    }

    companion object {
        private const val JITTER_GAIN: Float = 2.4f
        private const val BUMP_THETA: Float = 3.0f
        private const val BUMP_PHI: Float = 2.5f
        private const val BUMP_PHASE: Float = 1.4f
        private const val BAND_GAIN: Float = 0.4f
        private const val GLYPH_FACING_THRESHOLD: Float = 0.15f
        private const val GLYPH_BACKGROUND_SHRINK: Float = 0.30f

        // Half the span the look-owned yaw's rate is measured over: a frame at 30 fps.
        private const val YAW_RATE_STEP: Float = 1f / 60f
        private const val PI_F: Float = FramePhases.TWO_PI / 2f

        /** How long [morphTo] takes by default: Dots' 2.4 s. */
        const val DEFAULT_MORPH_SECONDS: Float = 2.4f

        // The shortest trip a [burst] fired from rest starts, so the spring has something to work with.
        private const val MIN_BURST_TRIP_SECONDS: Float = 0.3f

        /** How long [lookTo] takes by default. */
        const val LOOK_BLEND_SECONDS: Float = 0.6f

        // The weight on the incoming look past which an alignLook would show, and is skipped: one
        // frame of the default blend weighs under 1 % at 30 fps and above, and 5 % at about 12 fps.
        private const val LOOK_ALIGN_WEIGHT: Float = 0.05f

        /**
         * How long the spawn fade takes: `AgentLayer`'s own ramp at its default
         * `spawnSpeed` of 1, so the orb lands as the agent turns idle.
         */
        const val SPAWN_SECONDS: Float = 1f

        /** [angle] wrapped into `0..2π`, as the atmosphere's yaw is. */
        private fun wrapTwoPi(angle: Float): Float {
            var wrapped = angle % FramePhases.TWO_PI
            if (wrapped < 0f) wrapped += FramePhases.TWO_PI
            return if (wrapped >= FramePhases.TWO_PI) 0f else wrapped
        }

        /** [arc] taken the short way round: in `-π..π`. */
        private fun shortArc(arc: Float): Float {
            var short = arc % FramePhases.TWO_PI
            if (short > PI_F) short -= FramePhases.TWO_PI
            if (short < -PI_F) short += FramePhases.TWO_PI
            return short
        }

        /** How far a fully lit cell's colour moves toward white; see [CellModulation.lit]. */
        internal const val LIT_MIX: Float = 0.5f

        // Where a held glyph sits in its envelope: the middle of the hold, at full strength.
        private const val STILL_GLYPH_PROGRESS: Float = 0.5f

        /** The pattern mix in `0..1` for cell [index] of [sample] at [patternPhase]. */
        internal fun evaluatePattern(
            pattern: AtmospherePattern,
            sample: FormSample,
            index: Int,
            patternPhase: Float,
        ): Float =
            when (pattern) {
                AtmospherePattern.LONGITUDE ->
                    (sin(sample.theta[index] * 2f + patternPhase * 2f) + 1f) * 0.5f
                AtmospherePattern.LATITUDE ->
                    (sin(sample.phi[index] * 2.5f + patternPhase * 2f) + 1f) * 0.5f
                AtmospherePattern.SPIRAL ->
                    (sin(sample.theta[index] * 2f + sample.phi[index] * 3f + patternPhase * 2.5f) + 1f) * 0.5f
                AtmospherePattern.SCAN ->
                    (sin(sample.positions[index].y * 0.85f - patternPhase * 3f) + 1f) * 0.5f
                AtmospherePattern.PLASMA -> {
                    val position = sample.positions[index]
                    (
                        sin(position.x * 0.55f + patternPhase * 1.7f) +
                            sin(position.y * 0.50f + patternPhase * 1.2f) +
                            sin(position.z * 0.65f + patternPhase * 2.0f) + 3f
                    ) / 6f
                }
                AtmospherePattern.PULSE ->
                    (sin(sample.distance[index] * 0.85f - patternPhase * 3f) + 1f) * 0.5f
                AtmospherePattern.SOLID -> 0.5f
            }

        internal fun smoothstep(t: Float): Float {
            val clamped = t.coerceIn(0f, 1f)
            return clamped * clamped * (3f - 2f * clamped)
        }

        internal fun lerpHueShortest(
            startDegrees: Float,
            endDegrees: Float,
            t: Float,
        ): Float {
            val rawDiff = endDegrees - startDegrees
            val shortest =
                when {
                    rawDiff > 180f -> rawDiff - 360f
                    rawDiff < -180f -> rawDiff + 360f
                    else -> rawDiff
                }
            var hue = startDegrees + shortest * t
            while (hue < 0f) hue += 360f
            while (hue >= 360f) hue -= 360f
            return hue
        }

        private fun millisFromElapsed(elapsedSeconds: Float): Long = (elapsedSeconds * 1_000.0).toLong()
    }
}
