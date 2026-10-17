package link.socket.phosphor.lumos.compose

import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat

/**
 * PHO-61: [LumosSurfaceStylePreview] composes, and every row draws something at both sizes. The
 * render is written to `build/lumos-previews/` for review.
 */
class LumosSurfaceStylePreviewTest {
    @Test
    fun `every style row draws at 200 px and at 20 px`() {
        val scene = SteppedScene(width = WIDTH, height = HEIGHT, density = Density(1f)) { LumosSurfaceStylePreview() }
        try {
            val image = scene.render()
            val bytes = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
            File("build/lumos-previews").apply { mkdirs() }.resolve("lumos-surface-style-preview.png").writeBytes(bytes)

            val pixels = image.toComposeImageBitmap().toPixelMap()
            SURFACE_PREVIEW_STYLES.forEachIndexed { row, style ->
                val top = row * (LARGE + GAP)
                SURFACE_PREVIEW_COLUMNS.indices.forEach { column ->
                    val left = column * (LARGE + GAP + SMALL + GAP)
                    assertTrue(
                        inked(pixels, left, top, LARGE, LARGE),
                        "$style drew nothing at $LARGE px in column $column",
                    )
                    assertTrue(
                        inked(pixels, left + LARGE + GAP, top + (LARGE - SMALL) / 2, SMALL, SMALL),
                        "$style drew nothing at $SMALL px in column $column",
                    )
                }
            }
        } finally {
            scene.close()
        }
    }

    private fun inked(
        pixels: androidx.compose.ui.graphics.PixelMap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): Boolean {
        for (y in top until top + height) {
            for (x in left until left + width) {
                if (pixels[x, y] != SURFACE_PREVIEW_BACKGROUND) return true
            }
        }
        return false
    }

    private companion object {
        const val LARGE = SURFACE_PREVIEW_LARGE_PX
        const val SMALL = SURFACE_PREVIEW_SMALL_PX
        const val GAP = SURFACE_PREVIEW_GAP
        val WIDTH = SURFACE_PREVIEW_COLUMNS.size * (LARGE + GAP + SMALL + GAP)
        val HEIGHT = SURFACE_PREVIEW_STYLES.size * (LARGE + GAP)
    }
}
