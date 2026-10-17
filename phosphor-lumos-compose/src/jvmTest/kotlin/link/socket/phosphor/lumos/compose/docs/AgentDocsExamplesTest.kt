package link.socket.phosphor.lumos.compose.docs

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.compose.SteppedScene
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.Oscilloscope
import link.socket.phosphor.trace.TraceCodec

/**
 * Keeps the agent docs' examples honest. `docs/lumos/llms.txt` and `skills/lumos/` print the
 * `AgentDocs*Example.kt` files beside this one as they are, read by the generator in
 * `:phosphor-lumos-cli`; here each one must compile and draw an orb.
 */
class AgentDocsExamplesTest {
    @Test
    fun traceExampleDrawsAnOrb() {
        val capture = CaptureConfig(CaptureParams(atmosphere = AtmospherePresets.IDLE), durationMs = 500L, fps = 20)
        val vxt = TraceCodec.encode(Oscilloscope.capture(capture).getOrThrow())
        assertDraws("the trace example") { ReplayedOrb(vxt) }
    }

    @Test
    fun inlineExampleDrawsAnOrb() {
        assertDraws("the inline example") { InlineOrb() }
    }

    @Test
    fun lookExampleDrawsAnOrb() {
        assertDraws("the look example") { WorkingOrb(working = true) }
    }

    @Test
    fun signalExampleDrawsAnOrb() {
        assertDraws("the signal example") {
            AgentOrb(phase = CognitivePhase.EXECUTE, activity = AgentActivityState.PROCESSING, escalated = false)
        }
    }

    private fun assertDraws(
        what: String,
        content: @Composable () -> Unit,
    ) {
        SteppedScene(width = SIZE, height = SIZE, content = content).use { scene ->
            val pixels = scene.renderUntil(FRAMES * FRAME_NANOS).toComposeImageBitmap().toPixelMap()
            assertTrue(
                (0 until SIZE).any { x -> (0 until SIZE).any { y -> pixels[x, y].alpha > 0f } },
                "$what drew nothing",
            )
        }
    }

    private companion object {
        const val SIZE = 160
        const val FRAMES = 10L
        const val FRAME_NANOS = 33_333_333L
    }
}
