package link.socket.phosphor.palette

import link.socket.phosphor.signal.AtmospherePattern
import link.socket.phosphor.signal.AtmosphereState

/**
 * Canonical atmosphere presets for Lumos scene-global rendering.
 *
 * These presets use Socket-aligned default hues: 244-degree indigo,
 * 197-degree cyan, 32-degree amber, and 280-degree purple. Consumers can
 * construct their own [AtmosphereState] instances when a different palette or
 * motion profile is needed.
 */
object AtmospherePresets {
    val IDLE =
        AtmosphereState(
            primaryHue = 250f,
            secondaryHue = 175f,
            saturation = 0.85f,
            lightness = 0.60f,
            bipolarStrength = 0.0f,
            pattern = AtmospherePattern.LONGITUDE,
            patternSpeed = 0.25f,
            pulseAmplitude = 0.025f,
            pulseFrequency = 0.30f,
            rotationY = 0.14f,
            rotationX = 0.0f,
            surfaceBump = 0.10f,
            noise = 0.20f,
            voxelGap = 0.05f,
            ySquash = 0.95f,
            resolution = 10,
            glow = 1.0f,
        )

    val LISTENING =
        AtmosphereState(
            primaryHue = 195f,
            secondaryHue = 270f,
            saturation = 0.85f,
            lightness = 0.60f,
            bipolarStrength = 0.0f,
            pattern = AtmospherePattern.PLASMA,
            patternSpeed = 1.15f,
            pulseAmplitude = 0.06f,
            pulseFrequency = 0.5f,
            rotationY = 0.16f,
            rotationX = 0.10f,
            surfaceBump = 0.10f,
            noise = 0.20f,
            voxelGap = 0.05f,
            ySquash = 0.95f,
            resolution = 10,
            glow = 1.0f,
        )

    val THINKING =
        AtmosphereState(
            primaryHue = 244f,
            secondaryHue = 185f,
            saturation = 0.85f,
            lightness = 0.60f,
            bipolarStrength = 0.0f,
            pattern = AtmospherePattern.SPIRAL,
            patternSpeed = 0.7f,
            pulseAmplitude = 0.025f,
            pulseFrequency = 0.45f,
            rotationY = 0.45f,
            rotationX = 0.10f,
            surfaceBump = 0.10f,
            noise = 0.20f,
            voxelGap = 0.05f,
            ySquash = 0.95f,
            resolution = 10,
            glow = 1.0f,
        )

    val UNCERTAIN =
        AtmosphereState(
            primaryHue = 32f,
            secondaryHue = 280f,
            saturation = 0.85f,
            lightness = 0.60f,
            bipolarStrength = 0.45f,
            pattern = AtmospherePattern.SPIRAL,
            patternSpeed = 1.0f,
            pulseAmplitude = 0.06f,
            pulseFrequency = 0.16f,
            rotationY = 0.10f,
            rotationX = 0.10f,
            surfaceBump = 0.10f,
            noise = 0.20f,
            voxelGap = 0.05f,
            ySquash = 0.95f,
            resolution = 10,
            glow = 1.0f,
        )

    val READY =
        AtmosphereState(
            primaryHue = 249f,
            secondaryHue = 197f,
            saturation = 0.85f,
            lightness = 0.60f,
            bipolarStrength = 0.0f,
            pattern = AtmospherePattern.PULSE,
            patternSpeed = 1.3f,
            pulseAmplitude = 0.04f,
            pulseFrequency = 0.5f,
            rotationY = 0.20f,
            rotationX = -0.20f,
            surfaceBump = 0.10f,
            noise = 0.20f,
            voxelGap = 0.05f,
            ySquash = 0.95f,
            resolution = 10,
            glow = 1.0f,
        )

    val ALL: List<Pair<String, AtmosphereState>> =
        listOf(
            "idle" to IDLE,
            "listening" to LISTENING,
            "thinking" to THINKING,
            "uncertain" to UNCERTAIN,
            "ready" to READY,
        )

    /**
     * Resolve a canonical preset by name, ignoring case.
     */
    fun byName(name: String): AtmosphereState? =
        ALL.firstOrNull { (presetName, _) -> presetName.equals(name, ignoreCase = true) }?.second
}
