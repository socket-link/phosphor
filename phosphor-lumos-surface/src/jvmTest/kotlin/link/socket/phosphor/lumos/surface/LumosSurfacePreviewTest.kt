package link.socket.phosphor.lumos.surface

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * PHO-75: the Turn-on moment, recorded. [LumosSurfacePreview] is rendered at 30 fps into
 * `build/lumos-surface/frame-NNN.png` for review; the test asserts what the clip should show —
 * the orb in its slot, then cells in flight over a rising control, then a solid control with the
 * slot empty — and that nothing changes once the control is solid.
 */
class LumosSurfacePreviewTest {
    @Test
    fun `records the orb assembling into the Turn on button and then holds still`() {
        val state = previewSurfaceState()
        val dir =
            File("build/lumos-surface").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() }
            }
        val scene = SteppedScene(WIDTH, HEIGHT) { LumosSurfacePreview(state) }
        try {
            var index = 0
            var image = scene.render()
            var sawFlight = false
            var solidAtNanos = -1L
            while (scene.nowNanos < RECORD_NANOS) {
                dir.resolve("frame-%03d.png".format(index++)).writeBytes(png(image))
                when (val phase = state.phase) {
                    is SurfacePhase.Assembling -> if (state.canvasFrame != null) sawFlight = true
                    is SurfacePhase.Solid -> if (solidAtNanos < 0) solidAtNanos = scene.nowNanos
                    SurfacePhase.Orb -> Unit
                }
                image = scene.renderNextFrame()
            }
            println("[PHO-75] recorded $index frames; phase=${state.phase} solid at ${solidAtNanos / 1e9} s")

            assertIs<SurfacePhase.Solid>(state.phase, "the assembly should settle within ${RECORD_NANOS / 1e9} s")
            assertTrue(sawFlight, "the overlay never drew a frame in flight")
            assertEquals(1f, state.alphaFor(PREVIEW_TARGET))
            assertTrue(solidAtNanos > ASSEMBLE_AT_NANOS, "the control went solid before the flight began")

            val settled = image.toComposeImageBitmap().toPixelMap()
            assertTrue(region(settled, BUTTON).any { it != BACKGROUND }, "the solid button drew nothing")
            assertTrue(region(settled, SLOT).all { it == BACKGROUND }, "the orb's slot is not empty once solid")

            // Nothing ticks once solid: ten more frames change no pixel.
            val later = (1..10).fold(image) { _, _ -> scene.renderNextFrame() }
            val stillLater = later.toComposeImageBitmap().toPixelMap().toList()
            assertEquals(settled.toList(), stillLater, "the surface kept moving once solid")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `before the assembly the orb draws in its slot and the button is invisible`() {
        val state = previewSurfaceState()
        val scene = SteppedScene(WIDTH, HEIGHT) { LumosSurfacePreview(state) }
        try {
            var image = scene.render()
            while (scene.nowNanos < EARLY_NANOS) image = scene.renderNextFrame()
            val pixels = image.toComposeImageBitmap().toPixelMap()

            assertEquals(SurfacePhase.Orb, state.phase)
            assertTrue(region(pixels, SLOT).any { it != BACKGROUND }, "the slot's orb drew nothing")
            assertTrue(region(pixels, BUTTON).all { it == BACKGROUND }, "the button showed before the cells reached it")
        } finally {
            scene.close()
        }
    }

    private fun png(image: Image): ByteArray = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes

    private fun region(
        pixels: PixelMap,
        box: IntArray,
    ): List<Color> {
        val (left, top, right, bottom) = box
        return buildList {
            for (y in top until bottom) for (x in left until right) add(pixels[x, y])
        }
    }

    private fun PixelMap.toList(): List<Color> = region(this, intArrayOf(0, 0, width, height))

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 260
        const val RECORD_NANOS = 3_600_000_000L
        const val EARLY_NANOS = 500_000_000L
        const val ASSEMBLE_AT_NANOS = 800_000_000L

        val BACKGROUND = PREVIEW_BACKGROUND

        // Pixel boxes, left/top/right/bottom: the slot (centre 200, 100, radius 64) and the button.
        val SLOT = intArrayOf(136, 36, 264, 164)
        val BUTTON = intArrayOf(248, 196, 376, 236)
    }
}
