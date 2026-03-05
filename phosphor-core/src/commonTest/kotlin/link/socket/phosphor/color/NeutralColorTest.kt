package link.socket.phosphor.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NeutralColorTest {
    @Test
    fun `fromHex parses rgb input`() {
        val color = NeutralColor.fromHex("#3366CC")

        assertEquals(0x33, color.redInt)
        assertEquals(0x66, color.greenInt)
        assertEquals(0xCC, color.blueInt)
        assertEquals(0xFF, color.alphaInt)
    }

    @Test
    fun `fromHex parses rgba input`() {
        val color = NeutralColor.fromHex("11223380")

        assertEquals(0x11, color.redInt)
        assertEquals(0x22, color.greenInt)
        assertEquals(0x33, color.blueInt)
        assertEquals(0x80, color.alphaInt)
    }

    @Test
    fun `fromHex rejects invalid lengths`() {
        assertFailsWith<IllegalArgumentException> {
            NeutralColor.fromHex("#12345")
        }
    }

    @Test
    fun `fromHex rejects invalid characters`() {
        assertFailsWith<IllegalArgumentException> {
            NeutralColor.fromHex("#12ZZAA")
        }
    }

    @Test
    fun `fromHsl converts primaries`() {
        val red = NeutralColor.fromHsl(0f, 1f, 0.5f)
        val green = NeutralColor.fromHsl(120f, 1f, 0.5f)
        val blue = NeutralColor.fromHsl(240f, 1f, 0.5f)

        assertEquals(255, red.redInt)
        assertEquals(255, green.greenInt)
        assertEquals(255, blue.blueInt)
    }

    @Test
    fun `toHex round trips through fromHex`() {
        val original = NeutralColor.fromRgba(0.2f, 0.45f, 0.9f, 0.7f)
        val roundTripped = NeutralColor.fromHex(original.toHex())

        assertEquals(original, roundTripped)
    }

    @Test
    fun `lerp interpolates midpoint`() {
        val start = NeutralColor.fromHex("#000000")
        val end = NeutralColor.fromHex("#FFFFFF")
        val midpoint = NeutralColor.lerp(start, end, 0.5f)

        assertEquals(128, midpoint.redInt)
        assertEquals(128, midpoint.greenInt)
        assertEquals(128, midpoint.blueInt)
    }

    @Test
    fun `toHex can omit alpha`() {
        val color = NeutralColor.fromHex("#AABBCCDD")
        assertEquals("#AABBCC", color.toHex(includeAlpha = false))
    }

    @Test
    fun `fromRgba clamps out of range channels`() {
        val color = NeutralColor.fromRgba(-1f, 0.5f, 2f, 9f)

        assertEquals(0, color.redInt)
        assertEquals(128, color.greenInt)
        assertEquals(255, color.blueInt)
        assertEquals(255, color.alphaInt)
        assertTrue(color.red in 0f..1f)
    }
}
