package link.socket.phosphor.signal

import kotlinx.serialization.Serializable

/**
 * Diagnostic snapshot for a transition between two atmosphere states.
 *
 * The transition carries both raw time progress and eased progress so renderer
 * integrations can report exactly which interpolation input produced the
 * current state. Preset names are nullable because either endpoint may be a
 * caller-constructed [AtmosphereState].
 *
 * Default preset transition table planned for AtmosphereChoreographer:
 *
 * | From -> To | Duration (s) | Easing |
 * | --- | ---: | --- |
 * | idle -> listening | 0.6 | eager |
 * | idle -> thinking | 0.8 | easeOut |
 * | idle -> uncertain | 1.4 | easeInOut |
 * | idle -> ready | 1.1 | overshoot |
 * | listening -> idle | 0.9 | easeInOut |
 * | listening -> thinking | 0.75 | settled |
 * | listening -> uncertain | 1.5 | easeInOut |
 * | listening -> ready | 1.05 | overshoot |
 * | thinking -> idle | 1.0 | easeInOut |
 * | thinking -> listening | 0.65 | eager |
 * | thinking -> uncertain | 1.65 | easeInOut |
 * | thinking -> ready | 0.95 | overshoot |
 * | uncertain -> idle | 1.15 | easeInOut |
 * | uncertain -> listening | 0.6 | easeInOut |
 * | uncertain -> thinking | 0.65 | easeInOut |
 * | uncertain -> ready | 0.95 | easeInOut |
 * | ready -> idle | 1.5 | easeInOut |
 * | ready -> listening | 0.65 | eager |
 * | ready -> thinking | 0.85 | settled |
 * | ready -> uncertain | 1.7 | easeInOut |
 * | default fallback | 1.1 | easeInOut |
 *
 * @property from Starting atmosphere state.
 * @property to Target atmosphere state.
 * @property fromPresetName Preset name for [from], or null when [from] is not a known preset.
 * @property toPresetName Preset name for [to], or null when [to] is not a known preset.
 * @property progressLinear Time-based progress, expected in 0..1.
 * @property progressEased Easing-adjusted progress, expected in 0..1.
 * @property easingName Easing identifier used for diagnostics.
 * @property durationSeconds Transition duration in seconds.
 */
@Serializable
data class AtmosphereTransition(
    val from: AtmosphereState,
    val to: AtmosphereState,
    val fromPresetName: String?,
    val toPresetName: String?,
    val progressLinear: Float,
    val progressEased: Float,
    val easingName: String,
    val durationSeconds: Float,
)
