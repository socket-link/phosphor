package link.socket.phosphor.lumos.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame

/**
 * How [LumosCanvas] paints voxels: [begin] once per frame, [cell] per voxel in
 * painter's order (z ascending), [end] to flush anything still accumulated.
 * Styles that accumulate geometry (mesh, halftone, lines) emit on [end].
 */
internal interface CellStyle {
    fun begin(
        scope: DrawScope,
        frame: LumosCanvasFrame,
    )

    fun cell(
        index: Int,
        screenX: Float,
        screenY: Float,
        radiusPx: Float,
        rgba: Color,
        z: Float,
    )

    fun end()
}

/**
 * Squares, one `drawRect` per cell. Batching cells into paths was measured and rejected
 * (PHO-54): per-voxel colours leave ~85 distinct colours, so batching by (depth bucket, colour)
 * still costs hundreds of calls, reorders overlapping voxels and was slower on a CPU raster.
 */
internal class SquareCellStyle : CellStyle {
    private var scope: DrawScope? = null

    override fun begin(
        scope: DrawScope,
        frame: LumosCanvasFrame,
    ) {
        this.scope = scope
    }

    override fun cell(
        index: Int,
        screenX: Float,
        screenY: Float,
        radiusPx: Float,
        rgba: Color,
        z: Float,
    ) {
        scope?.drawRect(
            color = rgba,
            topLeft = Offset(screenX - radiusPx, screenY - radiusPx),
            size = Size(radiusPx * 2f, radiusPx * 2f),
        )
    }

    override fun end() {
        scope = null
    }
}
