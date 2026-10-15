package link.socket.phosphor.lumos

import kotlinx.serialization.Serializable

/**
 * How an orb is driven and shown, in one table: the knobs a surface driver reads.
 *
 * Framework-free, so a terminal driver can honour the same values a Compose one does;
 * `LumosOrb` in `:phosphor-lumos-compose` is the driver that reads them today.
 *
 * @property speed Multiplies every step of time the driver hands the orb's [LumosFrameSource]:
 *  breath, pattern, spin, atmosphere transitions and glyph windows all run this much faster. An
 *  atmosphere's own rates never change. 0.5 is half speed, 2 is double; must lie in
 *  [LumosConfig.MIN_SPEED]..[LumosConfig.MAX_SPEED].
 * @property density Cell-count multiplier for forms whose cell count is free to vary. The lattice
 *  has no such freedom and ignores it. Must be > 0.
 * @property dotSize Multiplies the drawn radius of every voxel. Must be > 0.
 * @property tiltDegrees How far above the orb's equator the viewer sits. 0, the default, looks
 *  straight on, as every projection has until now. The builder carves a glyph on the face an
 *  untilted viewer sees, so at a non-zero tilt the carve sits below the overlay drawn at the
 *  orb's centre, until carving learns the view.
 * @property paused Stop the clock. The orb holds its last frame, and a new atmosphere set while
 *  it is paused still draws, once, held still.
 * @property reducedMotion Hold still: draw the current state's pose with no breath, drift or spin,
 *  land atmosphere transitions on their target at once, and show a glyph at full strength for its
 *  whole window instead of fading it. [PlatformMotionPreference] turns this on too, and this flag
 *  can only add to it: a config cannot overrule someone who asked their platform for less motion.
 * @property label What the orb means, for screen readers. Null hides the orb from them.
 */
@Serializable
data class LumosConfig(
    val speed: Float = 1f,
    val density: Float = 1f,
    val dotSize: Float = 1f,
    val tiltDegrees: Float = 0f,
    val paused: Boolean = false,
    val reducedMotion: Boolean = false,
    val label: String? = null,
) {
    init {
        require(speed in MIN_SPEED..MAX_SPEED) { "speed must be in $MIN_SPEED..$MAX_SPEED, was $speed" }
        require(density > 0f) { "density must be > 0, was $density" }
        require(dotSize > 0f) { "dotSize must be > 0, was $dotSize" }
        require(tiltDegrees.isFinite()) { "tiltDegrees must be finite, was $tiltDegrees" }
    }

    companion object {
        /** The slowest [speed]: a tenth of the orb's own pace. */
        const val MIN_SPEED: Float = 0.1f

        /** The fastest [speed]: three times the orb's own pace. */
        const val MAX_SPEED: Float = 3f
    }
}
