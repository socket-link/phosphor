package link.socket.phosphor.signal

import link.socket.phosphor.math.Vector2

/**
 * What the person is doing to the surface the orb is drawn on: a signal like
 * the cognitive ones, but from the other side of the glass.
 *
 * Nothing in the runtime reads it. [link.socket.phosphor.runtime.CognitiveSceneRuntime]
 * carries it to the snapshot, so neither the atmosphere nor any clock can see
 * the pointer; an output module that wants the orb to answer does so itself.
 *
 * @property pointer Where the person is pointing, `x` and `y` in `[-1, 1]` across
 *  the orb's square with `+y` up — unlike [Vector2.UP], which is screen space.
 *  This is the orb's view space before any tilt. Null when nothing points.
 *  A point outside the square is allowed: the pointer can stray past the orb.
 * @property pressed Whether the pointer is pressed: a button held or a finger down.
 * @throws IllegalArgumentException when [pointer] is not finite.
 */
data class LumosInput(
    val pointer: Vector2? = null,
    val pressed: Boolean = false,
) {
    init {
        if (pointer != null) {
            require(pointer.x.isFinite() && pointer.y.isFinite()) { "pointer must be finite, was $pointer" }
        }
    }

    companion object {
        /** Nothing pointing, nothing pressed. */
        val NONE: LumosInput = LumosInput()
    }
}
