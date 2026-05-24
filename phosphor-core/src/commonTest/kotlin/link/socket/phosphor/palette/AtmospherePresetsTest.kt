package link.socket.phosphor.palette

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import link.socket.phosphor.signal.AtmospherePattern
import link.socket.phosphor.signal.AtmosphereState

class AtmospherePresetsTest {
    @Test
    fun `IDLE preset references are equal by value`() {
        assertEquals(AtmospherePresets.IDLE, AtmospherePresets.IDLE)
    }

    @Test
    fun `byName returns expected atmosphere state case-insensitively`() {
        assertEquals(AtmospherePresets.UNCERTAIN, AtmospherePresets.byName("uncertain"))
        assertEquals(AtmospherePresets.UNCERTAIN, AtmospherePresets.byName("UnCeRtAiN"))
    }

    @Test
    fun `byName returns null for unknown preset`() {
        assertNull(AtmospherePresets.byName("unknown"))
    }

    @Test
    fun `ALL contains five canonical presets`() {
        assertEquals(5, AtmospherePresets.ALL.size)
        assertEquals(
            listOf("idle", "listening", "thinking", "uncertain", "ready"),
            AtmospherePresets.ALL.map { (name, _) -> name },
        )
    }

    @Test
    fun `canonical presets use locked prototype parameters`() {
        assertEquals(
            listOf(
                "idle" to
                    state(
                        primaryHue = 250f,
                        secondaryHue = 175f,
                        bipolarStrength = 0.0f,
                        pattern = AtmospherePattern.LONGITUDE,
                        patternSpeed = 0.25f,
                        pulseAmplitude = 0.025f,
                        pulseFrequency = 0.30f,
                        rotationY = 0.14f,
                        rotationX = 0.0f,
                    ),
                "listening" to
                    state(
                        primaryHue = 195f,
                        secondaryHue = 270f,
                        bipolarStrength = 0.0f,
                        pattern = AtmospherePattern.PLASMA,
                        patternSpeed = 1.15f,
                        pulseAmplitude = 0.06f,
                        pulseFrequency = 0.5f,
                        rotationY = 0.16f,
                        rotationX = 0.10f,
                    ),
                "thinking" to
                    state(
                        primaryHue = 244f,
                        secondaryHue = 185f,
                        bipolarStrength = 0.0f,
                        pattern = AtmospherePattern.SPIRAL,
                        patternSpeed = 0.7f,
                        pulseAmplitude = 0.025f,
                        pulseFrequency = 0.45f,
                        rotationY = 0.45f,
                        rotationX = 0.10f,
                    ),
                "uncertain" to
                    state(
                        primaryHue = 32f,
                        secondaryHue = 280f,
                        bipolarStrength = 0.45f,
                        pattern = AtmospherePattern.SPIRAL,
                        patternSpeed = 1.0f,
                        pulseAmplitude = 0.06f,
                        pulseFrequency = 0.16f,
                        rotationY = 0.10f,
                        rotationX = 0.10f,
                    ),
                "ready" to
                    state(
                        primaryHue = 249f,
                        secondaryHue = 197f,
                        bipolarStrength = 0.0f,
                        pattern = AtmospherePattern.PULSE,
                        patternSpeed = 1.3f,
                        pulseAmplitude = 0.04f,
                        pulseFrequency = 0.5f,
                        rotationY = 0.20f,
                        rotationX = -0.20f,
                    ),
            ),
            AtmospherePresets.ALL,
        )
    }

    private fun state(
        primaryHue: Float,
        secondaryHue: Float,
        bipolarStrength: Float,
        pattern: AtmospherePattern,
        patternSpeed: Float,
        pulseAmplitude: Float,
        pulseFrequency: Float,
        rotationY: Float,
        rotationX: Float,
    ): AtmosphereState =
        AtmosphereState(
            primaryHue = primaryHue,
            secondaryHue = secondaryHue,
            saturation = 0.85f,
            lightness = 0.60f,
            bipolarStrength = bipolarStrength,
            pattern = pattern,
            patternSpeed = patternSpeed,
            pulseAmplitude = pulseAmplitude,
            pulseFrequency = pulseFrequency,
            rotationY = rotationY,
            rotationX = rotationX,
            surfaceBump = 0.10f,
            noise = 0.20f,
            voxelGap = 0.05f,
            ySquash = 0.95f,
            resolution = 10,
            glow = 1.0f,
        )
}
