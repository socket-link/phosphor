package link.socket.phosphor.lumos.compose.spike

import kotlin.math.cos
import kotlin.math.sin
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame.CanvasVoxel

/**
 * PHO-68 throwaway: `ComposeLattice`'s projection with the one thing an overlay needs that it
 * lacks — a **viewport**. `ComposeLattice` fits `[-1, 1]` to the whole canvas and drops any voxel
 * that lands outside it, which is right for an orb in its own square and wrong for cells flying
 * across a root to a button: the orb must stay its size at its place while the canvas is the root.
 *
 * Same maths (X, then Y, then Z rotation; Y flipped; z-sorted ascending), `[-1, 1]` mapped to
 * `viewport.centre ± viewport.radiusPx`, voxels kept unless they leave the **root**.
 */
class ViewportProjection(
    val widthPx: Int,
    val heightPx: Int,
    val viewport: OrbViewport,
    val voxelRadiusPx: Float,
) {
    fun project(frame: VoxelFrame): LumosCanvasFrame {
        val ambient = frame.ambient
        val cosX = cos(ambient.orbRotationX)
        val sinX = sin(ambient.orbRotationX)
        val cosY = cos(ambient.orbRotationY)
        val sinY = sin(ambient.orbRotationY)
        val out = ArrayList<CanvasVoxel>(frame.cells.size)
        for (cell in frame.cells) {
            if (cell.scale <= 0f) continue
            val y1 = cell.y * cosX - cell.z * sinX
            val z1 = cell.y * sinX + cell.z * cosX
            val rx = cell.x * cosY + z1 * sinY
            val rz = -cell.x * sinY + z1 * cosY
            val screenX = viewport.toPxX(rx)
            val screenY = viewport.toPxY(y1)
            if (screenX < 0f || screenX >= widthPx || screenY < 0f || screenY >= heightPx) continue
            out +=
                CanvasVoxel(
                    screenX = screenX,
                    screenY = screenY,
                    radiusPx = voxelRadiusPx * cell.scale,
                    red = cell.red,
                    green = cell.green,
                    blue = cell.blue,
                    alpha = cell.alpha ?: 1f,
                    z = rz,
                    dx = rz,
                    dy = 0f,
                )
        }
        out.sortBy { it.z }
        return LumosCanvasFrame(widthPx, heightPx, out, ambient, frame.glyph, frame.tick)
    }
}
