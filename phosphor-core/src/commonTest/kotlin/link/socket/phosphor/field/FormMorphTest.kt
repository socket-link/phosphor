package link.socket.phosphor.field

import kotlin.math.abs
import kotlin.math.exp
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
 * DIRECT travels straight. PHO-74 Task A: forces leave the spring untouched
 * when there are none, and a burst pushes out, decays, and lets the spring
 * bring the body home.
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

    @Test
    fun `an empty force list leaves step byte-identical`() {
        fun stepped(prepare: (FormMorph) -> Unit): Pair<FloatArray, FloatArray> {
            val morph = FormMorph(3)
            morph.snapTo(from)
            prepare(morph)
            morph.retarget(to, choreography = Choreography.FLOW, durationSeconds = 4f)
            repeat(200) { morph.step(1f / 60f, to) }
            return morph.positions.copyOf() to morph.velocities.copyOf()
        }
        val (positions, velocities) = stepped {}
        val emptied =
            stepped {
                it.forces.add(Force.Burst(6f))
                it.forces.clear()
            }
        assertContentEquals(positions, emptied.first)
        assertContentEquals(velocities, emptied.second)
        // A Repel is declared but not applied yet: it must not touch the body either.
        val repelled = stepped { it.forces.add(Force.Repel()) }
        assertContentEquals(positions, repelled.first)
        assertContentEquals(velocities, repelled.second)
    }

    @Test
    fun `a burst moves every cell outward on the first step`() {
        val morph = restingBody(ring)
        morph.forces.add(Force.Burst(6f))
        val before = radii(morph)
        morph.step(1f / 60f, ring)
        val after = radii(morph)
        for (i in ring.indices) {
            assertTrue(after[i] > before[i] + 1e-3f, "cell $i should move out: ${before[i]} -> ${after[i]}")
        }
    }

    @Test
    fun `a burst's push falls linearly to zero at its duration and the spent burst removes itself`() {
        val burst = Force.Burst(strength = 6f, durationSeconds = 0.16f)
        val h = 0.01f
        val pushes = List(16) { burst.displacement(it * h, (it + 1) * h) }
        // Equal slices of a linear ramp: each one smaller than the last by the same amount.
        for (k in 1 until pushes.size) {
            assertEquals(6f * h * h / 0.16f, pushes[k - 1] - pushes[k], 1e-6f)
        }
        assertEquals(6f * h * h / (2f * 0.16f), pushes.last(), 1e-6f)
        assertEquals(0f, burst.displacement(0.16f, 0.5f))

        val morph = restingBody(ring)
        val fired = Force.Burst(6f, durationSeconds = 0.16f)
        morph.forces.add(fired)
        repeat(9) { morph.step(1f / 60f, ring) }
        assertTrue(fired in morph.forces, "0.15 s in, the burst is still pushing")
        morph.step(1f / 60f, ring)
        assertTrue(fired.isSpent)
        assertTrue(morph.forces.isEmpty(), "the step that spends the burst removes it")
    }

    @Test
    fun `a burst carries a cell strength times duration over two however the time is cut`() {
        fun carried(dt: Float): Float {
            val pair = listOf(Vector3(-0.5f, 0f, 0f), Vector3(0.5f, 0f, 0f))
            // A trip so long its spring barely pulls: what is left is the burst's own reach.
            val morph = restingBody(pair, durationSeconds = 1_000f)
            morph.forces.add(Force.Burst(strength = 6f, durationSeconds = 0.16f))
            var t = 0f
            while (t < 0.16f - 1e-6f) {
                morph.step(dt, pair)
                t += dt
            }
            return morph.positions[3] - 0.5f
        }
        val expected = 6f * 0.16f / 2f
        for (dt in floatArrayOf(1f / 240f, 1f / 60f, 0.04f, 0.16f)) {
            assertEquals(expected, carried(dt), 2e-3f, "dt=$dt")
        }
    }

    @Test
    fun `a dropped frame lands where short frames would under a burst`() {
        fun at(
            dt: Float,
            steps: Int,
        ): FloatArray {
            val morph = restingBody(ring)
            morph.forces.add(Force.Burst(6f))
            repeat(steps) { morph.step(dt, ring) }
            return morph.positions
        }
        val short = at(1f / 60f, 18)
        val long = at(1f / 20f, 6)
        for (i in short.indices) {
            assertEquals(short[i], long[i], 1e-2f, "component $i after 0.3 s")
        }
    }

    @Test
    fun `a burst fired mid-trip is followed by the spring within its critically damped bound`() {
        val morph = FormMorph(3)
        morph.snapTo(from)
        morph.retarget(to, choreography = Choreography.DIRECT, durationSeconds = 2.4f)
        repeat(36) { morph.step(1f / 60f, to) }
        val burst = Force.Burst(6f)
        morph.forces.add(burst)
        while (burst in morph.forces) morph.step(1f / 60f, to)

        // From here the spring acts alone: x(τ) = (x₀ + (v₀ + ωx₀)τ)e^(−ωτ), ω = 7.5 for a 2.4 s trip.
        val omega = 7.5f
        val x0 = FloatArray(9) { morph.positions[it] - flatten(to)[it] }
        val v0 = morph.velocities.copyOf()
        var tau = 0f
        while (morph.isSettling) {
            morph.step(1f / 60f, to)
            tau += 1f / 60f
            for (c in 0 until 9) {
                val offset = morph.positions[c] - flatten(to)[c]
                val bound = (abs(x0[c]) + (abs(v0[c]) + omega * abs(x0[c])) * tau) * exp(-omega * tau)
                assertTrue(abs(offset) <= bound + 1e-5f, "component $c at τ=$tau: |$offset| > $bound")
            }
        }
        assertPositions(to, morph.positions, 1e-4f)
    }

    @Test
    fun `a burst on a trip's last step holds the trip open until the cells are home`() {
        val morph = restingBody(ring, durationSeconds = 0.3f)
        repeat(17) { morph.step(1f / 60f, ring) }
        morph.forces.add(Force.Burst(6f))
        morph.step(1f / 60f, ring)
        assertTrue(morph.isSettling, "the burst outlasts the trip, so the trip waits for it")
        while (morph.isSettling) morph.step(1f / 60f, ring)
        assertPositions(ring, morph.positions, 1e-4f)
    }

    @Test
    fun `a burst keeps pushing through a retarget and a snap removes it`() {
        val morph = restingBody(ring)
        val burst = Force.Burst(6f)
        morph.forces.add(burst)
        morph.step(1f / 60f, ring)
        morph.retarget(ring, choreography = Choreography.DIRECT, durationSeconds = 1f)
        assertTrue(burst in morph.forces)
        assertEquals(1f / 60f, burst.elapsedSeconds, 1e-6f)
        val before = radii(morph)
        morph.step(1f / 60f, ring)
        val after = radii(morph)
        assertTrue(after.indices.all { after[it] > before[it] }, "still pushing after the retarget")

        val repel = Force.Repel()
        morph.forces.add(repel)
        morph.snapTo(ring)
        assertEquals(listOf<Force>(repel), morph.forces)
    }

    @Test
    fun `a burst on a settled body waits for the next trip`() {
        val morph = FormMorph(ring.size)
        morph.snapTo(ring)
        val burst = Force.Burst(6f)
        morph.forces.add(burst)
        morph.step(1f / 60f, ring)
        assertContentEquals(flatten(ring), morph.positions)
        assertEquals(0f, burst.elapsedSeconds)
    }

    @Test
    fun `a burst rejects a non-finite strength and a non-positive duration`() {
        assertFailsWith<IllegalArgumentException> { Force.Burst(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Force.Burst(6f, durationSeconds = 0f) }
        assertFailsWith<IllegalArgumentException> { Force.Burst(6f, durationSeconds = Float.POSITIVE_INFINITY) }
    }

    // Six cells on the axes: a body whose centroid is the origin.
    private val ring =
        listOf(
            Vector3(1f, 0f, 0f),
            Vector3(-1f, 0f, 0f),
            Vector3(0f, 1f, 0f),
            Vector3(0f, -1f, 0f),
            Vector3(0f, 0f, 1f),
            Vector3(0f, 0f, -1f),
        )

    /** A body at rest on [points], on a trip to where it already is, so a force has a trip to act in. */
    private fun restingBody(
        points: List<Vector3>,
        durationSeconds: Float = 2.4f,
    ): FormMorph {
        val morph = FormMorph(points.size)
        morph.snapTo(points)
        morph.retarget(points, choreography = Choreography.DIRECT, durationSeconds = durationSeconds)
        return morph
    }

    private fun radii(morph: FormMorph): FloatArray =
        FloatArray(morph.count) { i ->
            val x = morph.positions[3 * i]
            val y = morph.positions[3 * i + 1]
            val z = morph.positions[3 * i + 2]
            sqrt(x * x + y * y + z * z)
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
