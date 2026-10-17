package link.socket.phosphor.lumos.dom

import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.frame.LumosCanvasFrame

/**
 * How cells are coloured: each its own sRGB, or all in CSS `currentColor`.
 */
internal enum class SvgTint {
    /** Each cell writes its own colour and alpha. */
    PER_CELL,

    /**
     * Every cell is painted `currentColor`, set once when the element is made; a cell writes only
     * its luminance times its alpha as opacity, so the page's CSS `color` tints the orb. The inline
     * orb's case: fewer writes a frame, and the orb takes the text colour around it.
     */
    CURRENT_COLOR,
}

/**
 * How [SvgOrbSurface] draws cells, `LumosCanvas`'s `begin / cell / end` contract in SVG: [begin]
 * once per frame, [cell] per voxel in painter's order (z ascending), [end] to flush anything
 * accumulated. A style that accumulates cells and emits once per frame fits the same contract.
 *
 * Every cell is one element of [tag], made once and kept; the painter writes its attributes through
 * the element's cache, geometry in slots `0 until` [COLOUR_SLOT], so an unchanged cell writes
 * nothing. One painter per [SurfaceStyle], from [svgPainter].
 */
internal abstract class SvgCellPainter(
    private val tint: SvgTint,
    /** The attribute a cell's colour goes to: `fill`, or `stroke` for a stroked mark. */
    private val paint: String,
) {
    /** The SVG element a cell is drawn as. */
    abstract val tag: String

    private val opacity = "$paint-opacity"

    /** Write what every cell of this style carries for good, once, as its element is made. */
    open fun prepare(cell: CachedNode) {
        if (tint == SvgTint.CURRENT_COLOR) cell.set(COLOUR_SLOT, paint, "currentColor")
    }

    open fun begin(frame: LumosCanvasFrame) {}

    /**
     * One voxel. [radiusPx] is already held to [SurfaceStyle.MIN_RADIUS_PX]; `(dx, dy)` is the
     * spin direction, both 0 when unknown.
     */
    abstract fun cell(
        cell: CachedNode,
        screenX: Float,
        screenY: Float,
        radiusPx: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        z: Float,
        dx: Float,
        dy: Float,
    )

    open fun end() {}

    /** The cell's colour, as [tint] carries it. */
    protected fun colour(
        cell: CachedNode,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        when (tint) {
            SvgTint.PER_CELL -> {
                cell.set(COLOUR_SLOT, paint, svgRgb(red, green, blue))
                cell.set(OPACITY_SLOT, opacity, svgNumber(alpha))
            }
            SvgTint.CURRENT_COLOR ->
                cell.set(OPACITY_SLOT, opacity, svgNumber(luminance(red, green, blue) * alpha))
        }
    }

    companion object {
        /** The cache slot of a cell's colour; geometry uses the slots below it. */
        const val COLOUR_SLOT = 5

        /** The cache slot of a cell's opacity. */
        const val OPACITY_SLOT = 6

        /** Slots per cell. */
        const val SLOTS = 7

        // Rec. 709 relative luminance of the sRGB values, as the spike measured it.
        fun luminance(
            red: Float,
            green: Float,
            blue: Float,
        ): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
    }
}

/** The painter that draws [this] style in [tint]. */
internal fun SurfaceStyle.svgPainter(tint: SvgTint): SvgCellPainter =
    when (this) {
        SurfaceStyle.Squares -> SquaresSvgPainter(tint)
        SurfaceStyle.Dots -> DotsSvgPainter(tint)
        SurfaceStyle.Dashes -> DashesSvgPainter(tint)
    }

/** Squares: a `<rect>` per cell, `2 × radius` on a side. */
internal class SquaresSvgPainter(tint: SvgTint) : SvgCellPainter(tint, paint = "fill") {
    override val tag: String get() = "rect"

    override fun cell(
        cell: CachedNode,
        screenX: Float,
        screenY: Float,
        radiusPx: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        z: Float,
        dx: Float,
        dy: Float,
    ) {
        cell.set(0, "x", svgNumber(screenX - radiusPx))
        cell.set(1, "y", svgNumber(screenY - radiusPx))
        val side = svgNumber(radiusPx * 2f)
        cell.set(2, "width", side)
        cell.set(3, "height", side)
        colour(cell, red, green, blue, alpha)
    }
}

/** Dots: a `<circle>` per cell, of the cell's radius. */
internal class DotsSvgPainter(tint: SvgTint) : SvgCellPainter(tint, paint = "fill") {
    override val tag: String get() = "circle"

    override fun cell(
        cell: CachedNode,
        screenX: Float,
        screenY: Float,
        radiusPx: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        z: Float,
        dx: Float,
        dy: Float,
    ) {
        cell.set(0, "cx", svgNumber(screenX))
        cell.set(1, "cy", svgNumber(screenY))
        cell.set(2, "r", svgNumber(radiusPx))
        colour(cell, red, green, blue, alpha)
    }
}

/**
 * Dashes: a round-capped `<line>` per cell along the spin direction, as far as the spin carries it —
 * `(dx, dy)` times [SurfaceStyle.DASH_HALF_LENGTH] radii each way — and [SurfaceStyle.DASH_WIDTH]
 * radii wide, as `LumosCanvas` strokes it. A cell with no direction is a zero-length line, which a
 * round cap draws as a dot of that width.
 */
internal class DashesSvgPainter(tint: SvgTint) : SvgCellPainter(tint, paint = "stroke") {
    override val tag: String get() = "line"

    override fun prepare(cell: CachedNode) {
        super.prepare(cell)
        cell.setOnce("stroke-linecap", "round")
    }

    override fun cell(
        cell: CachedNode,
        screenX: Float,
        screenY: Float,
        radiusPx: Float,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
        z: Float,
        dx: Float,
        dy: Float,
    ) {
        val half = SurfaceStyle.DASH_HALF_LENGTH * radiusPx
        val ux = dx * half
        val uy = dy * half
        cell.set(0, "x1", svgNumber(screenX - ux))
        cell.set(1, "y1", svgNumber(screenY - uy))
        cell.set(2, "x2", svgNumber(screenX + ux))
        cell.set(3, "y2", svgNumber(screenY + uy))
        cell.set(4, "stroke-width", svgNumber(SurfaceStyle.DASH_WIDTH * radiusPx))
        colour(cell, red, green, blue, alpha)
    }
}
