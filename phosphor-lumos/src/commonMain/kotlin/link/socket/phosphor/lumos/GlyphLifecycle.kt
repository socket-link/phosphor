package link.socket.phosphor.lumos

/**
 * State for an active glyph.
 *
 * Tracks remaining time and emits the progress envelope used by frame builders
 * to fade glyph-member voxels in and out.
 */
internal data class GlyphLifecycle(
    val glyph: LumosGlyph,
    val totalDurationSeconds: Float,
    val ageSeconds: Float,
) {
    val progress: Float get() = (ageSeconds / totalDurationSeconds).coerceIn(0f, 1f)

    val isComplete: Boolean get() = ageSeconds >= totalDurationSeconds

    val visibility: Float
        get() {
            val p = progress
            val fadeIn = 0.20f
            val fadeOut = 0.80f
            return when {
                p < fadeIn -> smoothstep(0f, fadeIn, p)
                p > fadeOut -> 1f - smoothstep(fadeOut, 1f, p)
                else -> 1f
            }
        }

    fun advance(dt: Float): GlyphLifecycle = copy(ageSeconds = ageSeconds + dt)

    private fun smoothstep(
        edge0: Float,
        edge1: Float,
        x: Float,
    ): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
