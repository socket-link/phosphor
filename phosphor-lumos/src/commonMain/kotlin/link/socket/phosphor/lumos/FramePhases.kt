package link.socket.phosphor.lumos

import kotlin.math.PI
import link.socket.phosphor.signal.AtmosphereState

/**
 * The four phase accumulators a [VoxelFrameBuilder] integrates: where the
 * breath pulse, the pattern, and the orb's two rotations stand at one instant.
 *
 * In steady state — one atmosphere, no transition, no glyph — a frame is a pure
 * function of the atmosphere and these four numbers, and each advances linearly
 * at the atmosphere's rate. [advanced] is that integration as a pure function,
 * the same one [VoxelFrameBuilder.build] applies on every tick, so a caller can
 * step a set of phases forward to a later frame or, with a negative `dt`, back
 * to an earlier one: the frames the builder would have produced had its
 * atmosphere always been active. [VoxelFrameBuilder.buildSteadyState] renders one.
 *
 * @property pulsePhase Breath-pulse phase in radians, wrapped at 2π. Drives the
 *  sine that modulates [VoxelCell.scale] and the orb's radius.
 * @property patternPhase Pattern-evaluation phase, wrapped at `2π · 10` so the
 *  non-integer pattern multipliers (1.2, 2.5, ...) all meet an integer multiple of
 *  2π at the wrap and no pattern's sine jumps when the accumulator resets.
 * @property orbRotationX Integrated X-axis rotation in radians, wrapped at 2π.
 *  Exposed via [VoxelAmbient.orbRotationX].
 * @property orbRotationY Integrated Y-axis rotation in radians, wrapped at 2π.
 *  Exposed via [VoxelAmbient.orbRotationY].
 */
data class FramePhases(
    val pulsePhase: Float,
    val patternPhase: Float,
    val orbRotationX: Float,
    val orbRotationY: Float,
) {
    /**
     * These phases [dt] seconds on under [atmosphere]'s rates, each wrapped into
     * its range.
     *
     * A negative [dt] rewinds. Every rate is a constant of the atmosphere, so
     * stepping back by `k · dt` lands on the phases the builder held `k` ticks
     * ago — up to float rounding — provided the atmosphere was the same across
     * those ticks.
     */
    fun advanced(
        atmosphere: AtmosphereState,
        dt: Float,
    ): FramePhases =
        FramePhases(
            pulsePhase = wrap(pulsePhase + dt * atmosphere.pulseFrequency * TWO_PI, TWO_PI),
            patternPhase = wrap(patternPhase + dt * atmosphere.patternSpeed, PATTERN_WRAP),
            orbRotationX = wrap(orbRotationX + dt * atmosphere.rotationX, TWO_PI),
            orbRotationY = wrap(orbRotationY + dt * atmosphere.rotationY, TWO_PI),
        )

    companion object {
        /** Every accumulator at zero: where a fresh [VoxelFrameBuilder] starts. */
        val ZERO: FramePhases = FramePhases(0f, 0f, 0f, 0f)

        /** Wrap limit for [pulsePhase], [orbRotationX], and [orbRotationY]. */
        const val TWO_PI: Float = (2.0 * PI).toFloat()

        /** Wrap limit for [patternPhase]. */
        const val PATTERN_WRAP: Float = (2.0 * PI * 10.0).toFloat()

        private fun wrap(
            value: Float,
            limit: Float,
        ): Float {
            if (limit <= 0f) return value
            var wrapped = value % limit
            if (wrapped < 0f) wrapped += limit
            return wrapped
        }
    }
}
