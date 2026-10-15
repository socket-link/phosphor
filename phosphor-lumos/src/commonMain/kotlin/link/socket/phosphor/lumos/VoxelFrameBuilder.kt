package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.field.VoxelSphere
import link.socket.phosphor.lumos.probe.FramePhase
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.lumos.probe.measure
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AtmospherePattern
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.AtmosphereTransition

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

    /** Integrated Y-axis rotation in radians. Exposed via [VoxelAmbient.orbRotationY]. */
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
     * resolution, [LumosLook.BASE], and the frame's time in seconds; see
     * [LumosForm.sample]. Setting it takes effect on the next [build].
     */
    var form: LumosForm = lattice
        set(value) {
            field = value
            if (value is Forms.Lattice) lattice = value
        }

    /**
     * The lattice at the atmosphere's current resolution. Rebuilt when
     * [AtmosphereState.resolution] changes and stable across ticks otherwise —
     * no per-frame allocation. It is the geometry being rendered only while
     * [form] is a lattice, which it is by default.
     */
    val voxelSphere: VoxelSphere get() = lattice.sphere

    private var activeGlyph: GlyphLifecycle? = null

    // The last still built and what it was built from; see buildStill.
    private var stillFrame: VoxelFrame? = null
    private var stillAtmosphere: AtmosphereState? = null
    private var stillPhases: FramePhases? = null
    private var stillGlyph: LumosGlyph? = null
    private var stillSample: FormSample? = null

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
     * own accumulators, glyph, and lattice are left as they were.
     *
     * With [FramePhases.advanced] this reconstructs frames the builder never
     * produced. A loop recorded straight after a transition fades its tail
     * toward the frames it *would* have shown before it started had its
     * atmosphere always been active: read [phases] at the loop's first frame,
     * rewind by `k · dt`, and build here. In steady state the result is the frame
     * [build] returned at those phases, up to float rounding; `Oscilloscope` in
     * `:phosphor-trace` is the caller this exists for.
     *
     * Cells are ordered as in [voxelSphere], which [form] must be and
     * [atmosphere] must match in resolution: the lattice is the one thing a
     * steady-state frame shares with the live stream, and building a throwaway
     * one here would hide a per-call allocation behind a method that promises
     * none. Not measured by the probe, which times the live render loop.
     *
     * @param tick [VoxelFrame.tick] of the result.
     * @param timestampEpochMillis [VoxelFrame.timestampEpochMillis] of the result.
     * @throws IllegalArgumentException when [form] is not a [Forms.Lattice], or
     *  [atmosphere]'s resolution differs from [voxelSphere]'s.
     */
    fun buildSteadyState(
        atmosphere: AtmosphereState,
        phases: FramePhases,
        tick: Long,
        timestampEpochMillis: Long,
    ): VoxelFrame {
        val current = form
        require(current is Forms.Lattice) {
            "buildSteadyState renders on the builder's lattice, but the current form is $current"
        }
        require(atmosphere.resolution == current.resolution) {
            "buildSteadyState renders on the builder's lattice: atmosphere resolution ${atmosphere.resolution} " +
                "differs from the current ${current.resolution}"
        }
        return frameAt(
            atmosphere = atmosphere,
            transition = null,
            phases = phases,
            glyphLifecycle = null,
            sample = current.sample(current.count, LumosLook.BASE, timestampEpochMillis / 1_000f),
            framing = Framing.NONE,
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

        val glyph = advanceGlyph(dt)?.glyph
        val sample = form.sample(lattice.count, LumosLook.BASE, snapshot.elapsedTimeSeconds)
        stillFrame?.let { held ->
            if (atmosphere === stillAtmosphere &&
                phases == stillPhases &&
                glyph == stillGlyph &&
                sample === stillSample &&
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
                    framing = framing(sample),
                    tick = snapshot.frameIndex,
                    timestampEpochMillis = millisFromElapsed(snapshot.elapsedTimeSeconds),
                )
            }
        stillFrame = frame
        stillAtmosphere = atmosphere
        stillPhases = phases
        stillGlyph = glyph
        stillSample = sample
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

        val sample = form.sample(lattice.count, LumosLook.BASE, snapshot.elapsedTimeSeconds)
        return frameAt(
            atmosphere = atmosphere,
            transition = snapshot.atmosphereTransition,
            phases = phases,
            glyphLifecycle = glyphLifecycle,
            sample = sample,
            framing = framing(sample),
            tick = snapshot.frameIndex,
            timestampEpochMillis = millisFromElapsed(snapshot.elapsedTimeSeconds),
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
     * The pure core of a build: every input named, nothing read from the
     * builder but its lattice resolution and [config]. [build] advances state
     * and calls this; [buildSteadyState] calls it as it is. One cell per entry
     * of [sample], in its order, positioned at [Framing.formScale] times the
     * form's and sized at [Framing.cellScale] times the lattice's cell.
     */
    private fun frameAt(
        atmosphere: AtmosphereState,
        transition: AtmosphereTransition?,
        phases: FramePhases,
        glyphLifecycle: GlyphLifecycle?,
        sample: FormSample,
        framing: Framing,
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
        val glyphRotation = Vector3(phases.orbRotationX, phases.orbRotationY, 0f)

        val positions = sample.positions
        val directions = sample.unitDirections
        val jitters = sample.jitter
        val thetas = sample.theta
        val phis = sample.phi
        val cells = ArrayList<VoxelCell>(sample.count)
        for (index in 0 until sample.count) {
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
            val scale = voxelFill * pulse * boundaryShrink * glyphShrink

            // Omission reads the atmosphere's own scale: a form's cell size changes how big a
            // cell is drawn, not whether it is worth drawing.
            if (config.omitBelowScale > 0f && scale < config.omitBelowScale) continue

            val position = positions[index]
            val jitter = jitters[index]
            val px = position.x + jitter.x * atmosphere.noise * JITTER_GAIN
            val py = position.y + jitter.y * atmosphere.noise * JITTER_GAIN
            val pz = position.z + jitter.z * atmosphere.noise * JITTER_GAIN
            val bumpAmount =
                atmosphere.surfaceBump *
                    sin(thetas[index] * BUMP_THETA + phis[index] * BUMP_PHI + phases.patternPhase * BUMP_PHASE)

            val ux = direction.x
            val uy = direction.y
            val uz = direction.z

            val finalX = (px + ux * bumpAmount) * pulse * formScale
            val finalY = (py + uy * bumpAmount) * effectiveYSquash * pulse * formScale
            val finalZ = (pz + uz * bumpAmount) * pulse * formScale

            cells +=
                VoxelCell(
                    x = finalX,
                    y = finalY,
                    z = finalZ,
                    scale = scale * cellScale,
                    red = color.red,
                    green = color.green,
                    blue = color.blue,
                )
        }

        val ambient = computeAmbient(atmosphere, phases)

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
            if (form is Forms.Lattice) form = lattice
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
        phases: FramePhases,
    ): VoxelAmbient {
        val primary = NeutralColor.fromHsl(atmosphere.primaryHue, atmosphere.saturation, atmosphere.lightness)
        val secondary = NeutralColor.fromHsl(atmosphere.secondaryHue, atmosphere.saturation, atmosphere.lightness)
        val glow = NeutralColor.lerpOklab(primary, secondary, 0.5f)
        return VoxelAmbient(
            glowRed = glow.red,
            glowGreen = glow.green,
            glowBlue = glow.blue,
            glowIntensity = atmosphere.glow,
            orbRotationX = phases.orbRotationX,
            orbRotationY = phases.orbRotationY,
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
