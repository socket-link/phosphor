package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlyphShapeTest {
    @Test
    fun `each shape contains at least one inside point and excludes an outside point`() {
        val samples =
            mapOf(
                CheckShape to (-0.30f to -0.20f),
                ExclaimShape to (0.00f to 0.00f),
                QuestionShape to (0.00f to 0.52f),
                HeartShape to (0.00f to 0.00f),
                StarShape to (0.00f to 0.00f),
                LightningShape to (0.02f to 0.03f),
            )

        samples.forEach { (shape, insidePoint) ->
            assertTrue(shape.contains(insidePoint.first, insidePoint.second))
            assertFalse(shape.contains(0.90f, -0.90f))
        }
    }

    @Test
    fun `forGlyph resolves each canonical glyph to its shape object`() {
        val expected =
            mapOf(
                LumosGlyph.CHECK to CheckShape,
                LumosGlyph.EXCLAIM to ExclaimShape,
                LumosGlyph.QUESTION to QuestionShape,
                LumosGlyph.HEART to HeartShape,
                LumosGlyph.STAR to StarShape,
                LumosGlyph.LIGHTNING to LightningShape,
            )

        expected.forEach { (glyph, shape) ->
            assertTrue(GlyphShape.forGlyph(glyph) === shape)
        }
    }
}
