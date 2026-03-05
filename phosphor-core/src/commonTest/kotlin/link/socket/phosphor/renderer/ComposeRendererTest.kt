package link.socket.phosphor.renderer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposeRendererTest {
    @Test
    fun `render produces canvas commands for non-empty cells`() {
        val renderer = ComposeRenderer()
        val frame =
            SimulationFrame(
                tick = 1L,
                timestampEpochMillis = 10L,
                width = 2,
                height = 2,
                cells =
                    listOf(
                        FrameCell(char = 'A', fgColor = 196, luminance = 0.9f, bold = true),
                        FrameCell(char = ' ', fgColor = 196, luminance = 0f),
                        FrameCell(char = '.', fgColor = 34, luminance = 0.2f),
                        FrameCell(char = ' ', fgColor = 34, luminance = 0f),
                    ),
            )

        val output = renderer.render(frame)

        assertEquals(2, output.width)
        assertEquals(2, output.height)
        assertEquals(2, output.commands.size)

        val first = output.commands.first()
        assertEquals(0, first.x)
        assertEquals(0, first.y)
        assertEquals('A', first.char)
        assertTrue(first.alpha > 0.8f)
        assertTrue(first.gradient.end.red >= first.gradient.start.red)
    }

    @Test
    fun `preferred fps defaults to 60 for compose targets`() {
        val renderer = ComposeRenderer()

        assertEquals(60, renderer.preferredFps)
        assertEquals(RenderTarget.COMPOSE, renderer.target)
    }

    @Test
    fun `ansi256ToColor maps color cube indexes`() {
        val color = ComposeRenderer.ansi256ToColor(196)

        assertEquals(255, color.red)
        assertEquals(0, color.green)
        assertEquals(0, color.blue)
    }
}
