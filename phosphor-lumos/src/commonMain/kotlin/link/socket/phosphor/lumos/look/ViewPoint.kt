package link.socket.phosphor.lumos.look

/**
 * A cell as the viewer sees it: its rest position and surface normal rotated
 * by the frame's orb rotation, in unit-sphere view space with `+z` toward the
 * viewer and `+y` up, before the surface's tilt.
 *
 * One instance per builder, rewritten for every cell by [ViewRotation.place];
 * a [LookModulator] reads it and must not keep it.
 */
class ViewPoint {
    /** Rotated rest position, `[-1, 1]³`. */
    var x: Float = 0f

    /** Rotated rest position, `[-1, 1]³`. */
    var y: Float = 0f

    /** Rotated rest position, `[-1, 1]³`; positive toward the viewer. */
    var z: Float = 0f

    /** `(z + 1) / 2`: 0 at the back of the sphere, 1 at the front. The rim ramp reads this. */
    val depth: Float get() = (z + 1f) * 0.5f

    /**
     * Rotated unit direction, the outward normal. On a shell it is `(x, y, z)`
     * normalised; on the solid lattice an interior cell keeps its angular
     * position, which is why the lens reads this rather than the position. Zero
     * for a cell at the origin.
     */
    var ux: Float = 0f

    /** Rotated unit direction; see [ux]. */
    var uy: Float = 0f

    /** Rotated unit direction; see [ux]. */
    var uz: Float = 0f

    /**
     * The view-space direction the spin carries this cell, per radian of spin:
     * `(z, 0)` for a yaw alone, Thinking Orbs' `sx = z1`. With both atmosphere
     * rates running it is the screen-plane part of the true velocity, divided by
     * the rates' magnitude. `y` up, as the rest of this point.
     */
    var dx: Float = 0f

    /** The view-space direction the spin carries this cell; see [dx]. */
    var dy: Float = 0f
}
