package link.socket.phosphor.trace

import kotlinx.serialization.Serializable

/**
 * A named, replayable slice of a [VoxelTrace] frame stream.
 *
 * Segments carve a recorded trace into the cognitive beats a player loops over.
 * Names follow the convention consumed downstream by `TraceStateMachine`: a
 * resting beat is `"idle"`, an active beat is `"thinking"`, and a transition
 * between two beats is written with an arrow, `"idle→thinking"`.
 *
 * @property name Segment label, e.g. `"idle"`, `"thinking"`, `"idle→thinking"`.
 * @property startFrame Index of the first frame in the segment, inclusive. Must be `>= 0`.
 * @property endFrame Index of the last frame in the segment, inclusive. Must be `>= startFrame`.
 * @property loop Whether a player should loop this segment rather than play it once.
 */
@Serializable
data class TraceSegment(
    val name: String,
    val startFrame: Int,
    val endFrame: Int,
    val loop: Boolean = false,
) {
    init {
        require(startFrame >= 0) { "startFrame must be >= 0, was $startFrame" }
        require(endFrame >= startFrame) {
            "endFrame ($endFrame) must be >= startFrame ($startFrame)"
        }
    }

    /** Number of frames covered by this segment, inclusive of both endpoints. */
    val frameCount: Int get() = endFrame - startFrame + 1
}
