package link.socket.phosphor.lumos

/**
 * The fade envelope every layer of a glyph rises and falls on.
 *
 * A glyph is shown twice on purpose: [VoxelFrameBuilder] carves it into the orb, and each
 * surface draws a crisp overlay on top. Both layers read their strength from here so they
 * agree on every surface — [GlyphLifecycle.visibility] delegates to [visibility], and the
 * surface overlays call it with [VoxelGlyphState.progress].
 *
 * Progress is the position in the display window; visibility is this envelope applied to it.
 */
object GlyphEnvelope {
    /** Progress at which the fade-in completes. */
    const val FADE_IN_END: Float = 0.20f

    /** Progress at which the fade-out begins. */
    const val FADE_OUT_START: Float = 0.80f

    /**
     * Visibility, `0..1`, at [progress] through the display window: smoothstep in over the
     * first 20%, full through the middle, smoothstep out over the last 20%. [progress] is
     * clamped to `0..1`, so it is `0` at both ends of the window.
     */
    fun visibility(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return when {
            p < FADE_IN_END -> smoothstep(0f, FADE_IN_END, p)
            p > FADE_OUT_START -> 1f - smoothstep(FADE_OUT_START, 1f, p)
            else -> 1f
        }
    }

    private fun smoothstep(
        edge0: Float,
        edge1: Float,
        x: Float,
    ): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
