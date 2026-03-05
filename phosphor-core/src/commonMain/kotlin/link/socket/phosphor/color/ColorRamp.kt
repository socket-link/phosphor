package link.socket.phosphor.color

/**
 * Ordered color stops from dark to bright (or low to high semantic intensity).
 */
data class ColorRamp(
    val stops: List<NeutralColor>,
) {
    init {
        require(stops.size >= 2) { "Color ramp must have at least 2 stops" }
    }

    private val lastIndex = stops.lastIndex

    /**
     * Sample the ramp at t in [0, 1], linearly interpolating between stops.
     */
    fun sample(t: Float): NeutralColor {
        val clamped = t.coerceIn(0f, 1f)
        if (clamped <= 0f) return stops.first()
        if (clamped >= 1f) return stops.last()

        val scaled = clamped * lastIndex
        val lower = scaled.toInt().coerceIn(0, lastIndex)
        val upper = (lower + 1).coerceIn(0, lastIndex)

        if (lower == upper) return stops[lower]

        val localT = scaled - lower
        return NeutralColor.lerp(stops[lower], stops[upper], localT)
    }

    companion object {
        /**
         * Build a linear ramp from start -> end with [steps] inclusive stops.
         */
        fun gradient(
            start: NeutralColor,
            end: NeutralColor,
            steps: Int,
        ): ColorRamp {
            require(steps >= 2) { "steps must be >= 2, got $steps" }

            val generated =
                List(steps) { index ->
                    val t = index.toFloat() / (steps - 1).toFloat()
                    NeutralColor.lerp(start, end, t)
                }
            return ColorRamp(generated)
        }
    }
}
