package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.LumosInput

/**
 * PHO-82: input is live only. A `.vxt` cannot carry a pointer: `VoxelTrace.fromFrames`
 * reads a frame's tick, timestamp, cells, ambient and glyph, and `VoxelFrame.input` is
 * never serialized. And `Oscilloscope` has no pointer to record: its generator is never
 * handed one, so a capture with `interactive` on is the capture with it off.
 */
class InputTraceRuleTest {
    @Test
    fun `every frame the Oscilloscope records has no input — even with interactive on`() {
        // The generator Oscilloscope drives, as it drives it.
        val generator = SignalGenerator(scene(), renderConfig = INTERACTIVE, fps = FPS)
        repeat(FRAMES) { assertNull(generator.nextFrame().input, "frame $it") }

        val trace = Oscilloscope.capture(capture(INTERACTIVE)).getOrThrow()
        trace.toFrames().forEachIndexed { index, frame -> assertNull(frame.input, "recorded frame $index") }
    }

    @Test
    fun `a capture with interactive on is byte for byte the capture with it off`() {
        val on = Oscilloscope.capture(capture(INTERACTIVE)).getOrThrow()
        val off = Oscilloscope.capture(capture(LumosRenderConfig())).getOrThrow()

        assertEquals(off, on)
        assertContentEquals(encodeForTest(off), encodeForTest(on))
    }

    @Test
    fun `a stream built with a pointer and interactive off encodes to the bytes of the stream without one`() {
        val pointed = LiveFrameSource(THINKING)
        val plain = LiveFrameSource(THINKING)
        pointed.setInput(LumosInput(pointer = Vector2(0.2f, -0.1f), pressed = true))

        val withPointer = List(FRAMES) { pointed.next(DT) }
        val without = List(FRAMES) { plain.next(DT) }

        assertTrue(withPointer.all { it.input != null }, "the live frames carry the pointer")
        assertNotEquals(without, withPointer)
        assertContentEquals(encodeForTest(traceOf(without)), encodeForTest(traceOf(withPointer)))
    }

    private fun traceOf(frames: List<VoxelFrame>): VoxelTrace =
        VoxelTrace.fromFrames(frames = frames, fps = FPS, phosphorVersion = "test", createdAtEpochMs = 0L)

    // A transition and a morph, so the body and the atmosphere's interpolation both run.
    private fun capture(renderConfig: LumosRenderConfig): CaptureConfig =
        CaptureConfig(
            params = CaptureParams(atmosphere = IDLE, renderConfig = renderConfig),
            durationMs = 1_000L,
            fps = FPS,
            cues = listOf(AtmosphereCue(atFrame = 10, atmosphere = THINKING)),
            formCues = listOf(FormCue(atFrame = 3, form = Forms.Fibonacci, durationSeconds = 0.3f)),
        )

    private fun scene(): SceneConfiguration =
        SceneConfiguration(
            width = 1,
            height = 1,
            enableWaveform = false,
            enableParticles = false,
            enableFlow = false,
            enableEmitters = false,
            enableCamera = false,
            enableAtmosphere = true,
            initialAtmosphere = THINKING,
        )

    private companion object {
        const val RESOLUTION = 4
        const val FPS = 20
        const val DT = 1f / FPS
        const val FRAMES = 20

        val INTERACTIVE = LumosRenderConfig(interactive = true)
        val IDLE = AtmospherePresets.IDLE.copy(resolution = RESOLUTION)
        val THINKING = AtmospherePresets.THINKING.copy(resolution = RESOLUTION)
    }
}
