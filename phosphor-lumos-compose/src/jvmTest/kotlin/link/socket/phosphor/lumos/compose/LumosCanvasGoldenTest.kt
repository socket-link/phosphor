package link.socket.phosphor.lumos.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.compose.projection.ComposeLattice
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.Oscilloscope

/**
 * PHO-54: the bucketed draw path paints the same pixels as the old per-voxel `drawRect`
 * painter's loop, kept here as the reference, for the first frame of each canonical preset.
 * The reference carries PHO-61's radius floor, the one intended difference since.
 * Also prints DRAW timings for both at resolution 10 (not asserted).
 */
class LumosCanvasGoldenTest {
    @Test
    fun `new path matches the per-voxel reference for all five presets`() {
        for ((name, atmosphere) in AtmospherePresets.ALL) {
            val frame = firstFrame(atmosphere)
            val expected = render { drawReference(frame) }
            val actual = render { drawVoxels(frame, SquaresPainter()) }
            var differing = 0
            var maxDelta = 0
            for (y in 0 until PX) {
                for (x in 0 until PX) {
                    val d = delta(expected.pixel(x, y), actual.pixel(x, y))
                    if (d > 0) differing++
                    maxDelta = maxOf(maxDelta, d)
                }
            }
            println("[PHO-54] $name: $differing of ${PX * PX} pixels differ, max channel delta $maxDelta")
            assertEquals(0, differing, "$name: $differing pixels differ")
            assertTrue(frame.voxels.size > 100, "$name: too few voxels to mean anything")
        }
    }

    @Test
    fun `reports draw time before and after`() {
        val frame = firstFrame(AtmospherePresets.IDLE)
        val style = SquaresPainter()
        val canvas = bitmapAndCanvas()

        fun time(draw: DrawScope.() -> Unit): Pair<Double, Double> {
            val scope = CanvasDrawScope()
            val ms =
                (0 until 300).map {
                    val t = System.nanoTime()
                    scope.draw(Density(1f), LayoutDirection.Ltr, canvas.second, Size(PX.toFloat(), PX.toFloat()), draw)
                    (System.nanoTime() - t) / 1e6
                }.drop(100).sorted()
            return ms[ms.size / 2] to ms[ms.size * 95 / 100]
        }
        val before = time { drawReference(frame) }
        val after = time { drawVoxels(frame, style) }
        println(
            "[PHO-54] ${frame.voxels.size} voxels: before p50=${before.first} p95=${before.second} ms; " +
                "after p50=${after.first} p95=${after.second} ms",
        )
    }

    // The pre-PHO-54 loop, with the one change PHO-61 made to every style since: a cell is drawn
    // at least SurfaceStyle.MIN_RADIUS_PX across. Without it UNCERTAIN's near-carved cells differ
    // in 22 of 291,600 pixels at 540 px.
    private fun DrawScope.drawReference(frame: LumosCanvasFrame) {
        for (v in frame.voxels.sortedBy { it.z }) {
            val radiusPx = max(v.radiusPx, SurfaceStyle.MIN_RADIUS_PX)
            drawRect(
                color = Color(v.red, v.green, v.blue, v.alpha),
                topLeft = Offset(v.screenX - radiusPx, v.screenY - radiusPx),
                size = Size(radiusPx * 2f, radiusPx * 2f),
            )
        }
    }

    private fun firstFrame(atmosphere: link.socket.phosphor.signal.AtmosphereState): LumosCanvasFrame {
        val trace =
            Oscilloscope.capture(
                CaptureConfig(CaptureParams(atmosphere = atmosphere), seed = 54L, durationMs = 100L, fps = 20),
            ).getOrThrow()
        return ComposeLattice(PX, PX, voxelRadiusPx = PX / 40f).project(trace.toFrames().first())
    }

    private fun bitmapAndCanvas(): Pair<ImageBitmap, Canvas> {
        val bitmap = ImageBitmap(PX, PX)
        return bitmap to Canvas(bitmap)
    }

    private fun render(draw: DrawScope.() -> Unit): ImageBitmap {
        val (bitmap, canvas) = bitmapAndCanvas()
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(PX.toFloat(), PX.toFloat()), draw)
        return bitmap
    }

    private fun ImageBitmap.pixel(
        x: Int,
        y: Int,
    ): Int = asSkiaBitmap().getColor(x, y)

    private fun delta(
        a: Int,
        b: Int,
    ): Int = (0..24 step 8).maxOf { kotlin.math.abs(((a shr it) and 0xff) - ((b shr it) and 0xff)) }

    private companion object {
        const val PX = 540
    }
}
