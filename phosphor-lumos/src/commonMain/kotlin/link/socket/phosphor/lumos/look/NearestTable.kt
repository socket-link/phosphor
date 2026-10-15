package link.socket.phosphor.lumos.look

import link.socket.phosphor.math.Vector3

/**
 * Each cell's [reach] nearest other cells, nearest first.
 *
 * Thinking Orbs' `nearest(pts, i, k)`: one pass over the cells per cell,
 * keeping a short sorted list, `O(N² · reach)` once at mount. At 4,801 cells
 * that is 23 million distance checks, measured in the tens of milliseconds on
 * the JVM; build once per form sample and keep it. Ties fall to the lower index,
 * so the table is a pure function of the positions. A cell with fewer than
 * [reach] others lists what there is and `-1` after.
 *
 * @property reach Neighbours kept per cell; `> 0`.
 */
internal class NearestTable(
    positions: List<Vector3>,
    val reach: Int,
) {
    init {
        require(reach > 0) { "reach must be > 0, was $reach" }
    }

    /** Cells in the table. */
    val count: Int = positions.size

    private val table = IntArray(count * reach) { -1 }

    init {
        val bestDistance = FloatArray(reach)
        val bestIndex = IntArray(reach)
        for (i in 0 until count) {
            val origin = positions[i]
            var kept = 0
            for (j in 0 until count) {
                if (j == i) continue
                val other = positions[j]
                val dx = other.x - origin.x
                val dy = other.y - origin.y
                val dz = other.z - origin.z
                val distance = dx * dx + dy * dy + dz * dz
                if (kept == reach && distance >= bestDistance[reach - 1]) continue
                // Insert in order; the list is short, so a shift beats a heap.
                var slot = if (kept < reach) kept else reach - 1
                while (slot > 0 && bestDistance[slot - 1] > distance) {
                    bestDistance[slot] = bestDistance[slot - 1]
                    bestIndex[slot] = bestIndex[slot - 1]
                    slot--
                }
                bestDistance[slot] = distance
                bestIndex[slot] = j
                if (kept < reach) kept++
            }
            val base = i * reach
            for (slot in 0 until kept) table[base + slot] = bestIndex[slot]
        }
    }

    /** The [slot]th nearest cell to [index], or `-1` past the cells there are. */
    fun neighbour(
        index: Int,
        slot: Int,
    ): Int = table[index * reach + slot]
}
