package link.socket.phosphor.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnsiColorMapTest {
    @Test
    fun `linear rgb gamma round trip preserves midpoint srgb channel`() {
        val midpoint = NeutralColor.fromHex("#808080")
        val roundTripped = LinearRgbColor.fromSrgb(midpoint).toSrgb()

        assertEquals(midpoint, roundTripped)
    }

    @Test
    fun `ansi 256 palette table contains standard cube boundaries`() {
        assertEquals(256, Ansi256Palette.size)
        assertEquals(NeutralColor.BLACK, AnsiColorAdapter.ansi256ToNeutral(16))
        assertEquals(NeutralColor.WHITE, AnsiColorAdapter.ansi256ToNeutral(231))
    }

    @Test
    fun `pure primary colors snap to color cube indices`() {
        assertEquals(196, NeutralColor.fromHex("#FF0000").nearestPaletteIndex())
        assertEquals(46, NeutralColor.fromHex("#00FF00").nearestPaletteIndex())
        assertEquals(21, NeutralColor.fromHex("#0000FF").nearestPaletteIndex())
    }

    @Test
    fun `pure black and white prefer color cube duplicates over base colors`() {
        assertEquals(16, NeutralColor.BLACK.nearestPaletteIndex())
        assertEquals(231, NeutralColor.WHITE.nearestPaletteIndex())
    }

    @Test
    fun `near grayscale snaps to grayscale ramp instead of color cube`() {
        val index = NeutralColor.fromHex("#808080").nearestPaletteIndex()

        assertEquals(244, index)
        assertTrue(index in 232..255)
    }

    @Test
    fun `truecolor mode emits raw foreground and background twenty four bit escapes`() {
        val red = NeutralColor.fromHex("#FF0000").toOklab()
        val cobalt = NeutralColor.fromHex("#3366CC").toOklab()

        assertEquals("\u001B[38;2;255;0;0m", AnsiColorMap.escape(red, AnsiColorMode.TRUECOLOR))
        assertEquals("\u001B[48;2;255;0;0m", AnsiColorMap.backgroundEscape(red, AnsiColorMode.TRUECOLOR))
        assertEquals("\u001B[38;2;51;102;204m", AnsiColorMap.escape(cobalt, AnsiColorMode.TRUECOLOR))
    }

    @Test
    fun `ansi 256 mode emits foreground and background palette escapes`() {
        val red = NeutralColor.fromHex("#FF0000").toOklab()

        assertEquals("\u001B[38;5;196m", AnsiColorMap.escape(red, AnsiColorMode.ANSI_256))
        assertEquals("\u001B[48;5;196m", AnsiColorMap.backgroundEscape(red, AnsiColorMode.ANSI_256))
    }

    @Test
    fun `near saturated lumos atmosphere colors quantize to stable palette regions`() {
        assertEquals(63, NeutralColor.fromHsl(244f, 0.85f, 0.60f).nearestPaletteIndex())
        assertEquals(75, NeutralColor.fromHsl(197f, 0.85f, 0.60f).nearestPaletteIndex())
        assertEquals(215, NeutralColor.fromHsl(32f, 0.85f, 0.60f).nearestPaletteIndex())
        assertEquals(135, NeutralColor.fromHsl(280f, 0.85f, 0.60f).nearestPaletteIndex())
    }

    private fun NeutralColor.toOklab(): OklabColor = OklabColor.fromSrgb(this)

    private fun NeutralColor.nearestPaletteIndex(): Int = AnsiColorMap.nearestPaletteIndex(toOklab())
}
