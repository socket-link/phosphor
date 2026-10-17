package link.socket.phosphor.lumos

import kotlin.math.PI
import link.socket.phosphor.signal.AtmosphereState

/**
 * The phase accumulators a [VoxelFrameBuilder] integrates: where the breath
 * pulse, the pattern, the orb's two rotations and the look's clock stand at one
 * instant.
 *
 * In steady state — one atmosphere, no transition, no glyph — a frame is a pure
 * function of the atmosphere and these numbers, and each advances linearly
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
 * @property lookSeconds The look's clock in seconds: the `t` every
 *  [link.socket.phosphor.lumos.look.LookModulator] reads. Advances by `dt`
 *  like the rest, and [at] sets it to the shared [LookClock]'s time, so every orb
 *  of one look modulates alike. Never wraps; a double keeps a frame's precision
 *  for years, and the narrowing to `Float` at the modulator is still under a
 *  hundredth of a second after a day.
 */
data class FramePhases(
    val pulsePhase: Float,
    val patternPhase: Float,
    val orbRotationX: Float,
    val orbRotationY: Float,
    val lookSeconds: Double = 0.0,
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
            lookSeconds = lookSeconds + dt,
        )

    companion object {
        /** Every accumulator at zero: where a fresh [VoxelFrameBuilder] starts. */
        val ZERO: FramePhases = FramePhases(0f, 0f, 0f, 0f, 0.0)

        /** Wrap limit for [pulsePhase], [orbRotationX], and [orbRotationY]. */
        const val TWO_PI: Float = (2.0 * PI).toFloat()

        /** Wrap limit for [patternPhase]. */
        const val PATTERN_WRAP: Float = (2.0 * PI * 10.0).toFloat()

        /**
         * The phases a builder holds [seconds] after starting from [ZERO] with [atmosphere] active
         * the whole time: [advanced] by [seconds] in one step, worked in double precision so a
         * clock that has run for days still lands every orb that reads it on the same phase.
         * [LookClock] time goes in; [LiveFrameSource.align] is the caller.
         */
        fun at(
            atmosphere: AtmosphereState,
            seconds: Double,
        ): FramePhases =
            FramePhases(
                pulsePhase = wrapAt(seconds * atmosphere.pulseFrequency * TWO_PI, TWO_PI),
                patternPhase = wrapAt(seconds * atmosphere.patternSpeed, PATTERN_WRAP),
                orbRotationX = wrapAt(seconds * atmosphere.rotationX, TWO_PI),
                orbRotationY = wrapAt(seconds * atmosphere.rotationY, TWO_PI),
                lookSeconds = seconds,
            )

        private fun wrap(
            value: Float,
            limit: Float,
        ): Float {
            if (limit <= 0f) return value
            var wrapped = value % limit
            if (wrapped < 0f) wrapped += limit
            return wrapped
        }

        // Wraps in doubles, then narrows; a value just under the limit can round up onto it.
        private fun wrapAt(
            value: Double,
            limit: Float,
        ): Float {
            val span = limit.toDouble()
            var wrapped = value % span
            if (wrapped < 0.0) wrapped += span
            val narrowed = wrapped.toFloat()
            return if (narrowed >= limit) 0f else narrowed
        }
    }
}
