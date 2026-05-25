package link.socket.phosphor.lumos

import kotlinx.serialization.Serializable

/**
 * Canonical glyph identifiers for the Lumos voxel-orb visualization.
 *
 * Glyphs are punctuated single-shot animations, distinct from
 * [link.socket.phosphor.signal.AtmosphereState], which describes continuous
 * scene-global character. Renderers receive an active glyph via [VoxelFrame.glyph].
 */
@Serializable
enum class LumosGlyph(
    /**
     * Semantic accent color in HSL, applied to glyph-member voxels.
     *
     * Renderers convert this to sRGB at frame-build time.
     */
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
) {
    /** Completion / task done. */
    CHECK(hue = 145f, saturation = 0.65f, lightness = 0.55f),

    /** Attention required / warning. */
    EXCLAIM(hue = 32f, saturation = 0.95f, lightness = 0.55f),

    /** CHI escalation / uncertainty surface. */
    QUESTION(hue = 280f, saturation = 0.70f, lightness = 0.60f),

    /** Affirmation / preference. */
    HEART(hue = 340f, saturation = 0.80f, lightness = 0.60f),

    /** Achievement / milestone. */
    STAR(hue = 50f, saturation = 0.95f, lightness = 0.65f),

    /** Active execution / spark. */
    LIGHTNING(hue = 244f, saturation = 0.85f, lightness = 0.60f),
}
