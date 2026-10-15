package link.socket.phosphor.lumos.look

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One frame's orb rotation, ready to carry rest positions into view space.
 *
 * The same rotation the surfaces apply at projection — X first, then Y, the
 * angles' trigonometry worked in doubles and narrowed, so a cell's view point
 * here equals its projected point up to the surface's tilt. Z is always zero.
 * Also holds the atmosphere's rotation rates, which fix which way the spin is
 * carrying each cell ([ViewPoint.dx], [ViewPoint.dy]).
 *
 * One instance per builder, reset by [set] each frame; never retained.
 */
class ViewRotation {
    /** `cos` of the X rotation. */
    var cosX: Float = 1f
        private set

    /** `sin` of the X rotation. */
    var sinX: Float = 0f
        private set

    /** `cos` of the Y rotation. */
    var cosY: Float = 1f
        private set

    /** `sin` of the Y rotation. */
    var sinY: Float = 0f
        private set

    private var rateX: Float = 0f
    private var rateY: Float = 0f
    private var invRate: Float = 0f

    /**
     * Take the frame's angles and rates.
     *
     * @param rotationX The orb's X rotation in radians; `FramePhases.orbRotationX`.
     * @param rotationY The orb's Y rotation in radians; `FramePhases.orbRotationY`.
     * @param rateX The atmosphere's X rotation rate in radians per second.
     * @param rateY The atmosphere's Y rotation rate in radians per second.
     */
    fun set(
        rotationX: Float,
        rotationY: Float,
        rateX: Float,
        rateY: Float,
    ) {
        cosX = cos(rotationX.toDouble()).toFloat()
        sinX = sin(rotationX.toDouble()).toFloat()
        cosY = cos(rotationY.toDouble()).toFloat()
        sinY = sin(rotationY.toDouble()).toFloat()
        this.rateX = rateX
        this.rateY = rateY
        val rate = sqrt(rateX * rateX + rateY * rateY)
        invRate = if (rate > 0f) 1f / rate else 0f
    }

    /**
     * Fill [view] for one cell: its rest position and unit direction rotated into
     * view space, and the direction the spin carries it.
     */
    fun place(
        view: ViewPoint,
        x: Float,
        y: Float,
        z: Float,
        ux: Float,
        uy: Float,
        uz: Float,
    ) {
        val y1 = y * cosX - z * sinX
        val z1 = y * sinX + z * cosX
        val vx = x * cosY + z1 * sinY
        val vz = -x * sinY + z1 * cosY
        view.x = vx
        view.y = y1
        view.z = vz

        val uy1 = uy * cosX - uz * sinX
        val uz1 = uy * sinX + uz * cosX
        view.ux = ux * cosY + uz1 * sinY
        view.uy = uy1
        view.uz = -ux * sinY + uz1 * cosY

        if (invRate == 0f) {
            // No spin: the direction a yaw would take, so a still orb still has one.
            view.dx = vz
            view.dy = 0f
        } else {
            // d/dt of (Ry·Rx·p): the yaw's term plus the X rate's, carried through Ry.
            view.dx = (rateY * vz + rateX * y1 * sinY) * invRate
            view.dy = -rateX * (vx * sinY + vz * cosY) * invRate
        }
    }

    /** The view-space `z` of a rest position: how far toward the viewer it sits. */
    fun viewZ(
        x: Float,
        y: Float,
        z: Float,
    ): Float {
        val z1 = y * sinX + z * cosX
        return -x * sinY + z1 * cosY
    }

    /** `x` of the rest-space vector that a view-space push of `(ox, oy, 0)` is: the transpose. */
    fun unrotateX(
        ox: Float,
        oy: Float,
    ): Float = ox * cosY

    /** `y` of the rest-space vector that a view-space push of `(ox, oy, 0)` is. */
    fun unrotateY(
        ox: Float,
        oy: Float,
    ): Float = oy * cosX + ox * sinY * sinX

    /** `z` of the rest-space vector that a view-space push of `(ox, oy, 0)` is. */
    fun unrotateZ(
        ox: Float,
        oy: Float,
    ): Float = -oy * sinX + ox * sinY * cosX
}
