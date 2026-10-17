package link.socket.phosphor.lumos.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame

/**
 * How [LumosCanvas] paints voxels: [begin] once per frame, [cell] per voxel in
 * painter's order (z ascending), [end] to flush anything still accumulated.
 * Styles that accumulate geometry (mesh, halftone, lines) emit on [end].
 *
 * One painter per [SurfaceStyle], from [painter]. A painter is reused across frames and
 * owns whatever it needs between them; it must not allocate per cell.
 */
internal interface SurfacePainter {
    fun begin(
        scope: DrawScope,
        frame: LumosCanvasFrame,
    )

    /**
     * One voxel. [radiusPx] is already held to [SurfaceStyle.MIN_RADIUS_PX]; `(dx, dy)` is
     * the spin direction from [LumosCanvasFrame.CanvasVoxel.dx] and `.dy`, both 0 when unknown.
     */
    fun cell(
        index: Int,
        screenX: Float,
        screenY: Float,
        radiusPx: Float,
        rgba: Color,
        z: Float,
        dx: Float,
        dy: Float,
    )

    fun end()
}

/** The painter that draws [this] style; a new instance each call, so remember it. */
internal fun SurfaceStyle.painter(): SurfacePainter =
    when (this) {
        SurfaceStyle.Squares -> SquaresPainter()
        SurfaceStyle.Dots -> DotsPainter()
        SurfaceStyle.Dashes -> DashesPainter()
    }

/** A painter that draws each cell as it arrives, holding the scope between [begin] and [end]. */
internal abstract class PerCellPainter : SurfacePainter {
    protected var scope: DrawScope? = null

    override fun begin(
        scope: DrawScope,
        frame: LumosCanvasFrame,
    ) {
        this.scope = scope
    }

    override fun end() {
        scope = null
    }
}

/**
 * Squares, one `drawRect` per cell. Batching cells into paths was measured and rejected
 * (PHO-54): per-voxel colours leave ~85 distinct colours, so batching by (depth bucket, colour)
 * still costs hundreds of calls, reorders overlapping voxels and was slower on a CPU raster.
 * The other per-cell styles follow the same finding.
 */
internal class SquaresPainter : PerCellPainter() {
    override fun cell(
        index: Int,
        screenX: Float,
        screenY: Float,
        radiusPx: Float,
        rgba: Color,
        z: Float,
        dx: Float,
        dy: Float,
    ) {
        scope?.drawRect(
            color = rgba,
            topLeft = Offset(screenX - radiusPx, screenY - radiusPx),
            size = Size(radiusPx * 2f, radiusPx * 2f),
        )
    }
}

/** Dots: one `drawCircle` per cell, of the cell's radius. */
internal class DotsPainter : PerCellPainter() {
    override fun cell(
        index: Int,
        screenX: Float,
        screenY: Float,
        radiusPx: Float,
        rgba: Color,
        z: Float,
        dx: Float,
        dy: Float,
    ) {
        scope?.drawCircle(color = rgba, radius = radiusPx, center = Offset(screenX, screenY))
    }
}

/**
 * Dashes: one round-capped `drawLine` per cell along the spin direction, as far as the spin
 * carries it — `(dx, dy)` times [SurfaceStyle.DASH_HALF_LENGTH] radii each way, so a cell at
 * the front of the sphere streaks and one at the limb, moving in depth, is a dot — and
 * [SurfaceStyle.DASH_WIDTH] radii wide. A cell with no direction is a dot of that width.
 */
internal class DashesPainter : PerCellPainter() {
    override fun cell(
        index: Int,
        screenX: Float,
        screenY: Float,
        radiusPx: Float,
        rgba: Color,
        z: Float,
        dx: Float,
        dy: Float,
    ) {
        val half = SurfaceStyle.DASH_HALF_LENGTH * radiusPx
        val ux = dx * half
        val uy = dy * half
        scope?.drawLine(
            color = rgba,
            start = Offset(screenX - ux, screenY - uy),
            end = Offset(screenX + ux, screenY + uy),
            strokeWidth = SurfaceStyle.DASH_WIDTH * radiusPx,
            cap = StrokeCap.Round,
        )
    }
}
