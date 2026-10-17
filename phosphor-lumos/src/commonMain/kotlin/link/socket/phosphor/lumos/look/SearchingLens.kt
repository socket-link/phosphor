package link.socket.phosphor.lumos.look

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import link.socket.phosphor.lumos.Look

/**
 * Thinking Orbs' searching look: a lens hops between spots on the front of
 * the sphere.
 *
 * Every 1.8 s the lens picks a new spot, 15–45° off the view axis and never at
 * the rim, from [Looks.hash] of the hop index, and glides to it over the first
 * 40 % of the hop. A cell is in the lens when its *direction* is within
 * [LENS] radians of the lens direction, so a solid form works as a shell does.
 * Inside, cells swell out 12 %, spread away from the lens centre like a
 * magnifier by 35 %, enlarge 90 % and light fully; outside, they dim 55 %. The
 * rim fade applies underneath.
 *
 * Thinking Orbs enlarges harder at 24 px and under; the builder cannot see the
 * surface's size, so that rule is not ported here.
 */
internal class SearchingLens : LookModulator {
    /** `x` of the lens direction this frame, unit length. */
    var lensX: Float = 0f
        private set

    /** `y` of the lens direction this frame. */
    var lensY: Float = 0f
        private set

    /** `z` of the lens direction this frame; always positive, the lens is on the front. */
    var lensZ: Float = 1f
        private set

    private var spotX: Float = 0f
    private var spotY: Float = 0f
    private var spotZ: Float = 1f

    override fun beginFrame(
        t: Float,
        rotation: ViewRotation,
    ) {
        val hop = floor(t / HOP)
        val within = (t - hop * HOP) / HOP
        val glide = if (within >= GLIDE) 1f else Looks.smoothstep(within / GLIDE)
        val k = hop.toInt()

        spot(k - 1)
        val fromX = spotX
        val fromY = spotY
        val fromZ = spotZ
        spot(k)
        val x = fromX + (spotX - fromX) * glide
        val y = fromY + (spotY - fromY) * glide
        val z = fromZ + (spotZ - fromZ) * glide
        val length = sqrt(x * x + y * y + z * z)
        lensX = x / length
        lensY = y / length
        lensZ = z / length
    }

    override fun modulate(
        index: Int,
        view: ViewPoint,
        t: Float,
        out: CellModulation,
    ) {
        val rim = BaseSpin.rimAlpha(view.depth)
        val facing = view.ux * lensX + view.uy * lensY + view.uz * lensZ
        val inside =
            if (facing <= COS_LENS) {
                0f
            } else {
                val angle = acos(facing.coerceIn(-1f, 1f))
                Looks.smoothstep((LENS - angle) / EDGE)
            }
        if (inside > 0f) {
            out.extent = 1f + SWELL * inside
            out.ox = (view.x - lensX) * SPREAD * inside
            out.oy = (view.y - lensY) * SPREAD * inside
            out.radius = 1f + GROW * inside
            out.lit = inside
        }
        out.alpha = max(rim * (1f - DIM * (1f - inside)), inside)
    }

    /** Spot [k] of the sequence into `spotX/Y/Z`: a hashed azimuth and off-axis angle. */
    private fun spot(k: Int) {
        val azimuth = Looks.hash(k * 2f + 1f) * TWO_PI
        val off = OFF_MIN + Looks.hash(k * 2f + 2f) * (OFF_MAX - OFF_MIN)
        val ring = sin(off)
        spotX = ring * cos(azimuth)
        spotY = ring * sin(azimuth)
        spotZ = cos(off)
    }

    companion object {
        /** The lens's half-angle in radians. */
        const val LENS: Float = 0.6f

        /** A new spot every 1.8 s. */
        const val HOP: Float = Look.SEARCHING_PERIOD_SECONDS

        /** The lens glides for this fraction of a hop, then rests. */
        const val GLIDE: Float = 0.4f

        /** Inside the lens cells swell out by this fraction. */
        const val SWELL: Float = 0.12f

        /** Inside the lens cells spread away from its centre by this fraction of their offset. */
        const val SPREAD: Float = 0.35f

        /** Inside the lens cells grow by this fraction. */
        const val GROW: Float = 0.9f

        /** Outside the lens cells dim by this fraction. */
        const val DIM: Float = 0.55f

        /** The lens's soft edge, in radians inside [LENS]. */
        const val EDGE: Float = 0.15f

        /** Nearest a spot comes to the view axis, in radians: 15°. */
        val OFF_MIN: Float = (15.0 * PI / 180.0).toFloat()

        /** Farthest a spot goes from the view axis, in radians: 45°, well short of the rim. */
        val OFF_MAX: Float = (45.0 * PI / 180.0).toFloat()

        private val COS_LENS: Float = cos(LENS)
        private val TWO_PI: Float = (2.0 * PI).toFloat()
    }
}
