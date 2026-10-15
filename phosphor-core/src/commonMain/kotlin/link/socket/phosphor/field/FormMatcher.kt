package link.socket.phosphor.field

import kotlin.math.floor
import link.socket.phosphor.math.Vector3

/**
 * One-to-one nearest destinations: which target each origin should travel to
 * when a body reshapes, so every cell is reused and none is faded out for a
 * replacement.
 *
 * Greedy in origin index order: origin `0` takes the nearest free target, then
 * origin `1` takes the nearest target still free, and so on. Distance is the
 * full 3D squared distance; an exact tie goes to the lower target index, so the
 * result depends only on the inputs and never on how the search is organised.
 * The result is a permutation when the counts are equal; with more targets than
 * origins the surplus targets are the ones no origin was assigned.
 *
 * Runs once per morph, not per frame, so it allocates freely. A uniform grid
 * of [partitionSize] cells over the targets' bounding box is the broad phase:
 * each origin searches outward ring by ring and stops as soon as the best found
 * is closer than anything an unvisited ring could hold. Late in the greedy the
 * free targets near an origin may all be taken; after [ringLimit] rings the
 * search gives up on the grid and scans the targets still free, which bounds
 * that tail by the naive cost. Measured on PHO-57 at 4,801 cells: under 1 ms
 * when the forms already coincide, 8–10 ms between a lattice and a Fibonacci
 * sphere, against 20–30 ms for the naive scan.
 *
 * @property partitionSize Grid cell size in the targets' units; `0.2` suits
 *  unit-sphere space. Smaller is not faster: a fine grid is mostly empty by the
 *  end of the greedy and costs more rings per origin.
 * @property ringLimit Rings to search before falling back to the free list; `>= 0`.
 * @throws IllegalArgumentException when [partitionSize] is not positive or [ringLimit] is negative.
 */
