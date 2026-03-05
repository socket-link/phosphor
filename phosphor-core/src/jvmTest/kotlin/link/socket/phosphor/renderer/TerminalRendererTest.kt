package link.socket.phosphor.renderer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import link.socket.phosphor.palette.AsciiLuminancePalette

class TerminalRendererTest {
    @Test
    fun `terminal renderer outputs ansi escaped text by default`() {
        val renderer = TerminalRenderer()
        val frame =
            SimulationFrame(
                tick = 1L,
                timestampEpochMillis = 100L,
                width = 1,
                height = 1,
                cells = listOf(FrameCell(char = '#', fgColor = 196, bgColor = 232, bold = true)),
            )

        val output = renderer.render(frame)

        assertTrue(output.rendered)
        assertTrue(output.text.contains("\u001B[1m"))
        assertTrue(output.text.contains("\u001B[38;5;196m"))
        assertTrue(output.text.contains("\u001B[48;5;232m"))
    }

    @Test
    fun `terminal renderer throttles to configured fps`() {
        var now = 1_000L
        val renderer = TerminalRenderer(preferredFps = 30, includeAnsi = false, clockMillis = { now })

        val first = renderer.render(singleCellFrame(tick = 1L))
        assertTrue(first.rendered)

        now += 10
        val second = renderer.render(singleCellFrame(tick = 2L))
        assertFalse(second.rendered)

        now += 40
        val third = renderer.render(singleCellFrame(tick = 3L))
        assertTrue(third.rendered)
    }

    @Test
    fun `terminal renderer can remap glyphs from luminance using selected palette`() {
        val palette = AsciiLuminancePalette.EXECUTE
        val renderer = TerminalRenderer(palette = palette, includeAnsi = false)
        val expected = palette.charForLuminance(1f)

        val frame =
            SimulationFrame(
                tick = 1L,
                timestampEpochMillis = 100L,
                width = 1,
                height = 1,
                cells = listOf(FrameCell(char = '?', fgColor = 15, luminance = 1f)),
            )

        val output = renderer.render(frame)

        assertEquals(expected.toString(), output.text)
    }

    @Test
    fun `terminal renderer defaults to 30 fps and terminal target`() {
        val renderer = TerminalRenderer()

        assertEquals(30, renderer.preferredFps)
        assertEquals(RenderTarget.TERMINAL, renderer.target)
    }

    private fun singleCellFrame(tick: Long): SimulationFrame =
        SimulationFrame(
            tick = tick,
            timestampEpochMillis = 100L,
            width = 1,
            height = 1,
            cells = listOf(FrameCell(char = 'x', fgColor = 15)),
        )
}
