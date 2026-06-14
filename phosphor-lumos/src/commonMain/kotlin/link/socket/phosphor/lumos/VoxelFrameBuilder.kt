package link.socket.phosphor.lumos

import kotlin.math.PI
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
 * The builder owns continuous phase accumulators and the current voxel
 * lattice so the surrounding pull-based runtime can call [build] each tick
 * without re-allocating geometry or resetting animation phase. Atmosphere
 * frequency changes can be interpolated through transitions without phase
 * discontinuities because [pulsePhase] and [patternPhase] persist across
 * calls and are advanced by the current state's rate.
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
     * Continuous breath-pulse phase in radians.
     *
     * Advanced each tick by `dt * pulseFrequency * 2 * PI` so the sine wave
     * driving [VoxelCell.scale] modulation has no discontinuities when the
     * atmosphere's frequency interpolates during a transition. Wrapped at
     * 2π to preserve floating-point precision over long runs.
     */
    var pulsePhase: Float = 0f
        private set

    /**
     * Continuous pattern-evaluation phase.
     *
     * Advanced each tick by `dt * patternSpeed`. Wrapped at `2π * 10` so
     * non-integer pattern multipliers (1.2, 2.5, ...) still hit an integer
     * multiple of 2π at the wrap boundary; this keeps each pattern's sine
     * wave continuous when the accumulator resets.
     */
    var patternPhase: Float = 0f
        private set

    /**
     * Integrated X-axis rotation in radians. Exposed via [VoxelAmbient.orbRotationX].
     */
    var orbRotationX: Float = 0f
        private set

    /**
     * Integrated Y-axis rotation in radians. Exposed via [VoxelAmbient.orbRotationY].
     */
    var orbRotationY: Float = 0f
        private set

    /**
     * Current voxel lattice. Rebuilt when [AtmosphereState.resolution] changes.
     * Stable across ticks otherwise — no per-frame allocation.
     */
    var voxelSphere: VoxelSphere = VoxelSphere(initialResolution)
        private set

    private var activeGlyph: GlyphLifecycle? = null

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

    private fun buildFrame(
        snapshot: SceneSnapshot,
        dt: Float,
    ): VoxelFrame {
        val atmosphere =
            checkNotNull(snapshot.atmosphere) {
                "VoxelFrameBuilder requires SceneConfiguration.enableAtmosphere = true"
            }
        val transition = snapshot.atmosphereTransition

        if (atmosphere.resolution != voxelSphere.resolution) {
            voxelSphere = voxelSphere.rebuild(atmosphere.resolution)
        }

        val glyphLifecycle = advanceGlyph(dt)
        advancePhases(atmosphere, dt)

        val pulse = 1f + sin(pulsePhase) * atmosphere.pulseAmplitude
        val effectiveYSquash = atmosphere.ySquash * (config.globalYSquashOverride ?: 1f)
        val glyphColor =
            glyphLifecycle
                ?.takeIf { config.enableGlyphCarving }
                ?.let { NeutralColor.fromHsl(it.glyph.hue, it.glyph.saturation, it.glyph.lightness) }
        val glyphShape = glyphLifecycle?.takeIf { config.enableGlyphCarving }?.let { GlyphShape.forGlyph(it.glyph) }
        val glyphVisibility = glyphLifecycle?.takeIf { config.enableGlyphCarving }?.visibility ?: 0f
        val glyphRotation = Vector3(orbRotationX, orbRotationY, 0f)

        val cells = ArrayList<VoxelCell>(voxelSphere.voxels.size)
        for (voxel in voxelSphere.voxels) {
            val mix = computeMix(atmosphere, transition, voxel)
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
            val scale = atmosphere.voxelGap * pulse * boundaryShrink * glyphShrink

            if (config.omitBelowScale > 0f && scale < config.omitBelowScale) continue

            val px = voxel.normalizedPos.x + voxel.jitter.x * atmosphere.noise * JITTER_GAIN
            val py = voxel.normalizedPos.y + voxel.jitter.y * atmosphere.noise * JITTER_GAIN
            val pz = voxel.normalizedPos.z + voxel.jitter.z * atmosphere.noise * JITTER_GAIN
            val bumpAmount =
                atmosphere.surfaceBump *
                    sin(voxel.theta * BUMP_THETA + voxel.phi * BUMP_PHI + patternPhase * BUMP_PHASE)

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

        val ambient = computeAmbient(atmosphere)

        return VoxelFrame(
            tick = snapshot.frameIndex,
            timestampEpochMillis = millisFromElapsed(snapshot.elapsedTimeSeconds),
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

    private fun advancePhases(
        atmosphere: AtmosphereState,
        dt: Float,
    ) {
        pulsePhase = wrap(pulsePhase + dt * atmosphere.pulseFrequency * TWO_PI, TWO_PI)
        patternPhase = wrap(patternPhase + dt * atmosphere.patternSpeed, PATTERN_WRAP)
        orbRotationX = wrap(orbRotationX + dt * atmosphere.rotationX, TWO_PI)
        orbRotationY = wrap(orbRotationY + dt * atmosphere.rotationY, TWO_PI)
    }

    private fun computeMix(
        atmosphere: AtmosphereState,
        transition: AtmosphereTransition?,
        voxel: Voxel,
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

    private fun computeAmbient(atmosphere: AtmosphereState): VoxelAmbient {
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

    companion object {
        private const val TWO_PI: Float = (2.0 * PI).toFloat()
        private const val PATTERN_WRAP: Float = (2.0 * PI * 10.0).toFloat()

        private const val JITTER_GAIN: Float = 2.4f
        private const val BUMP_THETA: Float = 3.0f
        private const val BUMP_PHI: Float = 2.5f
        private const val BUMP_PHASE: Float = 1.4f
        private const val BAND_GAIN: Float = 0.4f
        private const val GLYPH_FACING_THRESHOLD: Float = 0.15f
        private const val GLYPH_BACKGROUND_SHRINK: Float = 0.30f

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

        private fun wrap(
            value: Float,
            limit: Float,
        ): Float {
            if (limit <= 0f) return value
            var wrapped = value % limit
            if (wrapped < 0f) wrapped += limit
            return wrapped
        }

        private fun millisFromElapsed(elapsedSeconds: Float): Long = (elapsedSeconds * 1_000.0).toLong()
    }
}
