package link.socket.phosphor.choreography

import kotlin.math.roundToInt
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmospherePattern
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.AtmosphereTransition
import link.socket.phosphor.timeline.Easing

/**
 * Color-defining parameters captured at transition start.
 *
 * Surfaced by [AtmosphereChoreographer] when a transition involves a
 * bipolar-strength change. Renderers consume both the from- and to-snapshot
 * each frame, evaluate their color output independently, and blend in OKLab
 * space using [AtmosphereChoreographer.colorBlend]. This avoids interpolating
 * hues through forbidden palette regions (for example amber to indigo via
 * pink).
 *
 * @property primaryHue Primary hue in degrees, captured at transition start.
 * @property secondaryHue Secondary hue in degrees, captured at transition start.
 * @property bipolarStrength Two-pole color strength, captured at transition start.
 */
data class AtmosphereColorSnapshot(
    val primaryHue: Float,
    val secondaryHue: Float,
    val bipolarStrength: Float,
)

/**
 * Specification for a single atmosphere transition: duration and easing identifier.
 *
 * The easing identifier is resolved against [Easing.byName] at transition
 * start; unknown identifiers fall back to [Easing.easeInOut].
 *
 * @property durationSeconds Transition duration in seconds; must be > 0.
 * @property easingName Easing identifier resolved via [Easing.byName].
 */
data class AtmosphereTransitionSpec(
    val durationSeconds: Float,
    val easingName: String,
)

/**
 * Continuous interpolator for [AtmosphereState] transitions.
 *
 * The choreographer owns the running atmosphere value; [setAtmosphere]
 * requests a transition to a new target and [update] advances the
 * interpolation one tick at a time. The implementation encodes four
 * cross-cutting concerns that the Lumos prototype validated:
 *
 * - **Phase accumulators.** [pulsePhase] and [patternPhase] are integrated as
 *   `dt * frequency` each frame so callers driving sine waves do not see
 *   phase discontinuities when [AtmosphereState.pulseFrequency] or
 *   [AtmosphereState.patternSpeed] changes during a transition.
 * - **Two-track easing.** Numeric amplitude parameters use eased progress
 *   (e = easingFn(t)). Crossfade weights ([colorBlend], [patternBlend]) use
 *   linear progress so the visible blend animates evenly across the window.
 * - **Snapshot color crossfade.** When a transition involves a bipolar-strength
 *   change between distinct hues, [colorFromSnapshot] and [colorToSnapshot]
 *   capture both color configurations at transition start so renderers can
 *   blend them in OKLab space rather than lerping hue floats through unwanted
 *   palette regions.
 * - **Pattern crossfade.** When the [AtmosphereState.pattern] changes,
 *   [patternFrom] holds the source pattern alongside a linear [patternBlend]
 *   value; downstream consumers compute the mix for both patterns and blend.
 *
 * The choreographer is pure data and math — it surfaces the values renderers
 * need but performs no rendering itself.
 *
 * @param initialAtmosphere Starting atmosphere value; becomes [currentState].
 */
