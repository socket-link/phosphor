package link.socket.phosphor.lumos.look

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import link.socket.phosphor.lumos.Look

/**
 * Thinking Orbs' retrying look: the orb turns, stops, springs back, and tries
 * again.
 *
 * The look owns the yaw ([yaw]): Thinking Orbs' `rewind(t, w)` run at double
 * time and half rate, `rewind(2t, w / 2)`, so the turn moves at the look's own
 * pace `w` — one revolution per 13 s, Thinking Orbs' `PERIOD` for retrying —
 * and a cycle takes 1.6 s. With 60 % of every turn given back, the orb gains
 * 0.94 s of turn per 3.2 s cycle and so averages about 44 s a revolution. The
 * atmosphere's `rotationY` is not read while this look is worn. Cells wear
 * [BaseSpin]'s rim fade and radius ramp underneath.
 */
internal class RetryingRewind : LookModulator {
    private val base = BaseSpin(radiusRamp = true)

    override fun yaw(t: Float): Float = wrap(rewind(TIME_SCALE * t.toDouble(), RATE * RATE_SCALE))

    override fun modulate(
        index: Int,
        view: ViewPoint,
        t: Float,
        out: CellModulation,
    ) = base.modulate(index, view, t, out)

    companion object {
        // Every constant below is Thinking Orbs' milliseconds over 1000: seconds here.

        /** One retry: turn, brake, spring back, launch. `3200 / 1000`. */
        const val CYCLE: Double = 3200.0 / 1000.0

        /** The turn at full rate. `2000 / 1000`. */
        const val TURN: Double = 2000.0 / 1000.0

        /** The brake to a stop. `200 / 1000`. */
        const val BRAKE: Double = 200.0 / 1000.0

        /** The spring back. `800 / 1000`. */
        const val SPRING: Double = 800.0 / 1000.0

        /** The launch back up to full rate. `200 / 1000`. */
        const val LAUNCH: Double = 200.0 / 1000.0

        /** The share of what it turned the spring gives back. */
        const val BACK: Double = 0.6

        /** The spring's decay, `e^(−4.5x)`. */
        const val DECAY: Double = 4.5

        /** The spring's ringing, `cos(3πx)`. */
        const val RING: Double = 3.0 * PI

        /** What a cycle turns before the spring, in seconds at full rate: the turn plus the brake's half. */
        const val TURNED: Double = TURN + BRAKE / 2.0

        /** What a whole cycle gains, in seconds at full rate: 0.94. */
        const val NET: Double = TURNED * (1.0 - BACK) + LAUNCH / 2.0

        /** Thinking Orbs' `PERIOD` for retrying: the turn moves at a revolution per 13 s. `13000 / 1000`. */
        const val PERIOD_SECONDS: Double = 13_000.0 / 1000.0

        /** The turn's rate, radians per second: `2π / PERIOD`. */
        const val RATE: Double = 2.0 * PI / PERIOD_SECONDS

        /** The look runs `rewind` at double time... */
        const val TIME_SCALE: Double = 2.0

        /** ...and half rate, so the turn keeps [RATE]. */
        const val RATE_SCALE: Double = 0.5

        /** One cycle in look seconds: [CYCLE] at double time, 1.6 s. */
        const val CYCLE_SECONDS: Float = Look.RETRYING_PERIOD_SECONDS

        /**
         * Thinking Orbs' spring, `1 − e^(−4.5x)·cos(3πx) − x·e^(−4.5)`: 0 at 0 and
         * exactly 1 at 1, overshooting past 1 on the way.
         */
        fun spring(x: Double): Double = 1.0 - exp(-DECAY * x) * cos(RING * x) - x * exp(-DECAY)

        /**
         * Thinking Orbs' `rewind(t, w)`, in seconds and radians: every [CYCLE]
         * the yaw turns at [w] for [TURN], brakes to a stop over [BRAKE], springs
         * back [BACK] of what it turned over [SPRING] and speeds up again over
         * [LAUNCH]. Whole cycles stack, `NET · w` each, so the yaw never jumps.
         * Not wrapped. `rewind(2.0, w)` is `2.0 · w`: the end of the first turn.
         */
        fun rewind(
            t: Double,
            w: Double,
        ): Double {
            val cycles = floor(t / CYCLE)
            val u = t - cycles * CYCLE
            val within =
                when {
                    u < TURN -> u
                    u < TURN + BRAKE -> {
                        val v = u - TURN
                        TURN + v - v * v / (2.0 * BRAKE)
                    }
                    u < TURN + BRAKE + SPRING -> TURNED - BACK * TURNED * spring((u - TURN - BRAKE) / SPRING)
                    else -> {
                        val v = u - TURN - BRAKE - SPRING
                        TURNED * (1.0 - BACK) + v * v / (2.0 * LAUNCH)
                    }
                }
            return (cycles * NET + within) * w
        }

        private const val TWO_PI: Double = 2.0 * PI

        /** [angle] in `0..2π`, as the atmosphere's yaw is recorded. */
        internal fun wrap(angle: Double): Float {
            var wrapped = angle % TWO_PI
            if (wrapped < 0.0) wrapped += TWO_PI
            val narrowed = wrapped.toFloat()
            return if (narrowed >= TWO_PI.toFloat()) 0f else narrowed
        }
    }
}
