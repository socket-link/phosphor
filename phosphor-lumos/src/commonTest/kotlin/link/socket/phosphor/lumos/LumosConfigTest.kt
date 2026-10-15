package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class LumosConfigTest {
    @Test
    fun `the default config is a moving orb seen straight on with no label`() {
        val config = LumosConfig()

        assertEquals(1f, config.speed)
        assertEquals(1f, config.density)
        assertEquals(1f, config.dotSize)
        assertEquals(0f, config.tiltDegrees)
        assertFalse(config.paused)
        assertFalse(config.reducedMotion)
        assertNull(config.label)
    }

    @Test
    fun `speed is accepted at both ends of its range`() {
        assertEquals(LumosConfig.MIN_SPEED, LumosConfig(speed = 0.1f).speed)
        assertEquals(LumosConfig.MAX_SPEED, LumosConfig(speed = 3f).speed)
    }

    @Test
    fun `speed outside its range is rejected`() {
        assertFailsWith<IllegalArgumentException> { LumosConfig(speed = 0.09f) }
        assertFailsWith<IllegalArgumentException> { LumosConfig(speed = 3.01f) }
        assertFailsWith<IllegalArgumentException> { LumosConfig(speed = 0f) }
        assertFailsWith<IllegalArgumentException> { LumosConfig(speed = Float.NaN) }
    }

    @Test
    fun `dot size and density must be positive`() {
        assertFailsWith<IllegalArgumentException> { LumosConfig(dotSize = 0f) }
        assertFailsWith<IllegalArgumentException> { LumosConfig(dotSize = -1f) }
        assertFailsWith<IllegalArgumentException> { LumosConfig(density = 0f) }
    }

    @Test
    fun `tilt must be finite`() {
        assertFailsWith<IllegalArgumentException> { LumosConfig(tiltDegrees = Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { LumosConfig(tiltDegrees = Float.NaN) }
    }

    @Test
    fun `a config round-trips through JSON`() {
        val config =
            LumosConfig(
                speed = 2f,
                density = 0.5f,
                dotSize = 1.25f,
                tiltDegrees = 20f,
                paused = true,
                reducedMotion = true,
                label = "Lumos is thinking",
            )

        val json = Json.encodeToString(LumosConfig.serializer(), config)

        assertEquals(config, Json.decodeFromString(LumosConfig.serializer(), json))
    }

    @Test
    fun `decoding holds a config to the same range as constructing one`() {
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(LumosConfig.serializer(), """{"speed":9.0}""")
        }
    }
}
