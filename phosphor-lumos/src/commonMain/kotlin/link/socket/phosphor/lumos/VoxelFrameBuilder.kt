package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.field.Choreography
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
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AtmospherePattern
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.AtmosphereTransition
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
     * [LumosForm.sample]. Setting it takes effect on the next [build].
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
     */
    var look: Look = Look(LumosLook.BASE)
        set(value) {
            if (value != field) modulator = null
            field = value
        }

    private var modulator: LookModulator? = null
    private var modulatorSample: FormSample? = null

    // Reused by every cell of every frame; a modulator reads them and keeps nothing.
    private val rotation = ViewRotation()
    private val view = ViewPoint()
    private val modulation = CellModulation()

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

    // The body in flight, or null when the form is at rest and frames read the sample directly.
    private var morph: FormMorph? = null

    // The framing the morph left from, blended toward the new form's by the morph's progress.
    private var morphFramingFrom: Framing = Framing.NONE

    // The last frame time the form was sampled at, so morphTo samples the form where build did.
    private var lastTimeSeconds: Float = 0f

    private var activeGlyph: GlyphLifecycle? = null

    // The last still built and what it was built from; see buildStill.
    private var stillFrame: VoxelFrame? = null
    private var stillAtmosphere: AtmosphereState? = null
    private var stillPhases: FramePhases? = null
    private var stillGlyph: LumosGlyph? = null
    private var stillSample: FormSample? = null
    private var stillPermutation: IntArray? = null

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

    /** Whether a [morphTo] is in flight: cells are still springing to the new form's positions. */
    val isMorphing: Boolean get() = morph?.isSettling == true

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
     * cell size blend across with the spring's own progress. Once settled, the
     * builder renders the new form exactly as it renders any form at rest, with
     * no per-frame cost and with its cells in the matched order — which is what
     * keeps a trace's per-index delta coding and a crossfade's cell pairing
     * working across the change.
     *
     * Equal counts are the common case. With a smaller [count], the cells with
     * the highest indices are dropped and the rest are matched. With a larger
     * one, the cells that exist are matched first and the new form's leftover
     * positions become new cells, which start at the current position of the
     * nearest existing cell and spring out from there. Either way, the frame on
     * which the count changes is a cut for anything that pairs cells by index.
     *
     * Calling this mid-morph starts a new trip from wherever the cells are,
     * keeping their velocity. A [LumosConfig.reducedMotion] host draws through
     * [buildStill], which snaps the morph to its destination. A resolution
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
     * @throws IllegalArgumentException when [count] is not positive or
     *  [durationSeconds] is negative.
     */
    fun morphTo(
        form: LumosForm,
        count: Int = currentCount,
        choreography: Choreography = Choreography.FLOW,
        durationSeconds: Float = DEFAULT_MORPH_SECONDS,
        easingName: String? = null,
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
        val origins =
            List(fromCount) { i ->
                if (fromMorph != null) {
                    Vector3(fromMorph.positions[3 * i], fromMorph.positions[3 * i + 1], fromMorph.positions[3 * i + 2])
                } else {
                    fromSample.positions[fromPermutation?.get(i) ?: i]
                }
            }
        val framingFrom = effectiveFraming(fromSample)

        val toSample = target.sample(lattice.count, look.look, lastTimeSeconds)
        val toCount = toSample.count
        val matched = minOf(fromCount, toCount)
        val assignment = matcher.match(origins.subList(0, matched), toSample.positions)
        val nextPermutation = IntArray(toCount)
        assignment.copyInto(nextPermutation)
        val body = FormMorph(toCount)
        for (i in 0 until matched) {
            val origin = origins[i]
            body.positions[3 * i] = origin.x
            body.positions[3 * i + 1] = origin.y
            body.positions[3 * i + 2] = origin.z
            if (fromMorph != null) {
                body.velocities[3 * i] = fromMorph.velocities[3 * i]
                body.velocities[3 * i + 1] = fromMorph.velocities[3 * i + 1]
                body.velocities[3 * i + 2] = fromMorph.velocities[3 * i + 2]
            }
        }
        if (toCount > fromCount) spawnLeftovers(nextPermutation, assignment, origins, toSample.positions, body)

        // Not through the public setter, which would reset the identity just built.
        adoptForm(target)
        permutation = nextPermutation
        if (durationSeconds == 0f) {
            morph = null
            return
        }
        morphFramingFrom = framingFrom
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
            for (origin in origins) {
                val d = (origin - target).lengthSquared()
                if (d < nearestDistance) {
                    nearestDistance = d
                    nearest = origin
                }
            }
            body.positions[3 * cell] = nearest.x
            body.positions[3 * cell + 1] = nearest.y
            body.positions[3 * cell + 2] = nearest.z
            cell++
        }
    }

    /** The current form sampled where the last build sampled it. */
    private fun currentSample(): FormSample = form.sample(lattice.count, look.look, lastTimeSeconds)

    /**
     * Produce a [VoxelFrame] from [snapshot], advancing phase accumulators
     * and rotations by [dt] seconds.
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
        val sample = current.sample(lattice.count, look.look, timestampEpochMillis / 1_000f)
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
        // Reduced motion snaps: the body is already where the morph was heading.
        morph = null

        val glyph = advanceGlyph(dt)?.glyph
        lastTimeSeconds = snapshot.elapsedTimeSeconds
        val sample = form.sample(lattice.count, look.look, snapshot.elapsedTimeSeconds)
        stillFrame?.let { held ->
            if (atmosphere === stillAtmosphere &&
                phases == stillPhases &&
                glyph == stillGlyph &&
                sample === stillSample &&
                permutation === stillPermutation &&
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

        val glyphLifecycle = advanceGlyph(dt)
        phases = phases.advanced(atmosphere, dt)

        lastTimeSeconds = snapshot.elapsedTimeSeconds
        val sample = form.sample(lattice.count, look.look, snapshot.elapsedTimeSeconds)
        val body = stepMorph(sample, dt)
        return frameAt(
            atmosphere = atmosphere,
            transition = snapshot.atmosphereTransition,
            phases = phases,
            glyphLifecycle = glyphLifecycle,
            sample = sample,
            permutation = permutation,
            morphPositions = body?.positions,
            framing = if (body == null) framing(sample) else effectiveFraming(sample),
            modulator = modulatorFor(sample),
            tick = snapshot.frameIndex,
            timestampEpochMillis = millisFromElapsed(snapshot.elapsedTimeSeconds),
        )
    }

    /**
     * Advance the morph in flight by [dt] toward [sample]'s positions and return
     * it, or null when there is none or it has just settled — from which frame
     * on the sample is rendered directly, so a settled form costs nothing.
     */
    private fun stepMorph(
        sample: FormSample,
        dt: Float,
    ): FormMorph? {
        val body = morph ?: return null
        if (body.count != sample.count) {
            // The form's count moved under the morph: a cut, as before morphs existed.
            morph = null
            permutation = null
            return null
        }
        body.step(dt, sample.positions)
        if (!body.isSettling) {
            morph = null
            return null
        }
        return body
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
        )
    }

    /**
     * How the current [form] is framed, for [sample]: a [FramedForm]'s scale and
     * its cell size relative to the lattice's, `cellRadius · 2 · resolution`,
     * so a renderer sizing cells by the atmosphere's resolution draws them the
     * size the form asked for. The lattice, and any form that is not framed,
     * is drawn as is.
     */
    private fun framing(sample: FormSample): Framing {
        val framed = form as? FramedForm ?: return Framing.NONE
        val cellScale = framed.cellRadius(sample.count) * 2f * max(lattice.resolution, 1)
        return if (framed.scale == 1f && cellScale == 1f) Framing.NONE else Framing(framed.scale, cellScale)
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
     * times the lattice's cell.
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
        modulator: LookModulator?,
        tick: Long,
        timestampEpochMillis: Long,
    ): VoxelFrame {
        val formScale = framing.formScale
        val cellScale = framing.cellScale
        val pulse = 1f + sin(phases.pulsePhase) * atmosphere.pulseAmplitude
        // voxelGap is the fraction of a lattice cell left empty between cubes, so the
        // cube itself fills what remains. Clamped because an overshoot easing can carry
        // an interpolated gap briefly past its endpoints, and a negative fill would
        // invert the cube.
        val voxelFill = (1f - atmosphere.voxelGap).coerceIn(0f, 1f)
        val effectiveYSquash = atmosphere.ySquash * (config.globalYSquashOverride ?: 1f)
        val glyphColor =
            glyphLifecycle
                ?.takeIf { config.enableGlyphCarving }
                ?.let { NeutralColor.fromHsl(it.glyph.hue, it.glyph.saturation, it.glyph.lightness) }
        val glyphShape = glyphLifecycle?.takeIf { config.enableGlyphCarving }?.let { GlyphShape.forGlyph(it.glyph) }
        val glyphVisibility = glyphLifecycle?.takeIf { config.enableGlyphCarving }?.visibility ?: 0f
        val lookTime = phases.lookSeconds.toFloat()
        // A look that owns the yaw replaces the atmosphere's wherever this frame reads it.
        val lookYaw = modulator?.yaw(lookTime)
        val yaw = lookYaw ?: phases.orbRotationY
        val glyphRotation = Vector3(phases.orbRotationX, yaw, 0f)
        if (modulator != null) {
            val rateY = if (lookYaw == null) atmosphere.rotationY else yawRate(modulator, lookTime)
            rotation.set(phases.orbRotationX, yaw, atmosphere.rotationX, rateY)
            modulator.beginFrame(lookTime, rotation)
        }

        val positions = sample.positions
        val directions = sample.unitDirections
        val jitters = sample.jitter
        val thetas = sample.theta
        val phis = sample.phi
        val cells = ArrayList<VoxelCell>(sample.count)
        for (cell in 0 until sample.count) {
            val index = permutation?.get(cell) ?: cell
            val mix = computeMix(atmosphere, transition, sample, index, phases.patternPhase)
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
            if (modulator != null) {
                modulation.reset()
                rotation.place(view, restX, restY, restZ, direction.x, direction.y, direction.z)
                modulator.modulate(index, view, lookTime, modulation)
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
            val px = restX + jitter.x * atmosphere.noise * JITTER_GAIN
            val py = restY + jitter.y * atmosphere.noise * JITTER_GAIN
            val pz = restZ + jitter.z * atmosphere.noise * JITTER_GAIN
            val bumpAmount =
                atmosphere.surfaceBump *
                    sin(thetas[index] * BUMP_THETA + phis[index] * BUMP_PHI + phases.patternPhase * BUMP_PHASE)

            val ux = direction.x
            val uy = direction.y
            val uz = direction.z

            val finalX = (px + ux * bumpAmount) * pulse * formScale
            val finalY = (py + uy * bumpAmount) * effectiveYSquash * pulse * formScale
            val finalZ = (pz + uz * bumpAmount) * pulse * formScale

            val litColor = if (lit > 0f) NeutralColor.lerp(color, NeutralColor.WHITE, lit * LIT_MIX) else color
            cells +=
                VoxelCell(
                    x = finalX,
                    y = finalY,
                    z = finalZ,
                    scale = scale * cellScale,
                    red = litColor.red,
                    green = litColor.green,
                    blue = litColor.blue,
                    alpha = if (lookAlpha != 1f) lookAlpha else null,
                )
        }

        val ambient = computeAmbient(atmosphere, phases.orbRotationX, yaw)

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
    ): VoxelAmbient {
        val primary = NeutralColor.fromHsl(atmosphere.primaryHue, atmosphere.saturation, atmosphere.lightness)
        val secondary = NeutralColor.fromHsl(atmosphere.secondaryHue, atmosphere.saturation, atmosphere.lightness)
        val glow = NeutralColor.lerpOklab(primary, secondary, 0.5f)
        return VoxelAmbient(
            glowRed = glow.red,
            glowGreen = glow.green,
            glowBlue = glow.blue,
            glowIntensity = atmosphere.glow,
            orbRotationX = orbRotationX,
            orbRotationY = orbRotationY,
            orbRotationZ = 0f,
        )
    }

    /**
     * A [FramedForm]'s framing resolved against the lattice: [formScale]
     * multiplies every position, [cellScale] every cell's scale. [NONE] is the
     * lattice's own, and leaves every frame as it was before forms were framed.
     */
    private class Framing(val formScale: Float, val cellScale: Float) {
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
