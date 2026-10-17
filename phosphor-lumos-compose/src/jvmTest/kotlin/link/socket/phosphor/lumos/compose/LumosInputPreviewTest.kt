package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.LumosInput
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * PHO-83: [LumosInputPreview] — a settled THINKING orb with `interactive`, alone, pointed at the
 * centre and pointed upper right — written to `build/lumos-previews/lumos-input-strip.png`; and the
 * desktop recording: a scripted pointer path driven through [LumosOrb] with real pointer events on
 * desktop Skia, about three seconds at 30 fps, written to `build/lumos-previews/lumos-input-repel.gif`.
 *
 * Both go through [SteppedScene], so the orbs' effects run on the test thread.
 */
class LumosInputPreviewTest {
    @Test
    fun `the strip shows the orb alone and parted around two pointers`() {
        val scene = SteppedScene(width = STRIP_WIDTH, height = STRIP_HEIGHT) { LumosInputPreview() }
        try {
            val image = scene.renderUntil(STRIP_NANOS)
            save(image, "lumos-input-strip.png")
            val pixels = image.toComposeImageBitmap().toPixelMap()

            val alone = region(pixels, 0)
            val centre = region(pixels, ORB + GAP)
            val upperRight = region(pixels, 2 * (ORB + GAP))
            assertTrue(alone.any { it != BACKGROUND }, "the orb alone drew nothing")
            assertTrue(centre.any { it != BACKGROUND }, "the orb pointed at the centre drew nothing")
            assertTrue(upperRight.any { it != BACKGROUND }, "the orb pointed upper right drew nothing")
            assertNotEquals(alone, centre, "a pointer at the centre changed nothing")
            assertNotEquals(alone, upperRight, "a pointer upper right changed nothing")
            assertNotEquals(centre, upperRight, "the two pointers part the cells the same way")

            // Under the pointer the cells have parted: less light around it than in the orb alone.
            // The halo is the same in both, so it cancels out of the comparison.
            val aloneLight = light(pixels, 0, CENTRE_BOX)
            val centreLight = light(pixels, ORB + GAP, CENTRE_BOX)
            println("[PHO-83] light in the centre box: alone $aloneLight, pointed $centreLight")
            assertTrue(centreLight < aloneLight, "the cells did not part: $centreLight vs $aloneLight")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `records the cells parting around a pointer that sweeps across the orb`() {
        val live = LiveFrameSource(AtmospherePresets.THINKING, LumosRenderConfig(interactive = true))
        live.next(WARM_UP_SECONDS)
        val source = RecordingSource(live)
        var marker: Offset? by mutableStateOf(null)
        val scene =
            SteppedScene(width = CLIP, height = CLIP, density = Density(1f)) {
                val config = remember { LumosConfig(label = "Lumos, pointed at") }
                Box(Modifier.size(CLIP.dp)) {
                    LumosOrb(source, config, Modifier.size(CLIP.dp), backgroundColor = BACKGROUND)
                    Canvas(Modifier.size(CLIP.dp)) {
                        marker?.let { drawCircle(MARKER, radius = MARKER_RADIUS, center = it, style = Stroke(2f)) }
                    }
                }
            }
        val file = File("build/lumos-previews/lumos-input-repel.gif")
        // The orb's loop draws its first frame on the frame after it is laid out.
        scene.render()
        var image = scene.renderNextFrame()
        var changed = 0
        var previous = image.toComposeImageBitmap().toPixelMap().toList()
        GifWriter(file, FRAME_MILLIS).use { gif ->
            try {
                for (frame in 0 until CLIP_FRAMES) {
                    val at = pointerAt(frame)
                    when {
                        frame == ENTER_FRAME -> scene.sendPointerEvent(PointerEventType.Enter, checkNotNull(at))
                        frame == EXIT_FRAME -> scene.sendPointerEvent(PointerEventType.Exit, Offset(-1f, -1f))
                        at != null -> scene.sendPointerEvent(PointerEventType.Move, at)
                    }
                    marker = at
                    Snapshot.sendApplyNotifications()
                    image = scene.renderNextFrame()
                    gif.write(image)
                    val pixels = image.toComposeImageBitmap().toPixelMap().toList()
                    if (pixels != previous) changed++
                    previous = pixels
                }
            } finally {
                scene.close()
            }
            println("[PHO-83] recorded ${gif.frameCount} frames to $file; ${source.inputs.size} inputs")
        }

        // Breath and spin move the orb most frames; a step too small to move a pixel is allowed now and then.
        assertTrue(changed >= CLIP_FRAMES * 3 / 4, "the orb stood still on most frames: $changed of $CLIP_FRAMES moved")
        assertTrue(source.inputs.count { it.pointer != null } >= SWEEP_FRAMES, "the sweep was not reported")
        assertEquals(LumosInput.NONE, source.inputs.last(), "the exit was not reported")
        assertTrue(file.length() > 0, "the GIF is empty")
    }

    // The scripted path: in from the left, across the centre, a loop upper right, and out.
    private fun pointerAt(frame: Int): Offset? {
        if (frame < ENTER_FRAME || frame >= EXIT_FRAME) return null
        val sweepEnd = ENTER_FRAME + SWEEP_FRAMES
        val holdEnd = sweepEnd + HOLD_FRAMES
        return when {
            frame < sweepEnd -> {
                val t = (frame - ENTER_FRAME).toFloat() / SWEEP_FRAMES
                Offset(CLIP * (0.1f + 0.4f * t), CLIP * 0.5f)
            }
            frame < holdEnd -> Offset(CLIP * 0.5f, CLIP * 0.5f)
            else -> {
                val t = (frame - holdEnd).toFloat() / (EXIT_FRAME - holdEnd)
                val angle = PI.toFloat() * (1f - t)
                Offset(CLIP * (0.5f + 0.25f * (1f - cos(angle)) / 2f + 0.25f * t), CLIP * (0.5f - 0.3f * sin(angle)))
            }
        }
    }

    private fun region(
        pixels: PixelMap,
        left: Int,
    ): List<Color> =
        buildList {
            for (y in 0 until STRIP_HEIGHT) for (x in left until left + ORB) add(pixels[x, y])
        }

    // Summed channel light inside a box, left/top/right/bottom relative to an orb at [left].
    private fun light(
        pixels: PixelMap,
        left: Int,
        box: IntArray,
    ): Float {
        var sum = 0f
        for (y in box[1] until box[3]) for (x in left + box[0] until left + box[2]) {
            val pixel = pixels[x, y]
            sum += pixel.red + pixel.green + pixel.blue
        }
        return sum
    }

    private fun PixelMap.toList(): List<Color> =
        buildList {
            for (y in 0 until height) for (x in 0 until width) add(this@toList[x, y])
        }

    private fun save(
        image: Image,
        name: String,
    ) {
        val bytes = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
        File("build/lumos-previews").apply { mkdirs() }.resolve(name).writeBytes(bytes)
    }

    private class RecordingSource(
        private val live: LiveFrameSource,
    ) : LumosFrameSource by live {
        val inputs = mutableListOf<LumosInput>()

        override fun setInput(input: LumosInput) {
            inputs += input
            live.setInput(input)
        }
    }

    private companion object {
        const val ORB = 200
        const val GAP = 16
        const val STRIP_WIDTH = 3 * ORB + 2 * GAP
        const val STRIP_HEIGHT = ORB
        const val STRIP_NANOS = 500_000_000L

        // A box around the centre of an orb, half the repel's radius (0.32 of the orb's) either side.
        const val HOLE = 16
        val CENTRE_BOX = intArrayOf(ORB / 2 - HOLE, ORB / 2 - HOLE, ORB / 2 + HOLE, ORB / 2 + HOLE)

        const val CLIP = 240
        const val FRAME_MILLIS = 33
        const val CLIP_FRAMES = 90
        const val ENTER_FRAME = 8
        const val SWEEP_FRAMES = 24
        const val HOLD_FRAMES = 14
        const val EXIT_FRAME = 76
        const val WARM_UP_SECONDS = 1.5f
        const val MARKER_RADIUS = 5f

        val BACKGROUND = INPUT_PREVIEW_BACKGROUND
        val MARKER = Color.White
    }
}
