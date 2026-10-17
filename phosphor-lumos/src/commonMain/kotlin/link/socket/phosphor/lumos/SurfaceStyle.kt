package link.socket.phosphor.lumos

import kotlinx.serialization.Serializable

/**
 * How a surface draws each cell of a frame: the mark it makes where a voxel lands.
 *
 * Framework-free; each surface adapter draws the style in its own primitives.
 * `LumosCanvas` in `:phosphor-lumos-compose` reads it today, through its
 * `begin / cell / end` painter contract; a style that accumulates cells and
 * emits once per frame fits the same contract. The catalogue follows Thinking
 * Orbs' `OrbRender`, ported one style at a time (PHO-61):
 *
 * - [Squares], the default, is what every orb drew before styles existed.
 * - [Dots] draws a circle per cell.
 * - [Dashes] draws a short stroke per cell along the direction the orb's spin
 *   carries it and as far, so a turning orb reads as motion even when it is small.
 *
 * Every style draws a cell at least [MIN_RADIUS_PX] across. Below that a cell
 * rasterises to grey haze; at 20 px most of an orb is below it.
 */
@Serializable
sealed interface SurfaceStyle {
    /**
     * Whether the style ignores the viewer's tilt. A flat style is a screen
     * pattern — a halftone, ruled lines — that reads as a tilted sphere only
     * if the sphere is not also tilted under it, so a driver projects it
     * straight on and lets the look's motion carry the depth. None of
     * today's styles is flat.
     */
    val flat: Boolean get() = false

    /** A square per cell, `2 × radius` on a side. The default. */
    @Serializable
    data object Squares : SurfaceStyle

    /** A circle per cell of the cell's radius. */
    @Serializable
    data object Dots : SurfaceStyle

    /**
     * A stroke per cell along the spin direction, as far as the spin carries
     * the cell: the direction times [DASH_HALF_LENGTH] radii each way, so the
     * front of a turning sphere streaks and its limb, moving in depth, dots.
     * [DASH_WIDTH] radii wide, with round caps. A cell whose frame carries no
     * direction — one built by hand, or recorded before the direction existed
     * — is a dot of that width.
     */
    @Serializable
    data object Dashes : SurfaceStyle

    companion object {
        /** The smallest radius any style draws, in pixels: Thinking Orbs' floor. */
        const val MIN_RADIUS_PX: Float = 0.45f

        /** Half the length of a [Dashes] stroke at full spin speed, in cell radii. */
        const val DASH_HALF_LENGTH: Float = 1.8f

        /** The width of a [Dashes] stroke, in cell radii. */
        const val DASH_WIDTH: Float = 0.9f
    }
}
