package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlyphLifecycleTest {
    @Test
    fun `progress linearly tracks age over total duration`() {
        val lifecycle =
            GlyphLifecycle(
                glyph = LumosGlyph.CHECK,
                totalDurationSeconds = 2f,
                ageSeconds = 0.5f,
            )

        assertEquals(0.25f, lifecycle.progress, absoluteTolerance = 1e-5f)
    }

    @Test
    fun `visibility fades in holds and fades out`() {
        fun lifecycleAt(progress: Float): GlyphLifecycle =
            GlyphLifecycle(
                glyph = LumosGlyph.CHECK,
                totalDurationSeconds = 10f,
                ageSeconds = progress * 10f,
            )

        assertEquals(0f, lifecycleAt(0f).visibility, absoluteTolerance = 1e-5f)
        assertTrue(lifecycleAt(0.1f).visibility in 0f..1f)
        assertEquals(1f, lifecycleAt(0.2f).visibility, absoluteTolerance = 1e-5f)
        assertEquals(1f, lifecycleAt(0.5f).visibility, absoluteTolerance = 1e-5f)
        assertEquals(1f, lifecycleAt(0.8f).visibility, absoluteTolerance = 1e-5f)
        assertTrue(lifecycleAt(0.9f).visibility in 0f..1f)
        assertEquals(0f, lifecycleAt(1f).visibility, absoluteTolerance = 1e-5f)
    }

    @Test
    fun `advance returns a copy with incremented age`() {
        val lifecycle =
            GlyphLifecycle(
                glyph = LumosGlyph.EXCLAIM,
                totalDurationSeconds = 1.5f,
                ageSeconds = 0.25f,
            )

        val advanced = lifecycle.advance(0.5f)

        assertEquals(0.25f, lifecycle.ageSeconds)
        assertEquals(0.75f, advanced.ageSeconds)
    }

    @Test
    fun `isComplete flips when age reaches total duration`() {
        assertFalse(
            GlyphLifecycle(
                glyph = LumosGlyph.QUESTION,
                totalDurationSeconds = 1f,
                ageSeconds = 0.99f,
            ).isComplete,
        )
        assertTrue(
            GlyphLifecycle(
                glyph = LumosGlyph.QUESTION,
                totalDurationSeconds = 1f,
                ageSeconds = 1f,
            ).isComplete,
        )
    }
}
