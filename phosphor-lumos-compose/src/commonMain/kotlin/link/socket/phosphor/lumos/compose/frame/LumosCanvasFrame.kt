package link.socket.phosphor.lumos.compose.frame

import kotlinx.serialization.Serializable
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelGlyphState

/**
 * Serializable 2D projection of a [VoxelFrame] shaped for Compose Canvas rendering.
 *
 * Differs from LumosTerminalFrame in coordinate system (pixel-based, Compose-friendly)
 * and color representation (sRGB, not OKLab). Renderers can paint [voxels] directly
 * using Compose's `Canvas.drawRect` with sRGB color values.
 *
 * @property width Pixel width of the canvas the projector was sized for.
 * @property height Pixel height of the canvas the projector was sized for.
 * @property voxels Visible voxels only (zero-scale cells dropped during projection).
 * @property ambient Per-frame parameters passed through from the source [VoxelFrame]
 *  for halo and glow rendering.
 * @property glyph Active glyph state, if any. Passed through for glyph-aware overlay rendering.
 * @property tick Monotonic frame counter passed through from source frame.
 */
@Serializable
data class LumosCanvasFrame(
    val width: Int,
    val height: Int,
    val voxels: List<CanvasVoxel>,
    val ambient: VoxelAmbient,
    val glyph: VoxelGlyphState? = null,
    val tick: Long,
) {
    /**
     * Projected 2D voxel with rendering parameters suitable for Compose Canvas.
     *
     * Pixel coordinates are already aspect-corrected and camera-projected; consumers
     * paint directly at (screenX, screenY) with radius radiusPx.
     *
     * @property screenX X position in canvas pixels (0..width).
     * @property screenY Y position in canvas pixels (0..height). Y grows downward
     *  following Compose Canvas convention.
     * @property radiusPx Half the rect side length in pixels, derived from voxel scale
     *  and canvas density.
     * @property red sRGB red channel, 0..1, ready for androidx.compose.ui.graphics.Color(red, green, blue).
     * @property green sRGB green channel, 0..1.
     * @property blue sRGB blue channel, 0..1.
     * @property alpha sRGB alpha channel, 0..1. Defaults to 1.0 if source voxel had null alpha.
     * @property z Z-depth preserved for painter's-algorithm back-to-front compositing.
     */
    @Serializable
    data class CanvasVoxel(
        val screenX: Float,
        val screenY: Float,
        val radiusPx: Float,
        val red: Float,
        val green: Float,
        val blue: Float,
        val alpha: Float,
        val z: Float,
    )
}
