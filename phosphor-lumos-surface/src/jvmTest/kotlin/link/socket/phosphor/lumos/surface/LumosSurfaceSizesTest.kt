package link.socket.phosphor.lumos.surface

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.PixelForm
import link.socket.phosphor.lumos.RectPx
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.compose.drawVoxels
import link.socket.phosphor.lumos.compose.painter
import link.socket.phosphor.lumos.frame.LumosCanvasFrame
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * PHO-76: the surface at Socket's two sizes. The Turn-on moment is recorded at 30 fps for the
 * 160 px hero orb (resolution 10, 4,801 cells) with every cell kept and with the surplus faded,
 * and for the 20 px inline orb (80 Fibonacci cells), each as Dots and as Squares, into
 * `build/lumos-surface-sizes/<clip>/frame-NNN.png`. Each clip must settle solid with the slot
 * empty. The overlay's draw time at 4,801 cells, mid-flight, is printed for the record.
 */
class LumosSurfaceSizesTest {
    @Test
    fun `the hero orb assembles with every cell kept`() {
        for (style in STYLES) {
            val clip = record("hero-dense-${style.name()}", heroSurfaceState(style), HERO_ORB_SIZE, HERO_ORB_TOP)
            assertEquals(Forms.Lattice(HERO_RESOLUTION).count, clip.flightCounts.single(), "every cell flies")
        }
    }

    @Test
    fun `the hero orb assembles with the surplus faded`() {
        val keep = SurfaceSurplus.Fade().keep(BUTTON_RECT, Forms.Lattice(HERO_RESOLUTION).count)
        for (style in STYLES) {
            val clip =
                record(
                    "hero-fade-${style.name()}",
                    heroSurfaceState(style),
                    HERO_ORB_SIZE,
                    HERO_ORB_TOP,
                    surplus = SurfaceSurplus.Fade(),
                )
            assertEquals(
                Forms.Lattice(HERO_RESOLUTION).count,
                clip.flightCounts.first(),
                "the surplus flies while it fades",
            )
            assertEquals(keep, clip.flightCounts.last(), "only the kept cells reach the button")
        }
    }

    @Test
    fun `the inline orb assembles with its outline thickened`() {
        assertTrue(PixelForm.outlineFraction(BUTTON_RECT, INLINE_COUNT) > 0.5f)
        for (style in STYLES) {
            val clip = record("inline-${style.name()}", inlineSurfaceState(style), INLINE_ORB_SIZE, INLINE_ORB_TOP)
            assertEquals(listOf(INLINE_COUNT), clip.flightCounts, "the inline orb's 80 cells fly")
        }
        // For comparison: Dots' 18 % share, 14 cells round a 322 px outline.
        val thin =
            inlineSurfaceState(SurfaceStyle.Dots).also {
                it.outlineFraction = {
                        _,
                        _,
                    ->
                    PixelForm.PERIMETER_FRACTION
                }
            }
        record("inline-dots-outline-18", thin, INLINE_ORB_SIZE, INLINE_ORB_TOP)
    }

