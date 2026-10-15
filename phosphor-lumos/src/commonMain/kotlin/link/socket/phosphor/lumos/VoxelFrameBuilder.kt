package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.math.sin
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.field.Voxel
import link.socket.phosphor.field.VoxelSphere
import link.socket.phosphor.field.facingCamera
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
 * voxel lattice so the surrounding pull-based runtime can call [build] each
 * tick without re-allocating geometry or resetting animation phase. Atmosphere
 * frequency changes can be interpolated through transitions without phase
 * discontinuities because the accumulators persist across calls and are
 * advanced by the current state's rate.
 *
 * The builder is intentionally mutable and single-threaded: one builder per
 * renderer, called from the render loop. Sharing across threads or sharing
 * across renderers will produce torn output.
 *
 * @param initialResolution Starting resolution for the underlying
 *  [VoxelSphere]. Rebuilt when [AtmosphereState.resolution] changes.
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

    /**
     * Current voxel lattice. Rebuilt when [AtmosphereState.resolution] changes.
     * Stable across ticks otherwise — no per-frame allocation.
     */
    var voxelSphere: VoxelSphere = VoxelSphere(initialResolution)
        private set

    private var activeGlyph: GlyphLifecycle? = null

    // The last still built and what it was built from; see buildStill.
    private var stillFrame: VoxelFrame? = null
    private var stillAtmosphere: AtmosphereState? = null
    private var stillPhases: FramePhases? = null
    private var stillGlyph: LumosGlyph? = null

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
     * Cells are ordered as in [voxelSphere], which [atmosphere] must match in
     * resolution: the lattice is the one thing a steady-state frame shares with
     * the live stream, and building a throwaway one here would hide a per-call
     * allocation behind a method that promises none. Not measured by the probe,
     * which times the live render loop.
     *
     * @param tick [VoxelFrame.tick] of the result.
     * @param timestampEpochMillis [VoxelFrame.timestampEpochMillis] of the result.
     * @throws IllegalArgumentException when [atmosphere]'s resolution differs from [voxelSphere]'s.
     */
    fun buildSteadyState(
        atmosphere: AtmosphereState,
        phases: FramePhases,
        tick: Long,
        timestampEpochMillis: Long,
    ): VoxelFrame {
        require(atmosphere.resolution == voxelSphere.resolution) {
            "buildSteadyState renders on the builder's lattice: atmosphere resolution ${atmosphere.resolution} " +
                "differs from the current ${voxelSphere.resolution}"
        }
        return frameAt(
            atmosphere = atmosphere,
            transition = null,
            phases = phases,
            glyphLifecycle = null,
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
     * lattice follows the atmosphere's resolution, as in [build].
     *
     * With no transition and no glyph this is [buildSteadyState] at [phases].
     * A still whose inputs have not changed — the same atmosphere, phases, and
     * glyph — is returned as the same instance, so a driver can tell nothing
     * moved without comparing cells; its tick and timestamp stay those of the
     * call that built it. Only a still that is actually built is measured by
     * the probe.
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

        if (atmosphere.resolution != voxelSphere.resolution) {
            voxelSphere = voxelSphere.rebuild(atmosphere.resolution)
        }

        val glyph = advanceGlyph(dt)?.glyph
        stillFrame?.let { held ->
            if (atmosphere === stillAtmosphere &&
                phases == stillPhases &&
                glyph == stillGlyph &&
                held.resolution == voxelSphere.resolution
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
                    tick = snapshot.frameIndex,
                    timestampEpochMillis = millisFromElapsed(snapshot.elapsedTimeSeconds),
                )
            }
        stillFrame = frame
        stillAtmosphere = atmosphere
        stillPhases = phases
        stillGlyph = glyph
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

        if (atmosphere.resolution != voxelSphere.resolution) {
            voxelSphere = voxelSphere.rebuild(atmosphere.resolution)
        }

        val glyphLifecycle = advanceGlyph(dt)
        phases = phases.advanced(atmosphere, dt)

        return frameAt(
            atmosphere = atmosphere,
            transition = snapshot.atmosphereTransition,
            phases = phases,
            glyphLifecycle = glyphLifecycle,
            tick = snapshot.frameIndex,
            timestampEpochMillis = millisFromElapsed(snapshot.elapsedTimeSeconds),
        )
    }

    /**
     * The pure core of a build: every input named, nothing read from the
     * builder but its lattice and [config]. [build] advances state and calls
     * this; [buildSteadyState] calls it as it is.
     */
    private fun frameAt(
        atmosphere: AtmosphereState,
        transition: AtmosphereTransition?,
        phases: FramePhases,
        glyphLifecycle: GlyphLifecycle?,
        tick: Long,
        timestampEpochMillis: Long,
    ): VoxelFrame {
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

        val cells = ArrayList<VoxelCell>(voxelSphere.voxels.size)
        for (voxel in voxelSphere.voxels) {
            val mix = computeMix(atmosphere, transition, voxel, phases.patternPhase)
            val boundaryShrink = computeBoundaryShrink(atmosphere.bipolarStrength, mix)
            val baseColor = computeVoxelColor(atmosphere, transition, mix)
            val rotatedDirection = glyphShape?.let { voxel.unitDirection.rotatedBy(glyphRotation) }
            val isGlyphMember =
                glyphShape != null &&
                    rotatedDirection != null &&
                    voxel.facingCamera(glyphRotation, GLYPH_FACING_THRESHOLD) &&
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

            if (config.omitBelowScale > 0f && scale < config.omitBelowScale) continue

            val px = voxel.normalizedPos.x + voxel.jitter.x * atmosphere.noise * JITTER_GAIN
            val py = voxel.normalizedPos.y + voxel.jitter.y * atmosphere.noise * JITTER_GAIN
            val pz = voxel.normalizedPos.z + voxel.jitter.z * atmosphere.noise * JITTER_GAIN
            val bumpAmount =
                atmosphere.surfaceBump *
                    sin(voxel.theta * BUMP_THETA + voxel.phi * BUMP_PHI + phases.patternPhase * BUMP_PHASE)

            val ux = voxel.unitDirection.x
            val uy = voxel.unitDirection.y
            val uz = voxel.unitDirection.z

            val finalX = (px + ux * bumpAmount) * pulse
            val finalY = (py + uy * bumpAmount) * effectiveYSquash * pulse
            val finalZ = (pz + uz * bumpAmount) * pulse

            cells +=
                VoxelCell(
                    x = finalX,
                    y = finalY,
                    z = finalZ,
                    scale = scale,
                    red = color.red,
                    green = color.green,
                    blue = color.blue,
                )
        }

        val ambient = computeAmbient(atmosphere, phases)

        return VoxelFrame(
            tick = tick,
            timestampEpochMillis = timestampEpochMillis,
            resolution = voxelSphere.resolution,
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

    private fun advanceGlyph(dt: Float): GlyphLifecycle? {
        val next = activeGlyph?.advance(dt) ?: return null
        activeGlyph = next.takeUnless { it.isComplete }
        return activeGlyph
    }

    private fun computeMix(
        atmosphere: AtmosphereState,
        transition: AtmosphereTransition?,
        voxel: Voxel,
        patternPhase: Float,
    ): Float {
        val toMix = evaluatePattern(atmosphere.pattern, voxel, patternPhase)
        if (transition == null || transition.from.pattern == transition.to.pattern) {
            return toMix
        }
        val fromMix = evaluatePattern(transition.from.pattern, voxel, patternPhase)
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

        internal fun evaluatePattern(
            pattern: AtmospherePattern,
            voxel: Voxel,
            patternPhase: Float,
        ): Float =
            when (pattern) {
                AtmospherePattern.LONGITUDE ->
                    (sin(voxel.theta * 2f + patternPhase * 2f) + 1f) * 0.5f
                AtmospherePattern.LATITUDE ->
                    (sin(voxel.phi * 2.5f + patternPhase * 2f) + 1f) * 0.5f
                AtmospherePattern.SPIRAL ->
                    (sin(voxel.theta * 2f + voxel.phi * 3f + patternPhase * 2.5f) + 1f) * 0.5f
                AtmospherePattern.SCAN ->
                    (sin(voxel.normalizedPos.y * 0.85f - patternPhase * 3f) + 1f) * 0.5f
                AtmospherePattern.PLASMA ->
                    (
                        sin(voxel.normalizedPos.x * 0.55f + patternPhase * 1.7f) +
                            sin(voxel.normalizedPos.y * 0.50f + patternPhase * 1.2f) +
                            sin(voxel.normalizedPos.z * 0.65f + patternPhase * 2.0f) + 3f
                    ) / 6f
                AtmospherePattern.PULSE ->
                    (sin(voxel.distance * 0.85f - patternPhase * 3f) + 1f) * 0.5f
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
