package link.socket.phosphor.lumos.compose

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.GlyphEnvelope
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelGlyphState
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame

/**
 * PHO-70: the glyph overlay must fade on [GlyphEnvelope], the same envelope the carve uses,
 * not on raw [VoxelGlyphState.progress].
 *
 * Paints the canvas into an [ImageBitmap] with no halo, no voxels and a transparent
 * background, so the alpha of the pixel at the centre of a filled STAR is exactly the
 * overlay's alpha.
 */
class LumosCanvasGlyphFadeTest {
    @Test
    fun `overlay alpha late in the window follows the envelope, not raw progress`() {
        val alpha = centreAlpha(progress = 0.9f)

        assertEquals(GlyphEnvelope.visibility(0.9f), alpha, ALPHA_TOLERANCE)
    }

    @Test
    fun `overlay has almost faded out on the last frame of the window`() {
        val alpha = centreAlpha(progress = 0.99f)

        assertEquals(GlyphEnvelope.visibility(0.99f), alpha, ALPHA_TOLERANCE)
        assertTrue(alpha < 0.02f, "last-frame overlay alpha should be near zero, was $alpha")
    }

    @Test
    fun `overlay alpha early in the window follows the envelope`() {
        assertEquals(GlyphEnvelope.visibility(0.1f), centreAlpha(progress = 0.1f), ALPHA_TOLERANCE)
    }

    @Test
    fun `overlay is fully opaque through the hold`() {
        assertEquals(1f, centreAlpha(progress = 0.5f), ALPHA_TOLERANCE)
    }

    @Test
    fun `nothing is painted at the ends of the window`() {
        assertEquals(0f, centreAlpha(progress = 0f))
        assertEquals(0f, centreAlpha(progress = 1f))
    }

    private fun centreAlpha(progress: Float): Float {
        val frame =
            LumosCanvasFrame(
                width = SIZE,
                height = SIZE,
                voxels = emptyList(),
                ambient = ambient,
                glyph = VoxelGlyphState("STAR", progress = progress, red = 1f, green = 1f, blue = 1f),
                tick = 0L,
            )
        val bitmap = ImageBitmap(SIZE, SIZE)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(SIZE.toFloat(), SIZE.toFloat()),
        ) {
            drawLumosFrame(frame, showHalo = false)
        }
        val pixels = IntArray(SIZE * SIZE)
        bitmap.readPixels(pixels)
        val argb = pixels[(SIZE / 2) * SIZE + SIZE / 2]
        return ((argb ushr 24) and 0xFF) / 255f
    }

    private companion object {
        const val SIZE = 200

        // One 8-bit step plus rounding headroom.
        const val ALPHA_TOLERANCE = 1.5f / 255f

        val ambient =
            VoxelAmbient(
                glowRed = 0.2f,
                glowGreen = 0.3f,
                glowBlue = 0.9f,
                glowIntensity = 0.8f,
                orbRotationX = 0f,
                orbRotationY = 0f,
                orbRotationZ = 0f,
            )
    }
}
