package link.socket.phosphor.lumos

import kotlinx.serialization.Serializable

/**
 * Embedder-controllable knobs for [VoxelFrame] production.
 *
 * Distinct from [link.socket.phosphor.signal.AtmosphereState], which describes
 * scene-global visual character. Config here is about how the renderer
 * interprets that character — global format adjustments, optional features the
 * downstream renderer cannot honor, and cheap emission filters.
 *
 * Downstream renderers are also responsible for the light budget: per-voxel
 * sRGB output from [VoxelFrameBuilder] is in 0..1, and the consuming renderer
 * must constrain its lighting setup so that total ambient + directional
 * illumination does not exceed 1.0 to avoid color overshoot.
 *
 * @property globalYSquashOverride Multiplicative Y-axis squash applied on top
 *  of [link.socket.phosphor.signal.AtmosphereState.ySquash]. Null leaves the
 *  atmosphere's squash untouched; a non-null value multiplies further.
 * @property enableGlyphCarving When false, [VoxelFrame.glyph] is always null
 *  even if the atmosphere references a glyph. Useful for renderers that do
 *  not support glyph carving. Default true.
 * @property omitBelowScale Voxels whose final [VoxelCell.scale] falls below
 *  this threshold are omitted from the output frame entirely. Default 0
 *  emits all voxels regardless of scale.
 */
@Serializable
data class LumosRenderConfig(
    val globalYSquashOverride: Float? = null,
    val enableGlyphCarving: Boolean = true,
    val omitBelowScale: Float = 0.0f,
)
