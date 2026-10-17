package link.socket.phosphor.lumos.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * PHO-58: [LumosLookPreview] over time. Four looks at 200 px and at 20 px; each
 * draws, each moves, and no two of the hero orbs draw the same thing. Both
 * moments are written to `build/lumos-previews/` for the visual verdict.
 *
 * Driven through [SteppedScene] for the reason [LumosOrbPreviewTest] is.
 */
class LumosLookPreviewTest {
    @Test
    fun `every look draws and moves and differs from the others`() {
        val scene = SteppedScene(width = WIDTH, height = HEIGHT, density = Density(1f)) { LumosLookPreview() }
        try {
            val early = scene.renderUntil(EARLY_NANOS)
            val late = scene.renderUntil(LATE_NANOS)
            save(early, "lumos-look-preview-0.5s.png")
            save(late, "lumos-look-preview-3.0s.png")

            val before = early.toComposeImageBitmap().toPixelMap()
            val after = late.toComposeImageBitmap().toPixelMap()

            val heroes = (0 until LOOKS).map { region(after, it * (LARGE + GAP), 0, LARGE, LARGE) }
            for (i in 0 until LOOKS) {
                assertTrue(heroes[i].any { it != BACKGROUND }, "hero orb $i drew nothing")
                val inline = region(after, i * (LARGE + GAP), LARGE + GAP, SMALL, SMALL)
                assertTrue(inline.any { it != BACKGROUND }, "inline orb $i drew nothing")
                assertNotEquals(
                    region(before, i * (LARGE + GAP), 0, LARGE, LARGE),
                    heroes[i],
                    "hero orb $i stood still",
                )
            }
            for (i in 0 until LOOKS) {
                for (j in i + 1 until LOOKS) {
                    assertNotEquals(heroes[i], heroes[j], "looks $i and $j drew the same orb")
                }
            }
        } finally {
            scene.close()
        }
    }

    private fun region(
        pixels: PixelMap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): List<Color> =
        buildList {
            for (y in top until top + height) {
                for (x in left until left + width) add(pixels[x, y])
            }
        }

    private fun save(
        image: Image,
        name: String,
    ) {
        val bytes = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
        File("build/lumos-previews").apply { mkdirs() }.resolve(name).writeBytes(bytes)
    }

    private companion object {
        const val LOOKS = 4
        const val LARGE = 200
        const val SMALL = 20
        const val GAP = 16
        const val WIDTH = LOOKS * LARGE + (LOOKS - 1) * GAP
        const val HEIGHT = LARGE + GAP + SMALL

        const val EARLY_NANOS = 500_000_000L
        const val LATE_NANOS = 3_000_000_000L

        val BACKGROUND = Color(0xFF111111)
    }
}
