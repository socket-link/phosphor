package link.socket.phosphor.field

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import link.socket.phosphor.math.Vector3

/**
 * PHO-57 Task B: the spring settles within its duration without overshoot,
 * identity is kept by index, an interrupted trip carries its velocity, and
 * DIRECT travels straight.
 */
class FormMorphTest {
    private val from = listOf(Vector3(-1f, 0f, 0f), Vector3(0f, 0.5f, 0f), Vector3(0.2f, -0.3f, 0.9f))
    private val to = listOf(Vector3(1f, 0f, 0f), Vector3(0f, -0.5f, 0f), Vector3(-0.2f, 0.3f, -0.9f))

    @Test
    fun `snapTo puts every cell at its target with no velocity`() {
        val morph = FormMorph(3)
        morph.snapTo(to)
        assertContentEquals(flatten(to), morph.positions)
        assertTrue(morph.velocities.all { it == 0f })
        assertFalse(morph.isSettling)
        assertEquals(1f, morph.progress)
    }

    @Test
    fun `the spring settles on its targets within the duration and never overshoots`() {
        val morph = FormMorph(3)
        morph.snapTo(from)
        morph.retarget(to, choreography = Choreography.DIRECT, durationSeconds = 2.4f)
        assertTrue(morph.isSettling)

        var steps = 0
        while (morph.isSettling) {
            morph.step(1f / 60f, to)
            steps++
            // Along the straight line, the cell never passes its destination.
            for (i in 0 until 3) {
                val x = morph.positions[3 * i]
                val lo = minOf(from[i].x, to[i].x)
                val hi = maxOf(from[i].x, to[i].x)
                assertTrue(x >= lo - 1e-5f && x <= hi + 1e-5f, "cell $i overshot on step $steps: x=$x")
            }
        }
        assertTrue(steps in 144..145, "2.4 s at 60 fps is 144 steps, took $steps")
        assertPositions(to, morph.positions, 1e-4f)
        assertTrue(morph.velocities.all { abs(it) < 1e-3f })
        assertEquals(1f, morph.progress)
    }

    @Test
    fun `cell i always heads for the target its assignment names`() {
        val morph = FormMorph(3)
        morph.snapTo(from)
        val assignment = intArrayOf(2, 0, 1)
        morph.retarget(to, assignment, Choreography.DIRECT, durationSeconds = 1f)
        repeat(120) { morph.step(1f / 60f, to) }
        assertPositions(assignment.map { to[it] }, morph.positions, 1e-4f)
    }

    @Test
    fun `step allocates nothing and reuses the same arrays`() {
        val morph = FormMorph(3)
        val positions = morph.positions
        val velocities = morph.velocities
        morph.snapTo(from)
        morph.retarget(to, durationSeconds = 1f)
        morph.step(0.1f, to)
        assertTrue(positions === morph.positions && velocities === morph.velocities)
    }

    @Test
    fun `a retarget mid-trip keeps the velocity the cells were carrying`() {
        val morph = FormMorph(1)
        morph.snapTo(listOf(Vector3.ZERO))
        val far = listOf(Vector3(2f, 0f, 0f))
        morph.retarget(far, choreography = Choreography.DIRECT, durationSeconds = 2.4f)
        repeat(12) { morph.step(1f / 60f, far) }
        val carried = morph.velocities[0]
        assertTrue(carried > 0.5f, "the cell should be moving toward +x, velocity was $carried")

        // Turn back toward the origin. The cell keeps flying +x for a moment before the spring wins.
        val back = listOf(Vector3.ZERO)
        morph.retarget(back, choreography = Choreography.DIRECT, durationSeconds = 2.4f)
        assertEquals(carried, morph.velocities[0], "retarget must not touch velocity")
        val before = morph.positions[0]
        morph.step(1f / 60f, back)
        assertTrue(morph.positions[0] > before, "momentum carries the cell on past the turn")
        while (morph.isSettling) morph.step(1f / 60f, back)
        assertEquals(0f, morph.positions[0], 1e-4f)
    }

    @Test
    fun `DIRECT keeps every cell on the straight line to its target`() {
        val morph = FormMorph(1)
        morph.snapTo(listOf(Vector3(-1f, 0f, 0f)))
        val target = listOf(Vector3(1f, 0f, 0f))
        morph.retarget(target, choreography = Choreography.DIRECT, durationSeconds = 2f)
        while (morph.isSettling) {
            morph.step(1f / 30f, target)
            assertEquals(0f, morph.positions[1], 1e-6f)
            assertEquals(0f, morph.positions[2], 1e-6f)
        }
    }

    @Test
    fun `FLOW and SCATTER leave the line and still settle on the target`() {
        for (choreography in listOf(Choreography.FLOW, Choreography.SCATTER)) {
            val morph = FormMorph(1)
            morph.snapTo(listOf(Vector3(-1f, 0f, 0f)))
            val target = listOf(Vector3(1f, 0f, 0f))
            morph.retarget(target, choreography = choreography, durationSeconds = 2.4f)
            var offLine = 0f
            while (morph.isSettling) {
                morph.step(1f / 60f, target)
                val y = morph.positions[1]
                val z = morph.positions[2]
                offLine = maxOf(offLine, sqrt(y * y + z * z))
            }
            assertTrue(offLine > 0.02f, "$choreography should arc off the line, max was $offLine")
            assertPositions(target, morph.positions, 5e-3f)
        }
    }

