package link.socket.phosphor.field

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import link.socket.phosphor.math.Vector3

/**
 * PHO-57 Task B: the grid-accelerated matcher returns exactly what the greedy
 * contract says — the naive scan is the oracle — and the result is always a
 * one-to-one assignment.
 */
class FormMatcherTest {
    @Test
    fun `matches the naive greedy assignment on the lattice and random clouds`() {
        val lattice = VoxelSphere(4).voxels.map { it.normalizedPos }
        val random = Random(0x57)
        val cases =
            listOf(
                lattice to lattice,
                lattice to lattice.shuffled(random),
                lattice to cloud(lattice.size, random),
                cloud(300, random) to cloud(300, random),
                cloud(50, random) to lattice.take(50),
            )
        for ((partition, ringLimit) in listOf(0.05f to 0, 0.2f to 2, 0.5f to 1, 3f to 5)) {
            val matcher = FormMatcher(partitionSize = partition, ringLimit = ringLimit)
            cases.forEachIndexed { index, (origins, targets) ->
                assertContentEquals(
                    naive(origins, targets),
                    matcher.match(origins, targets),
                    "case $index at partition $partition ring $ringLimit",
                )
            }
        }
    }

    @Test
    fun `identical forms match to the identity`() {
        val lattice = VoxelSphere(3).voxels.map { it.normalizedPos }
        assertContentEquals(IntArray(lattice.size) { it }, FormMatcher().match(lattice, lattice))
    }

    @Test
    fun `every origin takes a distinct target`() {
        val random = Random(7)
        val origins = cloud(200, random)
        val targets = cloud(200, random)
        val assignment = FormMatcher().match(origins, targets)
        assertEquals(200, assignment.toSet().size)
        assertTrue(assignment.all { it in targets.indices })
    }

    @Test
    fun `with more targets than origins the surplus is left unassigned`() {
        val random = Random(11)
        val origins = cloud(20, random)
        val targets = cloud(35, random)
        val assignment = FormMatcher().match(origins, targets)
        assertEquals(20, assignment.size)
        assertEquals(20, assignment.toSet().size)
        assertContentEquals(naive(origins, targets), assignment)
    }

    @Test
    fun `ties go to the lower target index`() {
        val origin = listOf(Vector3.ZERO)
        val targets = listOf(Vector3(1f, 0f, 0f), Vector3(-1f, 0f, 0f), Vector3(0f, 1f, 0f))
        assertContentEquals(intArrayOf(0), FormMatcher().match(origin, targets))
    }

    @Test
    fun `greedy order is origin index order`() {
        // Both origins are nearest to target 0; origin 0 gets it and origin 1 settles for target 1.
        val origins = listOf(Vector3(0.1f, 0f, 0f), Vector3(0f, 0f, 0f))
        val targets = listOf(Vector3(0.05f, 0f, 0f), Vector3(5f, 0f, 0f))
        assertContentEquals(intArrayOf(0, 1), FormMatcher().match(origins, targets))
    }

    @Test
    fun `an empty body matches to nothing`() {
        assertContentEquals(IntArray(0), FormMatcher().match(emptyList(), emptyList()))
        assertContentEquals(IntArray(0), FormMatcher().match(emptyList(), listOf(Vector3.ZERO)))
    }

    @Test
    fun `rejects fewer targets than origins and bad tuning`() {
        assertFailsWith<IllegalArgumentException> {
            FormMatcher().match(listOf(Vector3.ZERO, Vector3.UP), listOf(Vector3.ZERO))
        }
        assertFailsWith<IllegalArgumentException> { FormMatcher(partitionSize = 0f) }
        assertFailsWith<IllegalArgumentException> { FormMatcher(ringLimit = -1) }
    }

    private fun cloud(
        count: Int,
        random: Random,
    ): List<Vector3> =
        List(count) {
            Vector3(random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f, random.nextFloat() * 2f - 1f)
        }

    companion object {
        /** The contract, written the slow way: origin order, nearest free, ties to the lower index. */
        fun naive(
            origins: List<Vector3>,
            targets: List<Vector3>,
        ): IntArray {
            val taken = BooleanArray(targets.size)
            return IntArray(origins.size) { i ->
                var best = -1
                var bestDistance = Float.POSITIVE_INFINITY
                for (j in targets.indices) {
                    if (taken[j]) continue
                    val d = (targets[j] - origins[i]).lengthSquared()
                    if (d < bestDistance) {
                        bestDistance = d
                        best = j
                    }
                }
                taken[best] = true
                best
            }
        }
    }
}