class AtmosphereChoreographer(
    initialAtmosphere: AtmosphereState,
) {
    /** Currently interpolated atmosphere state. Returned from [update]. */
    var currentState: AtmosphereState = initialAtmosphere
        private set

    /** Non-null while a transition is in progress; null before and after. */
    var activeTransition: AtmosphereTransition? = null
        private set

    /**
     * Continuous pulse phase advanced each frame by `dt * pulseFrequency`.
     *
     * Continuity is preserved across atmosphere changes so frequencies can be
     * interpolated without phase discontinuities.
     */
    var pulsePhase: Float = 0f
        private set

    /**
     * Continuous pattern phase advanced each frame by `dt * patternSpeed`.
     *
     * Continuity is preserved across atmosphere changes for the same reason
     * as [pulsePhase].
     */
    var patternPhase: Float = 0f
        private set

    /**
     * Snapshot of the source-side color configuration during an active
     * transition. Null when no transition is active or when the transition
     * does not need crossfading (no bipolar-strength change or matching hues).
     */
    var colorFromSnapshot: AtmosphereColorSnapshot? = null
        private set

    /**
     * Snapshot of the target-side color configuration during an active
     * transition. Null under the same conditions as [colorFromSnapshot].
     */
    var colorToSnapshot: AtmosphereColorSnapshot? = null
        private set

    /** Linear progress in 0..1 for color crossfade; 0 when no transition is active. */
    var colorBlend: Float = 0f
        private set

    /** Linear progress in 0..1 for pattern crossfade; 0 when no transition is active. */
    var patternBlend: Float = 0f
        private set

    /** Source pattern during transitions where the pattern changes; null otherwise. */
    var patternFrom: AtmospherePattern? = null
        private set

    private var sourceState: AtmosphereState = initialAtmosphere
    private var sourcePresetName: String? = reverseLookupPresetName(initialAtmosphere)
    private var targetState: AtmosphereState = initialAtmosphere
    private var targetPresetNameInternal: String? = sourcePresetName
    private var elapsedSeconds: Float = 0f
    private var durationSeconds: Float = 0f
    private var easingName: String = "linear"
    private var easingFn: (Float) -> Float = Easing.linear

    /**
     * Replace the current atmosphere with [target], starting a transition.
     *
     * The transition spec is resolved from the default table by matching
     * the from- and to-preset names against [AtmospherePresets.ALL]; when no
     * match is found the lookup falls back to [DefaultSpec] (1.1s easeInOut).
     *
     * If [targetPresetName] is null, the choreographer attempts to identify
     * [target] as a known preset via equality against [AtmospherePresets.ALL].
     * The from-preset name is always determined by reverse-lookup against the
     * current interpolated state.
     *
     * Calling this mid-transition begins a new transition whose source is the
     * current interpolated state — not the prior target.
     *
     * Phase accumulators are not touched here.
     *
     * @param target New atmosphere value.
     * @param targetPresetName Optional caller-supplied preset identifier for [target].
     */
    fun setAtmosphere(
        target: AtmosphereState,
        targetPresetName: String? = null,
    ) {
        val resolvedTargetName = targetPresetName ?: reverseLookupPresetName(target)
        val resolvedFromName = reverseLookupPresetName(currentState) ?: sourcePresetName
        val spec = resolveSpec(resolvedFromName, resolvedTargetName)
        val source = currentState

        sourceState = source
        sourcePresetName = resolvedFromName
        targetState = target
        targetPresetNameInternal = resolvedTargetName
        elapsedSeconds = 0f
        durationSeconds = spec.durationSeconds
        easingName = spec.easingName
        easingFn = Easing.byName(spec.easingName) ?: Easing.easeInOut

        patternFrom = if (source.pattern != target.pattern) source.pattern else null
        patternBlend = 0f

        val huesDiffer =
            source.primaryHue != target.primaryHue ||
                source.secondaryHue != target.secondaryHue
        val anyBipolar = source.bipolarStrength > 0f || target.bipolarStrength > 0f
        if (anyBipolar && huesDiffer) {
            colorFromSnapshot =
                AtmosphereColorSnapshot(
                    primaryHue = source.primaryHue,
                    secondaryHue = source.secondaryHue,
                    bipolarStrength = source.bipolarStrength,
                )
            colorToSnapshot =
                AtmosphereColorSnapshot(
                    primaryHue = target.primaryHue,
                    secondaryHue = target.secondaryHue,
                    bipolarStrength = target.bipolarStrength,
                )
        } else {
            colorFromSnapshot = null
            colorToSnapshot = null
        }
        colorBlend = 0f

        activeTransition =
            AtmosphereTransition(
                from = source,
                to = target,
                fromPresetName = resolvedFromName,
                toPresetName = resolvedTargetName,
                progressLinear = 0f,
                progressEased = 0f,
                easingName = easingName,
                durationSeconds = durationSeconds,
            )
    }

    /**
     * Advance the choreographer by [dt] seconds.
     *
     * When no transition is active, returns [currentState] unchanged but still
     * integrates [pulsePhase] and [patternPhase] using the current state's
     * frequencies.
     *
     * @param dt Frame delta in seconds; must be >= 0.
     * @return The interpolated atmosphere state for this tick.
     */
    fun update(dt: Float): AtmosphereState {
        require(dt >= 0f) { "dt must be >= 0, got $dt" }

        val transition = activeTransition
        if (transition == null) {
            pulsePhase += dt * currentState.pulseFrequency
            patternPhase += dt * currentState.patternSpeed
            return currentState
        }

        elapsedSeconds += dt
        val linear =
            if (durationSeconds <= 0f) {
                1f
            } else {
                (elapsedSeconds / durationSeconds).coerceIn(0f, 1f)
            }
        val eased = easingFn(linear).coerceIn(0f, 1f)

        val interpolated = interpolate(sourceState, targetState, eased)
        currentState = interpolated
        colorBlend = linear
        patternBlend = linear

        pulsePhase += dt * interpolated.pulseFrequency
        patternPhase += dt * interpolated.patternSpeed

        if (linear >= 1f) {
            currentState = targetState
            sourceState = targetState
            sourcePresetName = targetPresetNameInternal
            activeTransition = null
            colorFromSnapshot = null
            colorToSnapshot = null
            patternFrom = null
            colorBlend = 0f
            patternBlend = 0f
        } else {
            activeTransition =
                AtmosphereTransition(
                    from = sourceState,
                    to = targetState,
                    fromPresetName = sourcePresetName,
                    toPresetName = targetPresetNameInternal,
                    progressLinear = linear,
                    progressEased = eased,
                    easingName = easingName,
                    durationSeconds = durationSeconds,
                )
        }

        return currentState
    }

    private fun interpolate(
        from: AtmosphereState,
        to: AtmosphereState,
        t: Float,
    ): AtmosphereState =
        AtmosphereState(
            primaryHue = lerp(from.primaryHue, to.primaryHue, t),
            secondaryHue = lerp(from.secondaryHue, to.secondaryHue, t),
            saturation = lerp(from.saturation, to.saturation, t),
            lightness = lerp(from.lightness, to.lightness, t),
            bipolarStrength = lerp(from.bipolarStrength, to.bipolarStrength, t),
            pattern = to.pattern,
            patternSpeed = lerp(from.patternSpeed, to.patternSpeed, t),
            pulseAmplitude = lerp(from.pulseAmplitude, to.pulseAmplitude, t),
            pulseFrequency = lerp(from.pulseFrequency, to.pulseFrequency, t),
            rotationY = lerp(from.rotationY, to.rotationY, t),
            rotationX = lerp(from.rotationX, to.rotationX, t),
            surfaceBump = lerp(from.surfaceBump, to.surfaceBump, t),
            noise = lerp(from.noise, to.noise, t),
            voxelGap = lerp(from.voxelGap, to.voxelGap, t),
            ySquash = lerp(from.ySquash, to.ySquash, t),
            resolution = lerp(from.resolution.toFloat(), to.resolution.toFloat(), t).roundToInt(),
            glow = lerp(from.glow, to.glow, t),
        )

    private fun lerp(
        from: Float,
        to: Float,
        t: Float,
    ): Float = from + (to - from) * t

    private fun reverseLookupPresetName(state: AtmosphereState): String? =
        AtmospherePresets.ALL.firstOrNull { (_, presetState) -> presetState == state }?.first

    private fun resolveSpec(
        fromName: String?,
        toName: String?,
    ): AtmosphereTransitionSpec {
        if (fromName == null || toName == null) return DefaultSpec
        return DefaultTransitionTable[fromName to toName] ?: DefaultSpec
    }

    companion object {
        /**
         * Fallback transition spec used when the lookup against
         * [DefaultTransitionTable] does not match. 1.1 seconds, easeInOut.
         */
        val DefaultSpec: AtmosphereTransitionSpec =
            AtmosphereTransitionSpec(durationSeconds = 1.1f, easingName = "easeInOut")

        /**
         * Canonical transition table for the five Lumos atmosphere presets.
         *
         * Keys are `(fromPresetName, toPresetName)` pairs matching the lower-case
         * names registered in [AtmospherePresets.ALL]. Any pair not present in
         * this map resolves to [DefaultSpec].
         */
        private val DefaultTransitionTable: Map<Pair<String, String>, AtmosphereTransitionSpec> =
            mapOf(
                ("idle" to "listening") to AtmosphereTransitionSpec(0.6f, "eager"),
                ("idle" to "thinking") to AtmosphereTransitionSpec(0.8f, "easeOut"),
                ("idle" to "uncertain") to AtmosphereTransitionSpec(1.4f, "easeInOut"),
                ("idle" to "ready") to AtmosphereTransitionSpec(1.1f, "overshoot"),
                ("listening" to "idle") to AtmosphereTransitionSpec(0.9f, "easeInOut"),
                ("listening" to "thinking") to AtmosphereTransitionSpec(0.75f, "settled"),
                ("listening" to "uncertain") to AtmosphereTransitionSpec(1.5f, "easeInOut"),
                ("listening" to "ready") to AtmosphereTransitionSpec(1.05f, "overshoot"),
                ("thinking" to "idle") to AtmosphereTransitionSpec(1.0f, "easeInOut"),
                ("thinking" to "listening") to AtmosphereTransitionSpec(0.65f, "eager"),
                ("thinking" to "uncertain") to AtmosphereTransitionSpec(1.65f, "easeInOut"),
                ("thinking" to "ready") to AtmosphereTransitionSpec(0.95f, "overshoot"),
                ("uncertain" to "idle") to AtmosphereTransitionSpec(1.15f, "easeInOut"),
                ("uncertain" to "listening") to AtmosphereTransitionSpec(0.6f, "easeInOut"),
                ("uncertain" to "thinking") to AtmosphereTransitionSpec(0.65f, "easeInOut"),
                ("uncertain" to "ready") to AtmosphereTransitionSpec(0.95f, "easeInOut"),
                ("ready" to "idle") to AtmosphereTransitionSpec(1.5f, "easeInOut"),
                ("ready" to "listening") to AtmosphereTransitionSpec(0.65f, "eager"),
                ("ready" to "thinking") to AtmosphereTransitionSpec(0.85f, "settled"),
                ("ready" to "uncertain") to AtmosphereTransitionSpec(1.7f, "easeInOut"),
            )
    }
}
