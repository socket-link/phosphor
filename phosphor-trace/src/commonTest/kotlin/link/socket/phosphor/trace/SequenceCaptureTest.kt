package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.lumos.LumosSequence
import link.socket.phosphor.lumos.LumosStep
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneConfiguration

/**
 * PHO-65 Task C: one definition serves capture and live play. A `LumosSequence` on
 * [CaptureConfig.sequence] lays out one segment per step, produces the frames the cue path
 * produces for the same script, captures deterministically, replays through [TraceStateMachine]
 * by step name, and is frame for frame what `SignalGenerator.play` emits live.
 */
class SequenceCaptureTest {
    @Test
    fun `the layout gives each step the frames its duration spans`() {
        val layout = sequence().frameLayout(FPS)

        assertEquals(30, layout.frameCount)
        assertEquals(
            listOf(
                TraceSegment("idle", 0, 9, loop = true),
                TraceSegment("idle→thinking", 10, 19),
                TraceSegment("thinking", 20, 29, loop = true),
            ),
            layout.segments,
        )
    }

    @Test
    fun `forSequence sizes the clip to one pass`() {
        val config = CaptureConfig.forSequence(sequence(), FPS)

        assertEquals(30, config.frameCount)
        assertEquals(1_500L, config.durationMs)
    }

    @Test
    fun `a sequence capture carries one segment per step and the sequence it came from`() {
        val trace = Oscilloscope.capture(CaptureConfig.forSequence(sequence(), FPS, params())).getOrThrow()

        assertEquals(30, trace.frameCount)
        assertEquals(sequence().steps.map(LumosStep::name), trace.segments.map(TraceSegment::name))
        assertEquals(listOf(true, false, true), trace.segments.map(TraceSegment::loop))
        assertEquals(sequence(), LumosSequence.fromJson(trace.paramSnapshot.getValue("sequence")).getOrThrow())
        assertNull(trace.paramSnapshot["cue.0.atFrame"])
    }

    @Test
    fun `a sequence capture produces the frames the cue capture produces`() {
        val fromSequence = Oscilloscope.capture(CaptureConfig.forSequence(sequence(), FPS, params())).getOrThrow()
        val fromCues =
            Oscilloscope
                .capture(
                    CaptureConfig(
                        params = params(),
                        durationMs = 1_500L,
                        fps = FPS,
                        segments =
                            listOf(
                                TraceSegment("idle", 0, 9, loop = true),
                                TraceSegment("idle→thinking", 10, 19),
                                TraceSegment("thinking", 20, 29, loop = true),
                            ),
                        cues = listOf(AtmosphereCue(atFrame = 10, atmosphere = AtmospherePresets.THINKING)),
                    ),
                ).getOrThrow()

        assertEquals(fromCues.toFrames(), fromSequence.toFrames())
        assertEquals(fromCues.segments, fromSequence.segments)
    }

    @Test
    fun `identical sequence configs capture to identical bytes`() {
        val first = Oscilloscope.capture(CaptureConfig.forSequence(sequence(), FPS, params())).getOrThrow()
        val second = Oscilloscope.capture(CaptureConfig.forSequence(sequence(), FPS, params())).getOrThrow()

        assertEquals(first, second)
        assertContentEquals(encodeForTest(first), encodeForTest(second))
    }

    @Test
    fun `the live generator plays the sequence frame for frame as the capture recorded it`() {
        val trace = Oscilloscope.capture(CaptureConfig.forSequence(sequence(), FPS, params())).getOrThrow()
        val generator =
            SignalGenerator(
                configuration =
                    SceneConfiguration(
                        width = 1,
                        height = 1,
                        enableWaveform = false,
                        enableParticles = false,
                        enableFlow = false,
                        enableEmitters = false,
                        enableCamera = false,
                        enableAtmosphere = true,
                        initialAtmosphere = AtmospherePresets.IDLE,
                        seed = 0L,
                    ),
                fps = FPS,
            )

        val player = generator.play(sequence())
        val live = List(trace.frameCount) { generator.nextFrame() }

        assertEquals(trace.toFrames(), live)
        assertEquals(1.5f, player.timeSeconds, 1e-5f)
    }

    @Test
    fun `a captured sequence replays through the state machine by step name`() {
        val trace = Oscilloscope.capture(CaptureConfig.forSequence(sequence(), FPS, params())).getOrThrow()

        val machine = TraceStateMachine(trace, initialState = "idle")

        assertEquals(sequence().steps.map(LumosStep::name), machine.stateNames)
        machine.requestState("thinking").getOrThrow()
        assertTrue(machine.isTransitioning)
        assertEquals("thinking", machine.requestedState.value)
        // The recorded idle→thinking one-shot carries it there: the machine lands on the loop.
        machine.advance(2.seconds)
        assertEquals("thinking", machine.state.value)
    }

    @Test
    fun `a sequence does not share a clip with cues or hand-written segments`() {
        val config =
            CaptureConfig.forSequence(sequence(), FPS, params()).copy(
                cues = listOf(AtmosphereCue(0, AtmospherePresets.READY)),
            )

        val error = Oscilloscope.capture(config).exceptionOrNull()

        assertIs<CaptureError.InvalidConfig>(error)
    }

    @Test
    fun `a sequence needs at least ten frames a second`() {
        val error =
            Oscilloscope.capture(
                CaptureConfig(durationMs = 2_000L, fps = 5, sequence = sequence()),
            ).exceptionOrNull()

        assertIs<CaptureError.InvalidConfig>(error)
    }

    @Test
    fun `a clip must hold the whole sequence`() {
        val error =
            Oscilloscope.capture(
                CaptureConfig(durationMs = 1_000L, fps = FPS, sequence = sequence()),
            ).exceptionOrNull()

        val invalid = assertIs<CaptureError.InvalidConfig>(error)
        assertTrue("forSequence" in (invalid.message ?: ""), invalid.message)
    }

    @Test
    fun `a step shorter than a frame is refused`() {
        val sequence =
            LumosSequence(
                steps =
                    listOf(
                        // At 10 fps frames fall at 0.5 and 0.6 s; a 50 ms step starting at 0.52 s holds neither.
                        LumosStep("idle", 0.52f, atmospherePreset = "idle", loop = true),
                        LumosStep("blink", 0.05f, glyph = LumosGlyph.CHECK),
                    ),
            )

        val error =
            Oscilloscope.capture(
                CaptureConfig(durationMs = 1_000L, fps = 10, sequence = sequence),
            ).exceptionOrNull()

        val invalid = assertIs<CaptureError.InvalidConfig>(error)
        assertTrue("blink" in (invalid.message ?: ""), invalid.message)
    }

    private fun params(): CaptureParams = CaptureParams(atmosphere = AtmospherePresets.IDLE)

    private fun sequence(): LumosSequence =
        LumosSequence(
            steps =
                listOf(
                    LumosStep("idle", 0.5f, atmospherePreset = "idle", loop = true),
                    LumosStep("idle→thinking", 0.5f, atmospherePreset = "thinking"),
                    LumosStep("thinking", 0.5f, atmospherePreset = "thinking", loop = true),
                ),
        )

    private companion object {
        const val FPS = 20
    }
}
