package link.socket.phosphor.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class AtmosphereStateTest {
    @Test
    fun `AtmosphereState supports JSON serialization`() {
        val state =
            AtmosphereState(
                primaryHue = 244f,
                secondaryHue = 197f,
                saturation = 0.85f,
                lightness = 0.6f,
                bipolarStrength = 0.25f,
                pattern = AtmospherePattern.PLASMA,
                patternSpeed = 1.15f,
                pulseAmplitude = 0.06f,
                pulseFrequency = 0.5f,
                rotationY = 0.16f,
                rotationX = 0.1f,
                surfaceBump = 0.1f,
                noise = 0.2f,
                voxelGap = 0.05f,
                ySquash = 0.95f,
                resolution = 10,
                glow = 1.0f,
            )

        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(AtmosphereState.serializer(), state)
        val decoded = json.decodeFromString(AtmosphereState.serializer(), encoded)

        assertEquals(state, decoded)
    }

    @Test
    fun `AtmosphereTransition supports JSON serialization`() {
        val transition =
            AtmosphereTransition(
                from =
                    AtmosphereState(
                        primaryHue = 250f,
                        secondaryHue = 175f,
                        saturation = 0.85f,
                        lightness = 0.6f,
                        bipolarStrength = 0f,
                        pattern = AtmospherePattern.LONGITUDE,
                        patternSpeed = 0.25f,
                        pulseAmplitude = 0.025f,
                        pulseFrequency = 0.3f,
                        rotationY = 0.14f,
                        rotationX = 0f,
                        surfaceBump = 0.1f,
                        noise = 0.2f,
                        voxelGap = 0.05f,
                        ySquash = 0.95f,
                        resolution = 10,
                        glow = 1f,
                    ),
                to =
                    AtmosphereState(
                        primaryHue = 249f,
                        secondaryHue = 197f,
                        saturation = 0.85f,
                        lightness = 0.6f,
                        bipolarStrength = 0f,
                        pattern = AtmospherePattern.PULSE,
                        patternSpeed = 1.3f,
                        pulseAmplitude = 0.04f,
                        pulseFrequency = 0.5f,
                        rotationY = 0.2f,
                        rotationX = -0.2f,
                        surfaceBump = 0.1f,
                        noise = 0.2f,
                        voxelGap = 0.05f,
                        ySquash = 0.95f,
                        resolution = 10,
                        glow = 1f,
                    ),
                fromPresetName = "idle",
                toPresetName = "ready",
                progressLinear = 0.5f,
                progressEased = 0.72f,
                easingName = "overshoot",
                durationSeconds = 1.1f,
            )

        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(AtmosphereTransition.serializer(), transition)
        val decoded = json.decodeFromString(AtmosphereTransition.serializer(), encoded)

        assertEquals(transition, decoded)
    }
}
