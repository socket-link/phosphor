package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class MaterialTest {
    @Test
    fun `ink and neon default to half grain and half glow at full intensity`() {
        assertEquals(0.5f, Material.Ink().grain)
        assertEquals(1f, Material.Ink().intensity)
        assertEquals(0.5f, Material.Neon().glow)
        assertEquals(1f, Material.Neon().intensity)
    }

    @Test
    fun `grain and glow are held to 0 to 1 and intensity to non-negative`() {
        assertFailsWith<IllegalArgumentException> { Material.Ink(grain = -0.1f) }
        assertFailsWith<IllegalArgumentException> { Material.Ink(grain = 1.1f) }
        assertFailsWith<IllegalArgumentException> { Material.Ink(intensity = -1f) }
        assertFailsWith<IllegalArgumentException> { Material.Neon(glow = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Material.Neon(glow = 2f) }
        assertFailsWith<IllegalArgumentException> { Material.Neon(intensity = Float.POSITIVE_INFINITY) }
        assertEquals(0f, Material.Ink(grain = 0f).grain)
        assertEquals(1f, Material.Neon(glow = 1f).glow)
    }

    @Test
    fun `every material round-trips through JSON`() {
        val materials =
            listOf(
                Material.Flat,
                Material.Ink(grain = 0.25f, intensity = 0.8f),
                Material.Neon(glow = 1f, intensity = 0.5f),
                Material.Metal(roughness = 0.3f),
                Material.Glass(frost = 0.1f),
            )
        for (material in materials) {
            val json = Json.encodeToString(Material.serializer(), material)
            assertEquals(material, Json.decodeFromString(Material.serializer(), json), json)
        }
    }

    @Test
    fun `grain is deterministic, in range, and differs between neighbouring cells`() {
        val bits = (0 until 256).map { Material.grainBits(it) }
        assertEquals(bits, (0 until 256).map { Material.grainBits(it) })
        for (b in bits) {
            val a = Material.grainAlpha(b)
            val r = Material.grainRadius(b)
            assertTrue(a >= 0f && a < 1f, "alpha grain $a out of range")
            assertTrue(r >= 0f && r < 1f, "radius grain $r out of range")
        }
        assertNotEquals(bits[0], bits[1])
        assertTrue(bits.toSet().size > 250, "neighbouring cells should rarely share grain")
        // Both halves spread across their range rather than clustering.
        val alphas = bits.map(Material::grainAlpha)
        val lowAlphas = alphas.count { it < 0.5f }
        assertTrue(lowAlphas in 96..160, "alpha grain is skewed: $lowAlphas below half")
        val radii = bits.map(Material::grainRadius)
        val lowRadii = radii.count { it < 0.5f }
        assertTrue(lowRadii in 96..160, "radius grain is skewed: $lowRadii below half")
    }
}
