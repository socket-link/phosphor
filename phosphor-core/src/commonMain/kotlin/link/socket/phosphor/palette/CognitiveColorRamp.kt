package link.socket.phosphor.palette

import link.socket.phosphor.color.AnsiColorAdapter
import link.socket.phosphor.color.CognitiveColorModel
import link.socket.phosphor.signal.CognitivePhase

/**
 * Maps luminance to ANSI 256-color codes, with phase-specific hue shifts.
 *
 * Color is the emotional register of the visualization. The shape tells you
 * WHERE cognition is happening; the color tells you WHAT KIND of thinking it is.
 *
 * @param phase The cognitive phase this ramp represents
 * @param colorStops ANSI 256-color codes from dark to bright
 */
data class CognitiveColorRamp(
    val phase: CognitivePhase,
    val colorStops: List<Int>,
) {
    init {
        require(colorStops.size >= 2) { "Color ramp must have at least 2 stops" }
    }

    private val lastIndex = colorStops.lastIndex

    /**
     * Get the ANSI 256-color code for a given luminance.
     * @param luminance 0.0 (dark) to 1.0 (bright), clamped internally
     * @return ANSI 256-color code
     */
    fun colorForLuminance(luminance: Float): Int {
        val clamped = luminance.coerceIn(0f, 1f)
        val index = (clamped * lastIndex).toInt().coerceIn(0, lastIndex)
        return colorStops[index]
    }

    /**
     * Get the ANSI 256-color code with ordered dithering.
     *
     * Applies Bayer matrix dithering to break up color banding between
     * adjacent color stops.
     *
     * @param luminance 0.0 (dark) to 1.0 (bright), clamped internally
     * @param screenX Horizontal screen coordinate (for dither pattern)
     * @param screenY Vertical screen coordinate (for dither pattern)
     * @return ANSI 256-color code
     */
    fun colorForLuminanceDithered(
        luminance: Float,
        screenX: Int,
        screenY: Int,
    ): Int {
        val clamped = luminance.coerceIn(0f, 1f)
        val scaled = clamped * lastIndex
        val base = scaled.toInt().coerceIn(0, lastIndex)
        if (base >= lastIndex) return colorStops[lastIndex]
        val frac = scaled - base
        val index = if (frac > BayerDither.threshold(screenX, screenY)) base + 1 else base
        return colorStops[index]
    }

    companion object {
        private val ansiAdapter = AnsiColorAdapter.DEFAULT

        private fun fromModel(
            phase: CognitivePhase,
            modelPhase: CognitivePhase = phase,
        ): CognitiveColorRamp {
            val stops =
                CognitiveColorModel.phaseRampFor(modelPhase).stops.map { color ->
                    ansiAdapter.ansi256Code(color)
                }
            return CognitiveColorRamp(phase = phase, colorStops = stops)
        }

        /** Cool blues -> white (sensory, exploratory) */
        val PERCEIVE = fromModel(CognitivePhase.PERCEIVE)

        /** Dark amber -> warm gold (memory, warmth) */
        val RECALL = fromModel(CognitivePhase.RECALL)

        /** Teal -> cyan (structured, deliberate) */
        val PLAN = fromModel(CognitivePhase.PLAN)

        /** Red -> yellow -> white (discharge, energy) */
        val EXECUTE = fromModel(CognitivePhase.EXECUTE)

        /** Purple -> dim lavender (reflection, settling) */
        val EVALUATE = fromModel(CognitivePhase.EVALUATE)

        /** Neutral gray ramp for LOOP/NONE phases */
        val NEUTRAL = fromModel(phase = CognitivePhase.NONE, modelPhase = CognitivePhase.NONE)

        /**
         * Get the color ramp for a cognitive phase.
         */
        fun forPhase(phase: CognitivePhase): CognitiveColorRamp =
            when (phase) {
                CognitivePhase.PERCEIVE -> PERCEIVE
                CognitivePhase.RECALL -> RECALL
                CognitivePhase.PLAN -> PLAN
                CognitivePhase.EXECUTE -> EXECUTE
                CognitivePhase.EVALUATE -> EVALUATE
                CognitivePhase.LOOP -> NEUTRAL
                CognitivePhase.NONE -> NEUTRAL
            }
    }
}
