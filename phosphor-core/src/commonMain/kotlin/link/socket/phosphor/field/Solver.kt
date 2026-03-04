package link.socket.phosphor.field

/**
 * Coupled solver contract for interleaved fixed-timestep updates.
 */
fun interface Solver {
    fun solve(
        world: PhosphorWorld,
        deltaTime: Float,
    )
}

/**
 * Default solver: integrates each volume's local state.
 */
class CoupledFluidSolver : Solver {
    override fun solve(
        world: PhosphorWorld,
        deltaTime: Float,
    ) {
        world.mutableVolumes().forEach { volume ->
            volume.integrate(deltaTime)
        }
    }
}
