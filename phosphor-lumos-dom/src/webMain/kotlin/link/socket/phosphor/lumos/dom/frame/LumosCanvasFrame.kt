package link.socket.phosphor.lumos.dom.frame

// PHO-69 spike: verbatim copy of phosphor-lumos-compose's LumosCanvasFrame minus @Serializable. Depending on
// the Compose module for it pulled Skiko's `./skiko.mjs` import into the wasm link (webpack: Module not found),
// so the real change lifts this DTO and ComposeLattice into :phosphor-lumos (or a :phosphor-lumos-frame module).

import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelGlyphState

/**
 * Serializable 2D projection of a VoxelFrame shaped for Compose Canvas rendering.
 *
 * Differs from LumosTerminalFrame in coordinate system (pixel-based, Compose-friendly)
 * and color representation (sRGB, not OKLab). Renderers can paint [voxels] directly
 * using Compose's `Canvas.drawRect` with sRGB color values.
 *
 * @property width Pixel width of the canvas the projector was sized for.
 * @property height Pixel height of the canvas the projector was sized for.
 * @property voxels Visible voxels only (zero-scale cells dropped during projection).
 * @property ambient Per-frame parameters passed through from the source VoxelFrame
 *  for halo and glow rendering.
 * @property glyph Active glyph state, if any. Passed through for glyph-aware overlay rendering.
 * @property tick Monotonic frame counter passed through from source frame.
 */
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
     * @property dx The screen-space direction the orb's spin carries this voxel, per radian of
     *  yaw, in canvas orientation: Thinking Orbs' `sx = z1`, the voxel's rotated `z`. A dash
     *  style strokes along `(dx, dy)`. 0 for a frame built before this field existed.
     * @property dy The screen-space direction the spin carries this voxel, canvas Y down: the
     *  rotated `x` times the sine of the tilt, negated for the Y flip; 0 when the view is untilted.
     */

    data class CanvasVoxel(
        val screenX: Float,
        val screenY: Float,
        val radiusPx: Float,
        val red: Float,
        val green: Float,
        val blue: Float,
        val alpha: Float,
        val z: Float,
        val dx: Float = 0f,
        val dy: Float = 0f,
    )
}
