package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneConfiguration

/**
 * The seam itself: both arms open as a frame stream through one factory, and
 * each hands back the handle that drives it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LumosSourceTest {
    @Test
    fun bothArmsOpenAsAFrameStreamThroughOneFactory() =
        runTest {
            val trace = Oscilloscope.capture(captureConfig()).getOrThrow()

            // The one-line swap the seam exists for.
            val live: LumosSource = LumosSource.Live(configuration = scene(), fps = FPS)
            val replay: LumosSource = LumosSource.Trace(trace)

            assertEquals(4, live.frames(testTimeSource).take(4).toList().size)
            assertEquals(4, replay.frames(testTimeSource).take(4).toList().size)
        }

    @Test
    fun aTraceArmStartsOnTheSegmentItWasGiven() {
        val trace = Oscilloscope.capture(captureConfig(segments = listOf(THINKING))).getOrThrow()

        val source = LumosSource.Trace(trace, initialSegment = "thinking")

        assertEquals(THINKING, source.player.activeSegment)
        assertEquals(THINKING.startFrame, source.player.currentFrameIndex)
    }

    @Test
    fun aTraceArmWithoutASegmentPlaysTheWholeRecording() {
        val trace = Oscilloscope.capture(captureConfig()).getOrThrow()

        val source = LumosSource.Trace(trace)

        assertEquals(TracePlayer.WHOLE_TRACE_SEGMENT, source.player.activeSegment.name)
        assertEquals(trace.frameCount - 1, source.player.activeSegment.endFrame)
    }

    @Test
    fun aTraceArmRejectsASegmentTheRecordingDoesNotCarry() {
        val trace = Oscilloscope.capture(captureConfig()).getOrThrow()

        assertFailsWith<TracePlaybackError.UnknownSegment> {
            LumosSource.Trace(trace, initialSegment = "thinking")
        }
    }

    @Test
    fun aTraceArmExposesItsTransport() =
        runTest {
            val trace = Oscilloscope.capture(captureConfig()).getOrThrow()
            val source = LumosSource.Trace(trace)

            source.player.seek(5)
            source.player.pause()

            val held = source.frames(testTimeSource).take(1).toList().single()

            assertTrue(trace.frameCount > 5, "the fixture needs more than 5 frames to seek into")
            assertEquals(5, source.player.currentFrameIndex)
            // A frame's tick is the runtime's frame counter, which has already
            // advanced once by the time the first frame is built — so it runs one
            // ahead of the trace index. Assert against what was recorded.
            assertEquals(trace.ticks[5], held.tick)
        }

    @Test
    fun aLiveArmKeepsTheFpsItWasGiven() {
        val source = LumosSource.Live(configuration = scene(), fps = 24)

        assertEquals(24, source.generator.fps)
    }

    @Test
    fun aLiveArmRejectsASceneWithAtmosphereDisabled() {
        assertFailsWith<IllegalArgumentException> {
            LumosSource.Live(configuration = SceneConfiguration(width = 8, height = 8))
        }
    }

    @Test
    fun aGlyphFiredOnALiveArmReachesTheFrames() =
        runTest {
            val source = LumosSource.Live(configuration = scene(), fps = FPS)
            source.generator.queueGlyph(LumosGlyph.CHECK, durationSeconds = 1f)

            val burning = source.frames(testTimeSource).take(4).toList()

            assertTrue(burning.all { it.glyph != null }, "a queued glyph should carve every frame it spans")
            assertEquals("CHECK", assertNotNull(burning.first().glyph).glyphName)
        }

    @Test
    fun aGlyphIsNotBakedIntoAnUncuedCapture() {
        val trace = Oscilloscope.capture(captureConfig()).getOrThrow()

        // Nothing queued a glyph during capture, so a replay carries none —
        // which is precisely why a consumer overlays one live on top.
        assertTrue(trace.glyphs.all { it == null })
        assertNull(TracePlayer(trace).currentFrame.glyph)
    }

    @Test
    fun aLiveStreamAdvancesTheSimulation() =
        runTest {
            val source = LumosSource.Live(configuration = scene(), fps = FPS)

            val emitted = source.frames(testTimeSource).take(5).toList()

            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), emitted.map(VoxelFrame::tick))
        }

    private companion object {
        const val FPS = 30
        val THINKING = TraceSegment(name = "thinking", startFrame = 3, endFrame = 9, loop = true)

        fun captureConfig(segments: List<TraceSegment> = emptyList()): CaptureConfig =
            CaptureConfig(
                params = CaptureParams(atmosphere = AtmospherePresets.THINKING),
                seed = 7L,
                durationMs = 500L,
                fps = FPS,
                segments = segments,
            )

        fun scene(): SceneConfiguration =
            SceneConfiguration(
                width = 8,
                height = 8,
                enableAtmosphere = true,
                initialAtmosphere = AtmospherePresets.THINKING,
                seed = 7L,
            )
    }
}
