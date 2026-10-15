package link.socket.phosphor.lumos.look

import kotlin.math.max

/**
 * Thinking Orbs' base look: the sphere lit from the front.
 *
 * Opacity ramps from 0 just behind the rim to 1 at the front:
 * `a = max(0, (d − 0.3) / 0.7)` for `d = (z + 1) / 2`. With [radiusRamp], radius
 * follows `0.5 + 1.4·d`, normalised so the front keeps the atmosphere's full
 * size and the back shrinks to a quarter of it: Thinking Orbs' front-to-back
 * ratio, without growing a lattice cube past its cell. The yaw itself stays the
 * atmosphere's `rotationY`.
 *
 * The other looks apply the same opacity ramp before their own modulation, so
 * the rim fade is on wherever a look is.
 */
internal class BaseSpin(
    private val radiusRamp: Boolean,
) : LookModulator {
    override fun modulate(
        index: Int,
        view: ViewPoint,
        t: Float,
        out: CellModulation,
    ) {
        val depth = view.depth
        out.alpha = rimAlpha(depth)
        if (radiusRamp) out.radius = (RADIUS_BACK + RADIUS_GAIN * depth) / RADIUS_FRONT
    }

    companion object {
        /** Where the ramp starts, in depth: a cell at or behind this is invisible. */
        const val RIM_START: Float = 0.3f

        private const val RIM_SPAN: Float = 0.7f
        private const val RADIUS_BACK: Float = 0.5f
        private const val RADIUS_GAIN: Float = 1.4f
        private const val RADIUS_FRONT: Float = RADIUS_BACK + RADIUS_GAIN

        /** The rim opacity for a cell at [depth]: 0 behind [RIM_START], 1 at the front. */
        fun rimAlpha(depth: Float): Float = max(0f, (depth - RIM_START) / RIM_SPAN)
    }
}
