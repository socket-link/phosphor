package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneConfiguration

/**
 * The keystone: a recording replays into the frames it was recorded from.
 *
 * Capture a trace at seed S, run a live simulation at seed S and the same fixed
 * timestep, and compare the two streams frame by frame. Equality here means a
 * consumer can swap `LumosSource.Live` for `LumosSource.Trace` and see
 * pixel-identical output, because projection and drawing happen downstream of
 * the thing being compared.
 *
 * Note what the live side is *not*: it is not the headless configuration
 * [Oscilloscope] records with. It runs an 80×24 substrate with particles, flow,
 * waveform, emitters, and camera all on — every subsystem the oscilloscope
 * switches off to keep capture cheap. The frames match anyway, because a
 * `VoxelFrame` is built from the atmosphere subsystem and the frame counters
 * alone. That is the invariant this test pins down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TraceEquivalenceTest {
    @Test
    fun aReplayedTraceEqualsTheLiveSimulationFrameByFrame() =
        runTest {
            val trace = Oscilloscope.capture(captureConfig()).getOrThrow()

            val lived =
                LumosSource
                    .Live(configuration = liveScene(), fps = FPS)
                    .frames(testTimeSource)
                    .take(trace.frameCount)
                    .toList()

            val replayed =
                LumosSource
                    .Trace(trace)
                    .frames(testTimeSource)
                    .take(trace.frameCount)
                    .toList()

            assertEquals(trace.frameCount, lived.size)
            assertEquals(lived, replayed)
        }

    @Test
    fun aReplayedTraceEqualsALiveSimulationThroughAnAtmosphereTransition() {
        // Cues fire immediately before the step that produces their frame, so the
        // live side applies the same change at the same frame index to match.
        val cue = AtmosphereCue(atFrame = CUE_FRAME, atmosphere = AtmospherePresets.UNCERTAIN)
        val trace = Oscilloscope.capture(captureConfig(cues = listOf(cue))).getOrThrow()

        val generator = SignalGenerator(configuration = liveScene(), fps = FPS)
        val lived =
            List(trace.frameCount) { frameIndex ->
                if (frameIndex == CUE_FRAME) generator.setAtmosphere(AtmospherePresets.UNCERTAIN)
                generator.nextFrame()
            }

        val player = TracePlayer(trace)
        val replayed = List(trace.frameCount) { player.frameAt(player.frameDuration * it) }

        assertEquals(lived, replayed)
        // Guard against a vacuous pass: the transition has to actually move the orb.
        assertNotEquals(lived.first().cells, lived.last().cells)
    }

    @Test
    fun theCodecRoundTripDoesNotDisturbEquivalence() {
        val trace = Oscilloscope.capture(captureConfig()).getOrThrow()
        val decoded = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()

        val fromMemory = TracePlayer(trace)
        val fromBytes = TracePlayer(decoded)

        val replayedInMemory = List(trace.frameCount) { fromMemory.frameAt(fromMemory.frameDuration * it) }
        val replayedFromBytes = List(trace.frameCount) { fromBytes.frameAt(fromBytes.frameDuration * it) }

        assertEquals(replayedInMemory, replayedFromBytes)
    }

    @Test
    fun aLiveSimulationIsIndependentOfTheClockThatPacesIt() =
        runTest {
            // Frames are a function of how many fixed steps were taken, never of
            // wall time — otherwise no recording could ever replay identically.
            val pumped = SignalGenerator(configuration = liveScene(), fps = FPS)
            val byHand = List(FRAME_COUNT) { pumped.nextFrame() }

            val byClock =
                SignalGenerator(configuration = liveScene(), fps = FPS)
                    .frames(testTimeSource)
                    .take(FRAME_COUNT)
                    .toList()

            assertEquals(byHand, byClock)
        }

    @Test
    fun theRecordedStreamIsNotStatic() {
        val trace = Oscilloscope.capture(captureConfig()).getOrThrow()
        val player = TracePlayer(trace)

        val first = player.frameAt(player.frameDuration * 0)
        val later = player.frameAt(player.frameDuration * (trace.frameCount - 1))

        // A pass on a stream of identical frames would prove nothing about replay.
        assertTrue(first.cells.isNotEmpty(), "capture produced an empty lattice")
        assertNotEquals(first.cells, later.cells)
    }

    @Test
    fun equivalenceHoldsAtEveryFrameNotJustInAggregate() {
        val trace = Oscilloscope.capture(captureConfig()).getOrThrow()
        val generator = SignalGenerator(configuration = liveScene(), fps = FPS)
        val player = TracePlayer(trace)

        // Frame-indexed assertions so a regression names the frame it broke on
        // instead of dumping two 30-frame lists.
        for (frameIndex in 0 until trace.frameCount) {
            val live = generator.nextFrame()
            val replayed = player.frameAt(player.frameDuration * frameIndex)
            assertFrameEquals(live, replayed, frameIndex)
        }
    }

    private fun assertFrameEquals(
        live: VoxelFrame,
        replayed: VoxelFrame,
        frameIndex: Int,
    ) {
        assertEquals(live.tick, replayed.tick, "tick diverged at frame $frameIndex")
        assertEquals(
            live.timestampEpochMillis,
            replayed.timestampEpochMillis,
            "timestamp diverged at frame $frameIndex",
        )
        assertEquals(live.resolution, replayed.resolution, "resolution diverged at frame $frameIndex")
        assertEquals(live.ambient, replayed.ambient, "ambient diverged at frame $frameIndex")
        assertEquals(live.glyph, replayed.glyph, "glyph diverged at frame $frameIndex")
        assertEquals(live.cells.size, replayed.cells.size, "cell count diverged at frame $frameIndex")
        for (cellIndex in live.cells.indices) {
            assertEquals(
                live.cells[cellIndex],
                replayed.cells[cellIndex],
                "cell $cellIndex diverged at frame $frameIndex",
            )
        }
    }

    private companion object {
        const val SEED = 20_260_604L
        const val FPS = 30
        const val DURATION_MS = 1_000L
        const val FRAME_COUNT = 30
        const val CUE_FRAME = 12

        /** The atmosphere both sides start in. Anything but SOLID so the pattern moves. */
        val START_ATMOSPHERE = AtmospherePresets.THINKING

        fun captureConfig(cues: List<AtmosphereCue> = emptyList()): CaptureConfig =
            CaptureConfig(
                params = CaptureParams(atmosphere = START_ATMOSPHERE),
                seed = SEED,
                durationMs = DURATION_MS,
                fps = FPS,
                cues = cues,
            )

        /**
         * A realistic live scene — everything on, full substrate — deliberately
         * unlike the headless one capture uses.
         */
        fun liveScene(): SceneConfiguration =
            SceneConfiguration(
                width = 80,
                height = 24,
                enableAtmosphere = true,
                initialAtmosphere = START_ATMOSPHERE,
                seed = SEED,
            )
    }
}
