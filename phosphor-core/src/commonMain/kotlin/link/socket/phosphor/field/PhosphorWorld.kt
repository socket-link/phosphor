package link.socket.phosphor.field

import link.socket.phosphor.math.Vector2

/**
 * Simulation container with spatial partitioning and fixed timestep updates.
 */
class PhosphorWorld(
    val width: Int,
    val height: Int,
    val partitionSize: Int = DEFAULT_PARTITION_SIZE,
    val fixedTimeStep: Float = DEFAULT_FIXED_TIME_STEP,
    private val maxSubSteps: Int = DEFAULT_MAX_SUB_STEPS,
) {
    init {
        require(width > 0) { "width must be > 0, got $width" }
        require(height > 0) { "height must be > 0, got $height" }
        require(partitionSize > 0) { "partitionSize must be > 0, got $partitionSize" }
        require(fixedTimeStep > 0f) { "fixedTimeStep must be > 0, got $fixedTimeStep" }
        require(maxSubSteps > 0) { "maxSubSteps must be > 0, got $maxSubSteps" }
    }

    private val volumesById = linkedMapOf<String, Volume>()
    private val edgeForces = mutableListOf<EdgeForce>()
    private val solvers = mutableListOf<Solver>(CoupledFluidSolver())
    private val partitions = mutableMapOf<PartitionKey, MutableSet<String>>()

    private var accumulator: Float = 0f
    var simulationTime: Float = 0f
        private set

    val volumeCount: Int get() = volumesById.size
    val edgeForceCount: Int get() = edgeForces.size
    val solverCount: Int get() = solvers.size

    fun addVolume(volume: Volume) {
        validateBounds(volume.bounds)
        require(!volumesById.containsKey(volume.id)) { "Volume '${volume.id}' already exists" }

        volumesById[volume.id] = volume
        indexVolume(volume)
    }

    fun removeVolume(volumeId: String): Volume? {
        val removed = volumesById.remove(volumeId) ?: return null
        deindexVolume(removed)
        edgeForces.removeAll { it.fromVolumeId == volumeId || it.toVolumeId == volumeId }
        return removed
    }

    fun getVolume(volumeId: String): Volume? = volumesById[volumeId]

    fun allVolumes(): List<Volume> = volumesById.values.toList()

    fun volumesAt(
        x: Int,
        y: Int,
    ): List<Volume> {
        if (x < 0 || x >= width || y < 0 || y >= height) return emptyList()

        val key = partitionKeyFor(x = x, y = y)
        val ids = partitions[key] ?: return emptyList()

        return ids
            .mapNotNull { id -> volumesById[id] }
            .filter { volume -> volume.contains(x, y) }
    }

    fun addEdgeForce(edgeForce: EdgeForce) {
        require(volumesById.containsKey(edgeForce.fromVolumeId)) {
            "Unknown source volume '${edgeForce.fromVolumeId}'"
        }
        require(volumesById.containsKey(edgeForce.toVolumeId)) {
            "Unknown target volume '${edgeForce.toVolumeId}'"
        }
        edgeForces.add(edgeForce)
    }

    fun allEdgeForces(): List<EdgeForce> = edgeForces.toList()

    fun clearEdgeForces() {
        edgeForces.clear()
    }

    /**
     * Adds bidirectional edges between all touching volumes.
     *
     * @return number of edges created
     */
    fun connectAdjacentVolumes(
        transferFunction: EdgeTransferFunction = EdgeForce.DEFAULT_TRANSFER_FUNCTION,
        momentumScale: Float = EdgeForce.DEFAULT_MOMENTUM_SCALE,
    ): Int {
        var created = 0
        val volumes = volumesById.values.toList()

        for (i in volumes.indices) {
            for (j in i + 1 until volumes.size) {
                val first = volumes[i]
                val second = volumes[j]

                if (!first.isAdjacentTo(second)) continue

                val forward =
                    EdgeForce(
                        fromVolumeId = first.id,
                        toVolumeId = second.id,
                        transferFunction = transferFunction,
                        momentumScale = momentumScale,
                    )
                val reverse =
                    EdgeForce(
                        fromVolumeId = second.id,
                        toVolumeId = first.id,
                        transferFunction = transferFunction,
                        momentumScale = momentumScale,
                    )

                if (!edgeForces.contains(forward)) {
                    edgeForces.add(forward)
                    created++
                }
                if (!edgeForces.contains(reverse)) {
                    edgeForces.add(reverse)
                    created++
                }
            }
        }

        return created
    }

    fun addSolver(solver: Solver) {
        solvers.add(solver)
    }

    fun clearSolvers() {
        solvers.clear()
    }

    fun setSolvers(newSolvers: List<Solver>) {
        solvers.clear()
        solvers.addAll(newSolvers)
    }

    fun allSolvers(): List<Solver> = solvers.toList()

    /**
     * Advance the simulation by variable wall-clock delta.
     *
     * @return number of fixed steps executed
     */
    fun update(deltaTime: Float): Int {
        require(deltaTime >= 0f) { "deltaTime must be >= 0, got $deltaTime" }

        accumulator += deltaTime
        var steps = 0

        while (accumulator >= fixedTimeStep && steps < maxSubSteps) {
            step(fixedTimeStep)
            accumulator -= fixedTimeStep
            simulationTime += fixedTimeStep
            steps++
        }

        return steps
    }

    /**
     * Advance by exactly one deterministic step.
     */
    fun step(deltaTime: Float = fixedTimeStep) {
        require(deltaTime > 0f) { "deltaTime must be > 0, got $deltaTime" }
        if (volumesById.isEmpty()) return

        val activeSolvers = if (solvers.isEmpty()) listOf(DEFAULT_SOLVER) else solvers
        val interleavedDelta = deltaTime / activeSolvers.size

        activeSolvers.forEach { solver ->
            solver.solve(this, interleavedDelta)
            applyEdgeForces(interleavedDelta)
        }

        volumesById.values.forEach { volume ->
            volume.syncParticleOutput(simulationTime)
        }
    }

    /**
     * Snapshot current fluid state as a particle system for rendering.
     */
    fun toParticleSystem(maxParticles: Int = totalParticleBudget()): ParticleSystem {
        val budget = maxParticles.coerceAtLeast(0)
        val renderSystem =
            ParticleSystem(
                maxParticles = budget,
                drag = 0f,
                gravity = Vector2.ZERO,
                lifeDecayRate = 0f,
            )

        if (budget == 0) return renderSystem

        volumesById.values.forEach { volume ->
            renderSystem.addParticles(volume.renderParticles())
        }

        return renderSystem
    }

    internal fun mutableVolumes(): Collection<Volume> = volumesById.values

    private fun applyEdgeForces(deltaTime: Float) {
        edgeForces.forEach { edge ->
            edge.apply(this, deltaTime)
        }
    }

    private fun validateBounds(bounds: VolumeBounds) {
        require(bounds.x >= 0 && bounds.y >= 0) {
            "Volume bounds must start inside world; got (${bounds.x}, ${bounds.y})"
        }
        require(bounds.maxXExclusive <= width) {
            "Volume maxX (${bounds.maxXExclusive}) exceeds world width ($width)"
        }
        require(bounds.maxYExclusive <= height) {
            "Volume maxY (${bounds.maxYExclusive}) exceeds world height ($height)"
        }
    }

    private fun indexVolume(volume: Volume) {
        partitionKeysFor(volume.bounds).forEach { key ->
            partitions.getOrPut(key) { linkedSetOf() }.add(volume.id)
        }
    }

    private fun deindexVolume(volume: Volume) {
        partitionKeysFor(volume.bounds).forEach { key ->
            val bucket = partitions[key] ?: return@forEach
            bucket.remove(volume.id)
            if (bucket.isEmpty()) {
                partitions.remove(key)
            }
        }
    }

    private fun partitionKeysFor(bounds: VolumeBounds): Sequence<PartitionKey> {
        val minCellX = bounds.x / partitionSize
        val maxCellX = (bounds.maxXExclusive - 1) / partitionSize
        val minCellY = bounds.y / partitionSize
        val maxCellY = (bounds.maxYExclusive - 1) / partitionSize

        return sequence {
            for (cellY in minCellY..maxCellY) {
                for (cellX in minCellX..maxCellX) {
                    yield(PartitionKey(cellX, cellY))
                }
            }
        }
    }

    private fun partitionKeyFor(
        x: Int,
        y: Int,
    ): PartitionKey = PartitionKey(x / partitionSize, y / partitionSize)

    private fun totalParticleBudget(): Int =
        volumesById.values.sumOf { volume -> volume.particleBudget }.coerceAtLeast(1)

    private data class PartitionKey(
        val x: Int,
        val y: Int,
    )

    companion object {
        const val DEFAULT_PARTITION_SIZE: Int = 8
        const val DEFAULT_FIXED_TIME_STEP: Float = 1f / 60f
        const val DEFAULT_MAX_SUB_STEPS: Int = 8

        private val DEFAULT_SOLVER = CoupledFluidSolver()
    }
}
