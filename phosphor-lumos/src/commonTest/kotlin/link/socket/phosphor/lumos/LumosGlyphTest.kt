package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.color.NeutralColor

class LumosGlyphTest {
    @Test
    fun `enum has the six canonical glyph names`() {
        assertEquals(
            listOf("CHECK", "EXCLAIM", "QUESTION", "HEART", "STAR", "LIGHTNING"),
            LumosGlyph.entries.map { it.name },
        )
    }

    @Test
    fun `each glyph accent color resolves to non-grey sRGB`() {
        LumosGlyph.entries.forEach { glyph ->
            val color = NeutralColor.fromHsl(glyph.hue, glyph.saturation, glyph.lightness)
            val isGrey =
                abs(color.red - color.green) < 1e-5f &&
                    abs(color.green - color.blue) < 1e-5f

            assertTrue(glyph.saturation > 0f, "${glyph.name} should declare a saturated accent")
            assertTrue(!isGrey, "${glyph.name} resolved to a grey accent")
        }
    }
}
