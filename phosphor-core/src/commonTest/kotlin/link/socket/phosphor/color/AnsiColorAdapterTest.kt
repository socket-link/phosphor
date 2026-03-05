package link.socket.phosphor.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnsiColorAdapterTest {
    @Test
    fun `ansi256 palette round-trips legacy phase stops`() {
        val adapter = AnsiColorAdapter.DEFAULT
        val legacyStops =
            listOf(
                17,
                18,
                24,
                31,
                38,
                74,
                110,
                117,
                153,
                189,
                231,
                52,
                94,
                130,
                136,
                172,
                178,
                214,
                220,
                221,
                23,
                29,
                30,
                36,
                37,
                43,
                79,
                115,
                159,
                88,
                124,
                160,
                196,
                202,
                208,
                226,
                53,
                54,
                91,
                97,
                134,
                140,
                141,
                183,
                232,
                236,
                240,
                244,
                248,
                252,
                255,
            )

        legacyStops.forEach { code ->
            val neutral = adapter.neutralFromAnsi256(code)
            assertEquals(code, adapter.ansi256Code(neutral), "Expected ANSI code $code to round-trip")
        }
    }

    @Test
    fun `foreground and background escape use ansi256 format`() {
        val color = AnsiColorAdapter.ansi256ToNeutral(196)

        val foreground = AnsiColorAdapter.DEFAULT.foreground(color)
        val background = AnsiColorAdapter.DEFAULT.background(color)

        assertEquals("\u001B[38;5;196m", foreground)
        assertEquals("\u001B[48;5;196m", background)
    }

    @Test
    fun `true color mode emits 24 bit escapes`() {
        val adapter = AnsiColorAdapter(mode = AnsiColorMode.TRUE_COLOR)
        val color = NeutralColor.fromHex("#3366CC")

        assertEquals("\u001B[38;2;51;102;204m", adapter.foreground(color))
        assertEquals("\u001B[48;2;51;102;204m", adapter.background(color))
    }

    @Test
    fun `foreground escape for code clamps out of range values`() {
        val escaped = AnsiColorAdapter.foregroundEscapeForCode(999)
        assertTrue(escaped.contains("38;5;255"))
    }
}
