package link.socket.phosphor.renderer

import kotlin.test.Test
import kotlin.test.assertEquals
import link.socket.phosphor.color.AnsiColorAdapter
import link.socket.phosphor.color.CognitiveColorModel
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.signal.CognitivePhase

class ComposeColorAdapterTest {
    @Test
    fun `adapter maps neutral channels directly`() {
        val adapter = ComposeColorAdapter()
        val neutral = NeutralColor.fromHex("#3366CC80")

        val compose = adapter.adapt(neutral)

        assertEquals(51, compose.red)
        assertEquals(102, compose.green)
        assertEquals(204, compose.blue)
        assertEquals(128f / 255f, compose.alpha)
    }

    @Test
    fun `same phase ramp maps consistently to ansi and compose`() {
        val ansi = AnsiColorAdapter.DEFAULT
        val compose = ComposeColorAdapter()
        val ramp = CognitiveColorModel.phaseRampFor(CognitivePhase.PLAN)

        val ansiStops = ramp.stops.map(ansi::ansi256Code)
        val composeStops = compose.adapt(ramp)

        ansiStops.indices.forEach { index ->
            val fromAnsiPath = ComposeRenderer.ansi256ToColor(ansiStops[index])
            assertEquals(fromAnsiPath, composeStops[index])
        }
    }
}
