package link.socket.phosphor.lumos

/**
 * State for an active glyph.
 *
 * Tracks remaining time and emits the progress envelope used by frame builders
 * to fade glyph-member voxels in and out.
 *
 * Public so consumers outside this module can drive the same envelope when they
 * overlay a glyph on frames they did not build — replaying a `VoxelTrace`, for
 * instance, where the glyph fires live over baked voxels and nothing upstream
 * is advancing a lifecycle for them.
 *
 * @property glyph Which glyph is being displayed.
 * @property totalDurationSeconds Display window in seconds; must be `> 0`.
 * @property ageSeconds Seconds elapsed since the glyph was queued.
 */
data class GlyphLifecycle(
    val glyph: LumosGlyph,
    val totalDurationSeconds: Float,
    val ageSeconds: Float,
) {
    /** Linear position in the display window, `0..1`. */
    val progress: Float get() = (ageSeconds / totalDurationSeconds).coerceIn(0f, 1f)

    /** True once the display window has fully elapsed. */
    val isComplete: Boolean get() = ageSeconds >= totalDurationSeconds

    /**
     * Fade envelope, `0..1`: [GlyphEnvelope.visibility] at [progress] — smoothstep in
     * over the first 20% of the window, full through the middle, smoothstep out over
     * the last 20%.
     */
    val visibility: Float get() = GlyphEnvelope.visibility(progress)

    /** Age the lifecycle by [dt] seconds. */
    fun advance(dt: Float): GlyphLifecycle = copy(ageSeconds = ageSeconds + dt)
}
