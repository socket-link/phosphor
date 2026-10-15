package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals

class GlyphEnvelopeTest {
    @Test
    fun `visibility is zero at both ends of the window and full through the hold`() {
        assertEquals(0f, GlyphEnvelope.visibility(0f))
        assertEquals(1f, GlyphEnvelope.visibility(GlyphEnvelope.FADE_IN_END))
        assertEquals(1f, GlyphEnvelope.visibility(0.5f))
        assertEquals(1f, GlyphEnvelope.visibility(GlyphEnvelope.FADE_OUT_START))
        assertEquals(0f, GlyphEnvelope.visibility(1f))
    }

    @Test
    fun `fades are smoothstep halfway through each ramp`() {
        assertEquals(0.5f, GlyphEnvelope.visibility(0.1f), absoluteTolerance = 1e-5f)
        assertEquals(0.5f, GlyphEnvelope.visibility(0.9f), absoluteTolerance = 1e-5f)
    }

    @Test
    fun `the last moments of the window are nearly invisible`() {
        assertEquals(0.00725f, GlyphEnvelope.visibility(0.99f), absoluteTolerance = 1e-4f)
    }

    @Test
    fun `progress outside the window is clamped`() {
        assertEquals(0f, GlyphEnvelope.visibility(-0.5f))
        assertEquals(0f, GlyphEnvelope.visibility(1.5f))
    }

    @Test
    fun `lifecycle visibility is the envelope at its progress`() {
        for (step in 0..20) {
            val progress = step / 20f
            val lifecycle = GlyphLifecycle(LumosGlyph.STAR, totalDurationSeconds = 4f, ageSeconds = progress * 4f)
            assertEquals(GlyphEnvelope.visibility(lifecycle.progress), lifecycle.visibility)
        }
    }
}
