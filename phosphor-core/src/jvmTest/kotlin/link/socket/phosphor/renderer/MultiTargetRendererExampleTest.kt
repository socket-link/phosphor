package link.socket.phosphor.renderer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiTargetRendererExampleTest {
    @Test
    fun `example renders same frame to terminal and compose outputs`() {
        val frame =
            SimulationFrame(
                tick = 9L,
                timestampEpochMillis = 500L,
                width = 2,
                height = 1,
                cells =
                    listOf(
                        FrameCell(char = 'A', fgColor = 82, luminance = 0.8f),
                        FrameCell(char = 'B', fgColor = 196, luminance = 0.7f),
                    ),
            )

        val output = MultiTargetRendererExample.renderSimultaneously(frame)

        assertEquals(2, output.terminal.width)
        assertEquals(1, output.terminal.height)
        assertEquals(2, output.compose.width)
        assertEquals(1, output.compose.height)
        assertTrue(output.compose.commands.isNotEmpty())
        assertTrue(output.terminal.lines.isNotEmpty())
    }
}