    @Test
    fun `reports the overlay's draw time at 4801 cells`() {
        for (style in STYLES) {
            for (surplus in listOf(SurfaceSurplus.Keep, SurfaceSurplus.Fade())) {
                val state = heroSurfaceState(style)
                val clip = record(null, state, HERO_ORB_SIZE, HERO_ORB_TOP, surplus = surplus)
                val (frame, arrival) = clip.midFlight
                val painter = HandOffPainter(state.config.material.painter(style.painter()))
                painter.arrival = arrival
                painter.tint = PREVIEW_ACCENT
                val bitmap = ImageBitmap(WIDTH, HEIGHT)
                val canvas = Canvas(bitmap)
                val scope = CanvasDrawScope()
                val ms =
                    (0 until BENCH_ITERATIONS).map {
                        val t = System.nanoTime()
                        scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(WIDTH.toFloat(), HEIGHT.toFloat())) {
                            drawVoxels(frame, painter)
                        }
                        (System.nanoTime() - t) / 1e6
                    }.drop(BENCH_WARM_UP).sorted()
                val name = if (surplus is SurfaceSurplus.Fade) "fade" else "dense"
                println(
                    "[PHO-76] overlay ${style.name()} $name: ${frame.voxels.size} voxels at ${WIDTH}x$HEIGHT, " +
                        "arrival ${"%.2f".format(arrival)}, " +
                        "DRAW p50=${"%.2f".format(ms[ms.size / 2])} ms p95=${"%.2f".format(ms[ms.size * 95 / 100])} ms",
                )
            }
        }
    }

    private class Clip(
        val flightCounts: List<Int>,
        val midFlight: Pair<LumosCanvasFrame, Float>,
    )

    // Record one Turn-on into build/lumos-surface-sizes/<name>, or nowhere when name is null, and
    // check that it settles solid with the slot empty and the button drawn.
    private fun record(
        name: String?,
        state: LumosSurfaceState,
        orbSize: Dp,
        orbTop: Dp,
        surplus: SurfaceSurplus = SurfaceSurplus.Keep,
    ): Clip {
        val dir =
            name?.let {
                File("build/lumos-surface-sizes/$it").apply {
                    mkdirs()
                    listFiles()?.forEach(File::delete)
                }
            }
        val scene =
            SteppedScene(
                WIDTH,
                HEIGHT,
            ) { LumosSurfacePreview(state, orbSize = orbSize, orbTop = orbTop, surplus = surplus) }
        try {
            var index = 0
            var image = scene.render()
            val counts = mutableListOf<Int>()
            var midFlight: Pair<LumosCanvasFrame, Float>? = null
            var solidAtNanos = -1L
            var blank = 0
            var lastInk: FloatArray? = null
            var largestShift = 0f
            while (scene.nowNanos < RECORD_NANOS) {
                dir?.resolve("frame-%03d.png".format(index++))?.writeBytes(png(image))
                val ink = meanInk(image)
                // Blank once the orb has drawn: the frames before its first step are empty by design.
                if (ink == null && lastInk != null) blank++
                // The mean colour of what is drawn, frame to frame, from the orb until the hand-off
                // begins: the cells keep their colours as they leave, and the tint comes in with arrival.
                val last = lastInk
                if (ink != null && last != null && state.arrival == 0f) {
                    largestShift = maxOf(largestShift, (0..2).maxOf { abs(ink[it] - last[it]) })
                }
                lastInk = ink
                when (state.phase) {
                    is SurfacePhase.Assembling ->
                        state.canvasFrame?.let { frame ->
                            // The builder's count, not the frame's: a cell the burst throws off the surface is culled.
                            val count = state.source.builder.currentCount
                            if (counts.lastOrNull() != count) counts += count
                            if (midFlight == null && state.arrival >= MID_FLIGHT) midFlight = frame to state.arrival
                        }
                    is SurfacePhase.Solid -> if (solidAtNanos < 0) solidAtNanos = scene.nowNanos
                    SurfacePhase.Orb -> Unit
                }
                image = scene.renderNextFrame()
            }
            println(
                "[PHO-76] ${name ?: "bench"}: $index frames, flight counts $counts, " +
                    "solid at ${solidAtNanos / 1e9} s, " +
                    "largest colour shift before hand-off ${"%.3f".format(largestShift)}",
            )

            assertEquals(0, blank, "${name ?: "bench"} drew $blank blank frames")
            assertTrue(largestShift < MAX_COLOUR_SHIFT, "the colour jumped by $largestShift in one frame")

            assertIs<SurfacePhase.Solid>(state.phase, "${name ?: "bench"} should settle within ${RECORD_NANOS / 1e9} s")
            assertTrue(counts.isNotEmpty(), "the overlay never drew a frame in flight")
            val settled = image.toComposeImageBitmap().toPixelMap()
            assertTrue(region(settled, BUTTON).any { it != PREVIEW_BACKGROUND }, "the solid button drew nothing")
            assertTrue(
                region(settled, slot(orbSize, orbTop)).all { it == PREVIEW_BACKGROUND },
                "the slot is not empty once solid",
            )
            return Clip(counts, checkNotNull(midFlight) { "arrival never reached $MID_FLIGHT" })
        } finally {
            scene.close()
        }
    }

    // The mean colour, 0..1 per channel, of the pixels that are not background; null when there are none.
    private fun meanInk(image: Image): FloatArray? {
        val pixels = image.toComposeImageBitmap().toPixelMap()
        var n = 0
        val sum = FloatArray(3)
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                if (c == PREVIEW_BACKGROUND) continue
                sum[0] += c.red
                sum[1] += c.green
                sum[2] += c.blue
                n++
            }
        }
        return if (n == 0) null else FloatArray(3) { sum[it] / n }
    }

    private fun png(image: Image): ByteArray = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes

    private fun slot(
        size: Dp,
        top: Dp,
    ): IntArray {
        val side = size.value.toInt()
        val left = (WIDTH - side) / 2
        return intArrayOf(left, top.value.toInt(), left + side, top.value.toInt() + side)
    }

    private fun region(
        pixels: PixelMap,
        box: IntArray,
    ): List<Color> {
        val (left, top, right, bottom) = box
        return buildList {
            for (y in top until bottom) for (x in left until right) add(pixels[x, y])
        }
    }

    private fun SurfaceStyle.name(): String = this::class.simpleName!!.lowercase()

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 260
        const val RECORD_NANOS = 3_600_000_000L
        const val MID_FLIGHT = 0.5f

        // The largest change in the drawn mean colour between two frames before the hand-off. The
        // colour cut this guards against moved it by about 0.2 in one frame.
        const val MAX_COLOUR_SHIFT = 0.12f
        const val INLINE_COUNT = 80
        const val BENCH_ITERATIONS = 220
        const val BENCH_WARM_UP = 20

        val STYLES = listOf(SurfaceStyle.Dots, SurfaceStyle.Squares)

        // The button, left/top/right/bottom, and as the rect the surface measures.
        val BUTTON = intArrayOf(248, 196, 376, 236)
        val BUTTON_RECT = RectPx(248f, 196f, 128f, 40f, PREVIEW_CORNER_PX)
    }
}
