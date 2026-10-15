package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.palette.AtmospherePresets
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * PHO-53: [LumosOrbPreview] over time. The moving orb changes; the paused orb and the one under
 * reduced motion keep every pixel. Both moments are written to `build/lumos-previews/` for review.
 *
 * `ImageComposeScene` provides no lifecycle owner, so this also proves an orb runs in a host
 * without one rather than throwing.
 */
class LumosOrbPreviewTest {
    @Test
    fun `only the moving preview orb changes over time`() {
        val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f)) { LumosOrbPreview() }
        try {
            val clock = FrameClock(scene)
            val early = clock.renderUntil(EARLY_NANOS)
            val late = clock.renderUntil(LATE_NANOS)
            save(early, "lumos-orb-preview-0.5s.png")
            save(late, "lumos-orb-preview-3.0s.png")

            val before = early.toComposeImageBitmap().toPixelMap()
            val after = late.toComposeImageBitmap().toPixelMap()

            assertTrue(region(before, MOVING).any { it != BACKGROUND }, "the moving orb drew nothing")
            assertTrue(region(before, PAUSED).any { it != BACKGROUND }, "the paused orb drew nothing")
            assertTrue(region(before, HELD).any { it != BACKGROUND }, "the held orb drew nothing")
            assertNotEquals(region(before, MOVING), region(after, MOVING), "the moving orb stood still")
            assertEquals(region(before, PAUSED), region(after, PAUSED), "the paused orb moved")
            assertEquals(region(before, HELD), region(after, HELD), "the orb under reduced motion moved")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a tilted orb is seen from above`() {
        // Paused, so both orbs hold the same pose and the tilt is the only difference. The glyph
        // shows where the carve lands under a tilt: below the overlay drawn at the centre.
        val flat = renderPausedOrb(tiltDegrees = 0f)
        val tilted = renderPausedOrb(tiltDegrees = 20f)

        assertNotEquals(region(flat, 0, TILT_ORB, TILT_ORB), region(tilted, 0, TILT_ORB, TILT_ORB))
    }

    private fun renderPausedOrb(tiltDegrees: Float): PixelMap {
        val source = LiveFrameSource(AtmospherePresets.UNCERTAIN)
        source.builder.queueGlyph(LumosGlyph.QUESTION, durationSeconds = 60f)
        val config = LumosConfig(paused = true, tiltDegrees = tiltDegrees)
        val scene =
            ImageComposeScene(width = TILT_ORB, height = TILT_ORB, density = Density(1f)) {
                LumosOrb(source, config, Modifier.size(TILT_ORB.dp), backgroundColor = BACKGROUND)
            }
        try {
            val image = FrameClock(scene).renderUntil(EARLY_NANOS)
            save(image, "lumos-orb-tilt-${tiltDegrees.toInt()}.png")
            return image.toComposeImageBitmap().toPixelMap()
        } finally {
            scene.close()
        }
    }

    private class FrameClock(
        private val scene: ImageComposeScene,
    ) {
        private var now = 0L

        fun renderUntil(nanos: Long): Image {
            var image = scene.render(now)
            while (now < nanos) {
                now += FRAME_NANOS
                image = scene.render(now)
            }
            return image
        }
    }

    private fun region(
        pixels: PixelMap,
        left: Int,
        width: Int = ORB,
        height: Int = HEIGHT,
    ): List<Color> =
        buildList {
            for (y in 0 until height) {
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
        const val ORB = 200
        const val GAP = 16
        const val WIDTH = 3 * ORB + 2 * GAP
        const val HEIGHT = ORB

        const val MOVING = 0
        const val PAUSED = ORB + GAP
        const val HELD = 2 * (ORB + GAP)

        const val FRAME_NANOS = 33_333_333L
        const val EARLY_NANOS = 500_000_000L
        const val LATE_NANOS = 3_000_000_000L

        const val TILT_ORB = 240

        val BACKGROUND = Color(0xFF111111)
    }
}
