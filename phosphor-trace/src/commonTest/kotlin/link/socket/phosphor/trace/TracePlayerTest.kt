package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withTimeoutOrNull
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame

/**
 * Playback semantics: how wall time maps onto recorded frames, and what
 * `play`/`pause`/`seek`/`setSegment` do to that mapping.
 *
 * Every trace here runs at 10 fps, so one recorded frame spans exactly 100 ms
 * and the arithmetic in the assertions stays readable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TracePlayerTest {
    // ---------------------------------------------------------------------
    // Time → frame mapping
    // ---------------------------------------------------------------------

    @Test
    fun oneRecordedFrameSpansOneFrameDuration() {
        val player = TracePlayer(traceOf(frameCount = 5))

        assertEquals(100.milliseconds, player.frameDuration)
    }

    @Test
    fun timeInsideAFrameWindowHoldsThatFrame() {
        val player = TracePlayer(traceOf(frameCount = 5))

        // Frame 0 owns [0, 100) ms; frame 1 owns [100, 200).
        assertEquals(0, player.frameIndexAt(0.milliseconds))
        assertEquals(0, player.frameIndexAt(1.milliseconds))
        assertEquals(0, player.frameIndexAt(50.milliseconds))
        assertEquals(0, player.frameIndexAt(99.milliseconds))
        assertEquals(1, player.frameIndexAt(100.milliseconds))
        assertEquals(1, player.frameIndexAt(150.milliseconds))
        assertEquals(1, player.frameIndexAt(199.milliseconds))
        assertEquals(2, player.frameIndexAt(200.milliseconds))
    }

    @Test
    fun holdingAFrameReturnsTheIdenticalInstance() {
        val player = TracePlayer(traceOf(frameCount = 5))

        val first = player.frameAt(0.milliseconds)
        val held = player.frameAt(50.milliseconds)
        val stillHeld = player.frameAt(99.milliseconds)

        // The allocation contract: a display refreshing faster than the trace
        // was recorded at must not cost a frame's worth of cells per refresh.
        assertSame(first, held)
        assertSame(first, stillHeld)
    }

    @Test
    fun crossingIntoTheNextFrameBuildsANewFrame() {
        val player = TracePlayer(traceOf(frameCount = 5))

        val frame0 = player.frameAt(50.milliseconds)
        val frame1 = player.frameAt(150.milliseconds)

        assertEquals(0L, frame0.tick)
        assertEquals(1L, frame1.tick)
    }

    @Test
    fun reconstructedFramesEqualTheRecordedFrames() {
        val recorded = framesOf(frameCount = 6)
        val player = TracePlayer(traceFrom(recorded))

        val replayed = List(recorded.size) { player.frameAt((it * 100).milliseconds) }

        // Proves the columnar channel slicing is faithful, cell for cell.
        assertEquals(recorded, replayed)
    }

    @Test
    fun aNullAlphaSurvivesTheRoundTrip() {
        val player = TracePlayer(traceOf(frameCount = 1))

        assertNull(player.frameAt(0.milliseconds).cells.single().alpha)
    }

    // ---------------------------------------------------------------------
    // Segments
    // ---------------------------------------------------------------------

    @Test
    fun theWholeTraceIsTheDefaultSegment() {
        val player = TracePlayer(traceOf(frameCount = 5))

        assertEquals(TracePlayer.WHOLE_TRACE_SEGMENT, player.activeSegment.name)
        assertEquals(0, player.activeSegment.startFrame)
        assertEquals(4, player.activeSegment.endFrame)
        assertFalse(player.activeSegment.loop)
        assertEquals(emptyList<String>(), player.segmentNames)
    }

    @Test
    fun aNonLoopingSegmentClampsOnItsLastFrame() {
        val player = TracePlayer(traceOf(frameCount = 5))

        assertEquals(4, player.frameIndexAt(400.milliseconds))
        assertEquals(4, player.frameIndexAt(900.milliseconds))
        assertEquals(4, player.frameIndexAt(1.seconds * 60))
    }

    @Test
    fun aLoopingSegmentWrapsWithNoIndexGapAtTheSeam() {
        val player = TracePlayer(traceOf(frameCount = 10, segments = listOf(LOOPING_IDLE)), "idle")

        // Segment "idle" covers frames 4..7 inclusive — four frames, 400 ms.
        val walked = List(12) { player.frameIndexAt((it * 100).milliseconds) }

        assertEquals(listOf(4, 5, 6, 7, 4, 5, 6, 7, 4, 5, 6, 7), walked)
    }

    @Test
    fun setSegmentSwitchesTheRangeAndRewindsThePlayhead() {
        val player = TracePlayer(traceOf(frameCount = 10, segments = listOf(LOOPING_IDLE, THINKING)))
        player.advance(250.milliseconds)
        assertEquals(2, player.currentFrameIndex)

        player.setSegment("thinking")

        assertEquals(THINKING, player.activeSegment)
        assertEquals(8, player.currentFrameIndex)
        assertEquals(0.milliseconds, player.playhead)
    }

    @Test
    fun setSegmentRejectsANameTheTraceDoesNotCarry() {
        val player = TracePlayer(traceOf(frameCount = 10, segments = listOf(LOOPING_IDLE, THINKING)))

        val error = assertFailsWith<TracePlaybackError.UnknownSegment> { player.setSegment("uncertain") }

        assertEquals("uncertain", error.name)
        assertEquals(listOf("idle", "thinking"), error.available)
    }

    @Test
    fun anUnknownInitialSegmentIsRejectedAtConstruction() {
        assertFailsWith<TracePlaybackError.UnknownSegment> {
            TracePlayer(traceOf(frameCount = 5), initialSegment = "nope")
        }
    }

    // ---------------------------------------------------------------------
    // Transport
    // ---------------------------------------------------------------------

    @Test
    fun seekLandsExactlyOnTheRequestedFrame() {
        val player = TracePlayer(traceOf(frameCount = 8))

        for (target in 0 until 8) {
            player.seek(target)
            assertEquals(target, player.currentFrameIndex)
            assertEquals(target.toLong(), player.currentFrame.tick)
        }
    }

    @Test
    fun seekClampsIntoTheActiveSegment() {
        val player = TracePlayer(traceOf(frameCount = 10, segments = listOf(LOOPING_IDLE)), "idle")

        player.seek(0)
        assertEquals(4, player.currentFrameIndex)

        player.seek(9)
        assertEquals(7, player.currentFrameIndex)
    }

    @Test
    fun seekOutsideTheTraceIsRejected() {
        val player = TracePlayer(traceOf(frameCount = 5))

        assertFailsWith<IllegalArgumentException> { player.seek(5) }
        assertFailsWith<IllegalArgumentException> { player.seek(-1) }
    }

    @Test
    fun pauseHoldsTheCurrentFrameWhileTimePasses() {
        val player = TracePlayer(traceOf(frameCount = 20))
        player.advance(250.milliseconds)
        assertEquals(2, player.currentFrameIndex)

        player.pause()
        player.advance(1.seconds)

        assertFalse(player.isPlaying)
        assertEquals(2, player.currentFrameIndex)
        assertEquals(250.milliseconds, player.playhead)
    }

    @Test
    fun playResumesFromWhereThePlayheadWasPaused() {
        val player = TracePlayer(traceOf(frameCount = 20))
        player.advance(250.milliseconds)
        player.pause()
        player.advance(1.seconds)

        player.play()
        player.advance(100.milliseconds)

        assertTrue(player.isPlaying)
        assertEquals(3, player.currentFrameIndex)
    }

    @Test
    fun advanceRejectsTimeRunningBackwards() {
        val player = TracePlayer(traceOf(frameCount = 5))

        assertFailsWith<IllegalArgumentException> { player.advance(-1.milliseconds) }
    }

    // ---------------------------------------------------------------------
    // The frame stream
    // ---------------------------------------------------------------------

    @Test
    fun framesEmitsEachRecordedFrameOnceInOrder() =
        runTest {
            val player = TracePlayer(traceOf(frameCount = 10))

            val emitted = player.frames(testTimeSource).take(5).toList()

            assertEquals(listOf(0L, 1L, 2L, 3L, 4L), emitted.map(VoxelFrame::tick))
        }

    @Test
    fun tickingFasterThanTheRecordedFpsDoesNotDuplicateFrames() =
        runTest {
            val player = TracePlayer(traceOf(frameCount = 10))

            // A 40 Hz tick over a 10 fps trace: four wake-ups per recorded frame,
            // one emission. This is the display-refresh case from the ticket.
            val emitted =
                player
                    .frames(testTimeSource, tickInterval = 25.milliseconds)
                    .take(4)
                    .toList()

            assertEquals(listOf(0L, 1L, 2L, 3L), emitted.map(VoxelFrame::tick))
        }

    @Test
    fun framesGoesQuietWhilePaused() =
        runTest {
            val player = TracePlayer(traceOf(frameCount = 10))
            player.pause()

            // The held frame arrives; a second emission never does, because a
            // paused playhead can never resolve to a different index.
            val emitted =
                withTimeoutOrNull(10.seconds) {
                    player.frames(testTimeSource).take(2).toList()
                }

            assertNull(emitted)
        }

    @Test
    fun framesEmitsTheHeldFrameOnceWhilePaused() =
        runTest {
            val player = TracePlayer(traceOf(frameCount = 10))
            player.seek(3)
            player.pause()

            val emitted = player.frames(testTimeSource).take(1).toList()

            assertEquals(listOf(3L), emitted.map(VoxelFrame::tick))
        }

    @Test
    fun framesWrapsALoopingSegmentWithoutAGap() =
        runTest {
            val player = TracePlayer(traceOf(frameCount = 10, segments = listOf(LOOPING_IDLE)), "idle")

            val emitted = player.frames(testTimeSource).take(9).toList()

            assertEquals(listOf(4L, 5L, 6L, 7L, 4L, 5L, 6L, 7L, 4L), emitted.map(VoxelFrame::tick))
        }

    @Test
    fun framesHoldsTheLastFrameOfANonLoopingSegment() =
        runTest {
            val player = TracePlayer(traceOf(frameCount = 3))

            val emitted =
                withTimeoutOrNull(10.seconds) {
                    player.frames(testTimeSource).take(4).toList()
                }

            // Three frames arrive, then the stream holds frame 2 forever.
            assertNull(emitted)
        }

    @Test
    fun framesRejectsANonPositiveTickInterval() {
        val player = TracePlayer(traceOf(frameCount = 5))

        assertFailsWith<IllegalArgumentException> { player.frames(tickInterval = 0.milliseconds) }
    }

    private companion object {
        val LOOPING_IDLE = TraceSegment(name = "idle", startFrame = 4, endFrame = 7, loop = true)
        val THINKING = TraceSegment(name = "thinking", startFrame = 8, endFrame = 9, loop = false)

        /** Frame `i` carries `tick = i` and one cell at `x = i`, so frames are identifiable. */
        fun framesOf(frameCount: Int): List<VoxelFrame> =
            List(frameCount) { index ->
                VoxelFrame(
                    tick = index.toLong(),
                    timestampEpochMillis = index * 100L,
                    resolution = 2,
                    cells =
                        listOf(
                            VoxelCell(
                                x = index.toFloat(),
                                y = 0f,
                                z = 0f,
                                scale = 1f,
                                red = 0.1f,
                                green = 0.2f,
                                blue = 0.3f,
                            ),
                        ),
                    ambient = VoxelAmbient(0.1f, 0.2f, 0.3f, 0.4f, 0f, 0f, 0f),
                )
            }

        fun traceFrom(
            frames: List<VoxelFrame>,
            segments: List<TraceSegment> = emptyList(),
        ): VoxelTrace =
            VoxelTrace.fromFrames(
                frames = frames,
                fps = 10,
                phosphorVersion = "test",
                createdAtEpochMs = 0L,
                segments = segments,
            )

        fun traceOf(
            frameCount: Int,
            segments: List<TraceSegment> = emptyList(),
        ): VoxelTrace = traceFrom(framesOf(frameCount), segments)
    }
}