    @Test
    fun `SCATTER arcs further than FLOW on a short trip`() {
        fun peak(choreography: Choreography): Float {
            val morph = FormMorph(1)
            morph.snapTo(listOf(Vector3(0f, 0f, 0f)))
            val target = listOf(Vector3(0.1f, 0f, 0f))
            morph.retarget(target, choreography = choreography, durationSeconds = 2.4f)
            var peak = 0f
            while (morph.isSettling) {
                morph.step(1f / 60f, target)
                peak = maxOf(peak, abs(morph.positions[1]) + abs(morph.positions[2]))
            }
            return peak
        }
        assertTrue(peak(Choreography.SCATTER) > peak(Choreography.FLOW) * 3f)
    }

    @Test
    fun `the arc is deterministic`() {
        fun run(): FloatArray {
            val morph = FormMorph(5)
            morph.snapTo(List(5) { Vector3(it * 0.1f, 0f, 0f) })
            val target = List(5) { Vector3(-it * 0.1f, 0.3f, 0f) }
            morph.retarget(target, choreography = Choreography.SCATTER, durationSeconds = 1f)
            repeat(20) { morph.step(1f / 60f, target) }
            return morph.positions.copyOf()
        }
        assertContentEquals(run(), run())
    }

    @Test
    fun `an eased arc envelope still closes and settles`() {
        val morph = FormMorph(1)
        morph.snapTo(listOf(Vector3.ZERO))
        val target = listOf(Vector3(0f, 0f, 1f))
        morph.retarget(target, choreography = Choreography.SCATTER, durationSeconds = 1f, arcEasing = { it * it })
        while (morph.isSettling) morph.step(1f / 60f, target)
        assertPositions(target, morph.positions, 2e-2f)
    }

    @Test
    fun `a zero duration snaps through the assignment`() {
        val morph = FormMorph(3)
        morph.snapTo(from)
        morph.retarget(to, intArrayOf(1, 2, 0), durationSeconds = 0f)
        assertFalse(morph.isSettling)
        assertPositions(listOf(to[1], to[2], to[0]), morph.positions, 0f)
    }

    @Test
    fun `a moving target is followed`() {
        val morph = FormMorph(1)
        morph.snapTo(listOf(Vector3.ZERO))
        morph.retarget(listOf(Vector3(1f, 0f, 0f)), choreography = Choreography.DIRECT, durationSeconds = 2f)
        var t = 0f
        while (morph.isSettling) {
            t += 1f / 60f
            morph.step(1f / 60f, listOf(Vector3(1f, t, 0f)))
        }
        assertTrue(
            abs(morph.positions[1] - t) < 0.3f,
            "y should track the moving target, was ${morph.positions[1]} vs $t",
        )
    }

    @Test
    fun `progress is the step response from rest`() {
        val morph = FormMorph(1)
        morph.snapTo(listOf(Vector3.ZERO))
        val target = listOf(Vector3(1f, 0f, 0f))
        morph.retarget(target, choreography = Choreography.DIRECT, durationSeconds = 2.4f)
        assertEquals(0f, morph.progress)
        repeat(30) { morph.step(1f / 60f, target) }
        assertEquals(morph.positions[0], morph.progress, 1e-3f)
    }

    @Test
    fun `rejects mismatched sizes and bad assignments and negative time`() {
        val morph = FormMorph(2)
        assertFailsWith<IllegalArgumentException> { morph.snapTo(from) }
        morph.snapTo(from.take(2))
        assertFailsWith<IllegalArgumentException> { morph.retarget(to, durationSeconds = -1f) }
        assertFailsWith<IllegalArgumentException> { morph.retarget(to.take(2), intArrayOf(0), durationSeconds = 1f) }
        assertFailsWith<IllegalArgumentException> { morph.retarget(to.take(2), intArrayOf(0, 5), durationSeconds = 1f) }
        morph.retarget(to.take(2), durationSeconds = 1f)
        assertFailsWith<IllegalArgumentException> { morph.step(-0.1f, to.take(2)) }
        assertFailsWith<IllegalArgumentException> { morph.step(0.1f, to.take(1)) }
        assertFailsWith<IllegalArgumentException> { FormMorph(-1) }
    }

    private fun flatten(points: List<Vector3>): FloatArray =
        FloatArray(points.size * 3) { i ->
            val p = points[i / 3]
            when (i % 3) {
                0 -> p.x
                1 -> p.y
                else -> p.z
            }
        }

    private fun assertPositions(
        expected: List<Vector3>,
        actual: FloatArray,
        tolerance: Float,
    ) {
        val flat = flatten(expected)
        for (i in flat.indices) {
            assertTrue(abs(flat[i] - actual[i]) <= tolerance, "component $i: expected ${flat[i]}, was ${actual[i]}")
        }
    }
}
