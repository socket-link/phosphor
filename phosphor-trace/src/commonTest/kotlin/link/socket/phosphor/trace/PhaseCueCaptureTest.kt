package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.CognitivePhase

/**
 * PHO-78: a [PhaseCue] moves the capture's agent to a phase, the look switch the first cue
 * installs blends the orb to it, and the look bakes into the recorded cells with no format change.
 */
class PhaseCueCaptureTest {
    private val fps = 24
    private val frames = 48

    private fun config(vararg cues: PhaseCue): CaptureConfig =
        CaptureConfig(
            params = CaptureParams(atmosphere = AtmospherePresets.IDLE.copy(resolution = 4)),
            durationMs = frames * 1_000L / fps,
            fps = fps,
            phaseCues = cues.toList(),
        )

    @Test
    fun `a second phase leaves the first untouched until it fires and differs once its blend lands`() {
        val executeOnly = Oscilloscope.capture(config(PhaseCue(0, CognitivePhase.EXECUTE))).getOrThrow().toFrames()
        val thenPerceive =
            Oscilloscope
                .capture(config(PhaseCue(0, CognitivePhase.EXECUTE), PhaseCue(30, CognitivePhase.PERCEIVE)))
                .getOrThrow()
                .toFrames()
        assertEquals(frames, thenPerceive.size)

        for (frame in 0 until 30) assertEquals(executeOnly[frame], thenPerceive[frame], "frame $frame")
        // 0.6 s at 24 fps is 14.4 frames: the blend from frame 30 has landed by frame 45.
        for (frame in 45 until frames) {
            assertNotEquals(executeOnly[frame].cells, thenPerceive[frame].cells, "frame $frame")
        }
    }

    @Test
    fun `a phase cue changes the frames a capture without one records`() {
        val plain = Oscilloscope.capture(config()).getOrThrow().toFrames()
        val cued = Oscilloscope.capture(config(PhaseCue(0, CognitivePhase.EXECUTE))).getOrThrow().toFrames()

        assertNotEquals(plain.last().cells, cued.last().cells)
    }

    @Test
    fun `equal configs with phase cues encode to identical bytes`() {
        val cues = arrayOf(PhaseCue(0, CognitivePhase.EXECUTE), PhaseCue(30, CognitivePhase.PERCEIVE))
        val first = Oscilloscope.capture(config(*cues)).getOrThrow()
        val second = Oscilloscope.capture(config(*cues)).getOrThrow()

        assertEquals(first, second)
        assertContentEquals(encodeForTest(first), encodeForTest(second))
    }

    @Test
    fun `a phase cue outside the clip is an invalid config`() {
        for (atFrame in listOf(-1, frames)) {
            val result = Oscilloscope.capture(config(PhaseCue(atFrame, CognitivePhase.PLAN)))
            assertIs<CaptureError.InvalidConfig>(result.exceptionOrNull(), "atFrame $atFrame")
        }
    }

    @Test
    fun `phase cues and a named agent are recorded only when present`() {
        val cued =
            Oscilloscope.snapshotParams(
                config(PhaseCue(0, CognitivePhase.EXECUTE), PhaseCue(30, CognitivePhase.PERCEIVE)),
            )
        assertEquals("30", cued["phaseCue.1.atFrame"])
        assertEquals("PERCEIVE", cued["phaseCue.1.phase"])

        val plain = Oscilloscope.snapshotParams(config())
        assertFalse(plain.keys.any { it.startsWith("phaseCue.") })
        assertFalse("render.agentId" in plain)
        assertFalse("render.unattended" in plain)

        val named =
            Oscilloscope.snapshotParams(
                config().copy(
                    params = CaptureParams(renderConfig = LumosRenderConfig(agentId = "run-7", unattended = true)),
                ),
            )
        assertEquals("run-7", named["render.agentId"])
        assertEquals("true", named["render.unattended"])
    }
}
