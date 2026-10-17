package link.socket.phosphor.lumos.look

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import link.socket.phosphor.lumos.Look

/**
 * Thinking Orbs' working look: every 1.7 s a ring of light runs down the
 * sphere over 1.2 s.
 *
 * The ring runs along the axis `(AXIS_X, AXIS_Y, AXIS_Z)`, the up axis tipped 30° toward the viewer and
 * rolled 10°, in view space. A cell's place on that run is
 * `q = (1 − view · axis) / 2`, 0 at the top and 1 at the bottom; the ring is at
 * `at = u / 1.2` for `u = t mod 1.7`. Cells at the ring grow 60 % and light
 * fully under `g = exp(−((q − at) / 0.2)²)`. What the ring has passed pulls in
 * 8 % and shrinks 15 %, then lets go over 0.5 s, so the whole sphere has
 * released by the time the next ring starts. The rim fade applies underneath.
 */
internal class WorkingRing : LookModulator {
    private var at: Float = 0f

    override fun beginFrame(
        t: Float,
        rotation: ViewRotation,
    ) {
        val u = t - PERIOD * floor(t / PERIOD)
        at = u / RUN
    }

    override fun modulate(
        index: Int,
        view: ViewPoint,
        t: Float,
        out: CellModulation,
    ) {
        val q = (1f - (view.x * AXIS_X + view.y * AXIS_Y + view.z * AXIS_Z)) * 0.5f
        val away = (q - at) / WIDTH
        val crest = exp(-away * away)
        val wake =
            if (q < at) {
                max(0f, 1f - (at - q) * RUN / RELEASE)
            } else {
                0f
            }
        val hold = wake * (1f - crest)
        out.radius = (1f + GROW * crest) * (1f - SHRINK * hold)
        out.extent = 1f - PULL * hold
        out.lit = crest
        out.alpha = max(BaseSpin.rimAlpha(view.depth), crest)
    }

    companion object {
        /** One ring every 1.7 s. */
        const val PERIOD: Float = Look.WORKING_PERIOD_SECONDS

        /** The ring takes 1.2 s to run from top to bottom. */
        const val RUN: Float = 1.2f

        /** The crest's Gaussian width along the run. */
        const val WIDTH: Float = 0.2f

        /** Cells at the crest grow by this fraction. */
        const val GROW: Float = 0.6f

        /** Cells the ring has passed pull in by this fraction of their radius. */
        const val PULL: Float = 0.08f

        /** Cells the ring has passed shrink by this fraction. */
        const val SHRINK: Float = 0.15f

        /** How long a passed cell takes to let go. */
        const val RELEASE: Float = 0.5f

        private const val TIP_DEGREES: Double = 30.0
        private const val ROLL_DEGREES: Double = 10.0
        private const val TIP: Double = TIP_DEGREES * PI / 180.0
        private const val ROLL: Double = ROLL_DEGREES * PI / 180.0

        /** `x` of the ring's axis: up, tipped 30° toward the viewer, rolled 10°. */
        val AXIS_X: Float = (-sin(ROLL) * cos(TIP)).toFloat()

        /** `y` of the ring's axis. */
        val AXIS_Y: Float = (cos(ROLL) * cos(TIP)).toFloat()

        /** `z` of the ring's axis. */
        val AXIS_Z: Float = sin(TIP).toFloat()
    }
}
