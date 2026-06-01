package link.socket.phosphor.lumos.compose.projection

import kotlin.math.cos
import kotlin.math.sin
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame.CanvasVoxel

/**
 * Orthographic projection of a [VoxelFrame] to a [LumosCanvasFrame] for Compose Canvas rendering.
 *
 * Mirrors CliLattice with three structural differences:
 * 1. **No aspect compression** — pixel cells are 1:1, so [aspectRatio] defaults to 1.0.
 * 2. **No per-cell winner aggregation** — every visible voxel is emitted as its own [CanvasVoxel].
 *    The output list is unsorted by z. Painter's-algorithm depth sorting is the composable's
 *    responsibility, keeping this projector pure.
 * 3. **Orb rotation applied here** — each voxel's `(x, y, z)` lattice position is rotated by
 *    Euler angles from the [VoxelAmbient.orbRotationX], [VoxelAmbient.orbRotationY], and
 *    [VoxelAmbient.orbRotationZ] properties before projection.
 *    **Rotation order: X first, then Y, then Z.**
 *
 * Input lattice positions are in `[-1, 1]` on all axes, following the same convention as
 * CliLattice. Y is flipped: `y = +1` maps to the top of the canvas (`screenY = 0`).
 *
 * @param widthPx Output canvas width in pixels; must be > 0.
 * @param heightPx Output canvas height in pixels; must be > 0.
 * @param voxelRadiusPx Base voxel half-size in pixels at `scale = 1.0`; must be > 0.
 * @param aspectRatio Pixel aspect ratio applied to the Y axis; defaults to 1.0 for square pixels.
 *  Must be > 0.
 */
class ComposeLattice(
    val widthPx: Int,
    val heightPx: Int,
    val voxelRadiusPx: Float = 4f,
    val aspectRatio: Float = 1.0f,
) {
    init {
        require(widthPx > 0) { "widthPx must be > 0, got $widthPx" }
        require(heightPx > 0) { "heightPx must be > 0, got $heightPx" }
        require(voxelRadiusPx > 0f) { "voxelRadiusPx must be > 0, got $voxelRadiusPx" }
        require(aspectRatio > 0f) { "aspectRatio must be > 0, got $aspectRatio" }
    }

    /**
     * Project [frame] to a [LumosCanvasFrame].
     *
     * Algorithm:
     * 1. For each voxel with `scale > 0`, apply Euler XYZ rotation (X → Y → Z) using
     *    angles from [frame]'s ambient state.
     * 2. Project the rotated position orthographically:
     *    `screenX = (rx + 1) * 0.5 * widthPx`
     *    `screenY = (1 - (ry + 1) * 0.5) * heightPx * (1 / aspectRatio)`
     * 3. Skip voxels whose projected center falls outside `[0, widthPx) × [0, heightPx)`.
     * 4. Emit one [CanvasVoxel] per surviving voxel with `radiusPx = voxelRadiusPx * scale`,
     *    sRGB color forwarded directly, and rotated `z` preserved for depth compositing.
     *
     * Ambient and glyph state pass through unchanged.
     */
    fun project(frame: VoxelFrame): LumosCanvasFrame {
        val cells = frame.cells
        val ambient = frame.ambient

        val cosX = cos(ambient.orbRotationX.toDouble()).toFloat()
        val sinX = sin(ambient.orbRotationX.toDouble()).toFloat()
        val cosY = cos(ambient.orbRotationY.toDouble()).toFloat()
        val sinY = sin(ambient.orbRotationY.toDouble()).toFloat()
        val cosZ = cos(ambient.orbRotationZ.toDouble()).toFloat()
        val sinZ = sin(ambient.orbRotationZ.toDouble()).toFloat()

        val invAspect = 1f / aspectRatio
        val out = ArrayList<CanvasVoxel>(cells.size)

        for (cell in cells) {
            if (cell.scale <= 0f) continue

            // Rotate around X axis
            val x1 = cell.x
            val y1 = cell.y * cosX - cell.z * sinX
            val z1 = cell.y * sinX + cell.z * cosX

            // Rotate around Y axis
            val x2 = x1 * cosY + z1 * sinY
            val y2 = y1
            val z2 = -x1 * sinY + z1 * cosY

            // Rotate around Z axis
            val rx = x2 * cosZ - y2 * sinZ
            val ry = x2 * sinZ + y2 * cosZ
            val rz = z2

            val screenX = (rx + 1f) * 0.5f * widthPx
            val screenY = (1f - (ry + 1f) * 0.5f) * heightPx * invAspect

            if (screenX < 0f || screenX >= widthPx || screenY < 0f || screenY >= heightPx) continue

            out +=
                CanvasVoxel(
                    screenX = screenX,
                    screenY = screenY,
                    radiusPx = voxelRadiusPx * cell.scale,
                    red = cell.red,
                    green = cell.green,
                    blue = cell.blue,
                    alpha = cell.alpha ?: 1.0f,
                    z = rz,
                )
        }

        return LumosCanvasFrame(
            width = widthPx,
            height = heightPx,
            voxels = out,
            ambient = ambient,
            glyph = frame.glyph,
            tick = frame.tick,
        )
    }
}
