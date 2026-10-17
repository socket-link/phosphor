package link.socket.phosphor.lumos.surface

/**
 * Where a [LumosSurfaceState] is: the orb at rest in its slot, its cells on their way to a
 * target, or a target solid with the cells gone into it.
 */
sealed interface SurfacePhase {
    /** The orb is an orb, drawn and stepped by [LumosSurfaceOrb]; every target is invisible. */
    data object Orb : SurfacePhase

    /**
     * The cells are flying to target [id]: the overlay draws them and steps the source, and
     * [LumosSurfaceState.arrival] says how far they are.
     */
    data class Assembling(val id: String) : SurfacePhase

    /** Target [id] is solid: the cells have become it, the overlay is gone and nothing ticks. */
    data class Solid(val id: String) : SurfacePhase
}
