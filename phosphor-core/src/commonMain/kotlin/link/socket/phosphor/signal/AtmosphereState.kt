package link.socket.phosphor.signal

import kotlinx.serialization.Serializable

/**
 * Scene-global visual parameter for renderer atmosphere.
 *
 * AtmosphereState describes the global environment through which rendered
 * light propagates. It is not a per-agent state; [AgentVisualState] remains the
 * per-agent counterpart for position, activity, and phase progress.
 *
 * @property primaryHue Primary hue in degrees, expected in 0..360.
 * @property secondaryHue Secondary hue in degrees, expected in 0..360.
 * @property saturation Color saturation, expected in 0..1.
 * @property lightness Color lightness, expected in 0..1.
 * @property bipolarStrength Two-pole color strength; values greater than zero enable bipolar color mode.
 * @property pattern Spatial pattern family.
 * @property patternSpeed Temporal scale for pattern movement.
 * @property pulseAmplitude Radial scale modulation amplitude.
 * @property pulseFrequency Radial scale modulation rate in Hz.
 * @property rotationY Continuous spin rate around the Y axis.
 * @property rotationX Continuous spin rate around the X axis.
 * @property surfaceBump Surface deformation amplitude.
 * @property noise Per-voxel position jitter scale.
 * @property voxelGap Voxel scale-down amount used by renderers that show lattice gaps.
 * @property ySquash Vertical squash ratio.
 * @property resolution Lattice resolution.
 * @property glow Renderer-interpreted atmospheric glow intensity.
 */
@Serializable
data class AtmosphereState(
    val primaryHue: Float,
    val secondaryHue: Float,
    val saturation: Float,
    val lightness: Float,
    val bipolarStrength: Float,
    val pattern: AtmospherePattern,
    val patternSpeed: Float,
    val pulseAmplitude: Float,
    val pulseFrequency: Float,
    val rotationY: Float,
    val rotationX: Float,
    val surfaceBump: Float,
    val noise: Float,
    val voxelGap: Float,
    val ySquash: Float,
    val resolution: Int,
    val glow: Float,
)