class FormMatcher(
    val partitionSize: Float = DEFAULT_PARTITION_SIZE,
    val ringLimit: Int = DEFAULT_RING_LIMIT,
) {
    init {
        require(partitionSize > 0f && partitionSize.isFinite()) { "partitionSize must be > 0, was $partitionSize" }
        require(ringLimit >= 0) { "ringLimit must be >= 0, was $ringLimit" }
    }

    /**
     * The target index each origin travels to: `result[i]` is the target of
     * origin `i`, and no two origins share one.
     *
     * @throws IllegalArgumentException when there are fewer targets than origins.
     */
    fun match(
        origins: List<Vector3>,
        targets: List<Vector3>,
    ): IntArray {
        val originCount = origins.size
        val targetCount = targets.size
        require(targetCount >= originCount) { "need at least $originCount targets, have $targetCount" }
        if (originCount == 0) return IntArray(0)

        val grid = Grid(targets, partitionSize)
        val free = FreeList(targetCount)
        val out = IntArray(originCount)

        for (i in 0 until originCount) {
            val origin = origins[i]
            var best = -1
            var bestDistance = Float.POSITIVE_INFINITY

            val cx = grid.cellX(origin.x)
            val cy = grid.cellY(origin.y)
            val cz = grid.cellZ(origin.z)
            var ring = 0
            var exhausted = false
            while (true) {
                if (ring > grid.maxRing) {
                    exhausted = true
                    break
                }
                if (ring > ringLimit) break
                // Only the shell of the ring is new: everything inside was scanned on earlier rings.
                val z0 = cz - ring
                val z1 = cz + ring
                val y0 = cy - ring
                val y1 = cy + ring
                val x0 = cx - ring
                val x1 = cx + ring
                for (z in maxOf(z0, 0)..minOf(z1, grid.nz - 1)) {
                    val zEdge = z == z0 || z == z1
                    for (y in maxOf(y0, 0)..minOf(y1, grid.ny - 1)) {
                        val yEdge = y == y0 || y == y1
                        if (zEdge || yEdge) {
                            for (x in maxOf(x0, 0)..minOf(x1, grid.nx - 1)) {
                                val bucket = grid.bucket(x, y, z)
                                val start = grid.bucketStart[bucket]
                                val end = start + grid.bucketLive[bucket]
                                for (k in start until end) {
                                    val j = grid.items[k]
                                    val d = distanceSquared(origin, targets[j])
                                    if (d < bestDistance || (d == bestDistance && j < best)) {
                                        bestDistance = d
                                        best = j
                                    }
                                }
                            }
                        } else {
                            if (x0 >= 0) {
                                val bucket = grid.bucket(x0, y, z)
                                val start = grid.bucketStart[bucket]
                                val end = start + grid.bucketLive[bucket]
                                for (k in start until end) {
                                    val j = grid.items[k]
                                    val d = distanceSquared(origin, targets[j])
                                    if (d < bestDistance || (d == bestDistance && j < best)) {
                                        bestDistance = d
                                        best = j
                                    }
                                }
                            }
                            if (x1 < grid.nx && x1 != x0) {
                                val bucket = grid.bucket(x1, y, z)
                                val start = grid.bucketStart[bucket]
                                val end = start + grid.bucketLive[bucket]
                                for (k in start until end) {
                                    val j = grid.items[k]
                                    val d = distanceSquared(origin, targets[j])
                                    if (d < bestDistance || (d == bestDistance && j < best)) {
                                        bestDistance = d
                                        best = j
                                    }
                                }
                            }
                        }
                    }
                }
                // A point in ring + 1 or beyond is at least ring × partitionSize from a point
                // inside the centre cell, so a best within that is final.
                if (best >= 0) {
                    val bound = ring * partitionSize
                    if (bestDistance <= bound * bound) break
                }
                ring++
            }
            if (!exhausted && (best < 0 || ring > ringLimit)) {
                // The grid near this origin is empty: the greedy tail. Scan what is left.
                for (k in 0 until free.count) {
                    val j = free.items[k]
                    val d = distanceSquared(origin, targets[j])
                    if (d < bestDistance || (d == bestDistance && j < best)) {
                        bestDistance = d
                        best = j
                    }
                }
            }
            check(best >= 0) { "no free target for origin $i" }
            out[i] = best
            grid.remove(best)
            free.remove(best)
        }
        return out
    }

    /** CSR buckets over the targets' bounding box; a taken target is swap-removed from its bucket. */
    private class Grid(
        targets: List<Vector3>,
        partitionSize: Float,
    ) {
        private val minX: Float
        private val minY: Float
        private val minZ: Float
        private val inverse: Float = 1f / partitionSize
        val nx: Int
        val ny: Int
        val nz: Int
        val maxRing: Int
        val bucketStart: IntArray
        val bucketLive: IntArray
        val items: IntArray
        private val bucketOf: IntArray
        private val slotOf: IntArray

        init {
            var loX = Float.POSITIVE_INFINITY
            var loY = Float.POSITIVE_INFINITY
            var loZ = Float.POSITIVE_INFINITY
            var hiX = Float.NEGATIVE_INFINITY
            var hiY = Float.NEGATIVE_INFINITY
            var hiZ = Float.NEGATIVE_INFINITY
            for (t in targets) {
                if (t.x < loX) loX = t.x
                if (t.y < loY) loY = t.y
                if (t.z < loZ) loZ = t.z
                if (t.x > hiX) hiX = t.x
                if (t.y > hiY) hiY = t.y
                if (t.z > hiZ) hiZ = t.z
            }
            minX = loX
            minY = loY
            minZ = loZ
            nx = cellsAcross(hiX - loX)
            ny = cellsAcross(hiY - loY)
            nz = cellsAcross(hiZ - loZ)
            maxRing = maxOf(nx, ny, nz)

            val n = targets.size
            val bucketCount = nx * ny * nz
            bucketOf = IntArray(n) { bucket(cellX(targets[it].x), cellY(targets[it].y), cellZ(targets[it].z)) }
            bucketLive = IntArray(bucketCount)
            for (b in bucketOf) bucketLive[b]++
            bucketStart = IntArray(bucketCount + 1)
            for (b in 0 until bucketCount) bucketStart[b + 1] = bucketStart[b] + bucketLive[b]
            val fill = bucketStart.copyOf(bucketCount)
            items = IntArray(n)
            for (j in 0 until n) items[fill[bucketOf[j]]++] = j
            slotOf = IntArray(n)
            for (k in 0 until n) slotOf[items[k]] = k
        }

        private fun cellsAcross(extent: Float): Int = (floor(extent * inverse).toInt() + 1).coerceAtLeast(1)

        fun cellX(x: Float): Int = ((x - minX) * inverse).toInt().coerceIn(0, nx - 1)

        fun cellY(y: Float): Int = ((y - minY) * inverse).toInt().coerceIn(0, ny - 1)

        fun cellZ(z: Float): Int = ((z - minZ) * inverse).toInt().coerceIn(0, nz - 1)

        fun bucket(
            x: Int,
            y: Int,
            z: Int,
        ): Int = (z * ny + y) * nx + x

        fun remove(target: Int) {
            val b = bucketOf[target]
            val lastSlot = bucketStart[b] + bucketLive[b] - 1
            val slot = slotOf[target]
            val moved = items[lastSlot]
            items[slot] = moved
            slotOf[moved] = slot
            items[lastSlot] = target
            slotOf[target] = lastSlot
            bucketLive[b]--
        }
    }

    /** Every target not yet taken, in no particular order, swap-removed when taken. */
    private class FreeList(n: Int) {
        val items = IntArray(n) { it }
        private val slotOf = IntArray(n) { it }
        var count = n
            private set

        fun remove(target: Int) {
            val slot = slotOf[target]
            val last = items[count - 1]
            items[slot] = last
            slotOf[last] = slot
            items[count - 1] = target
            slotOf[target] = count - 1
            count--
        }
    }

    companion object {
        /** Grid cell size that measured fastest in unit-sphere space. */
        const val DEFAULT_PARTITION_SIZE: Float = 0.2f

        /** Rings searched before the free-list fallback. */
        const val DEFAULT_RING_LIMIT: Int = 2

        private fun distanceSquared(
            a: Vector3,
            b: Vector3,
        ): Float {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val dz = b.z - a.z
            return dx * dx + dy * dy + dz * dz
        }
    }
}
