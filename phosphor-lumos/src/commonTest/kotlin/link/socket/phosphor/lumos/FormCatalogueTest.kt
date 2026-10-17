package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import link.socket.phosphor.field.VoxelSphere
import link.socket.phosphor.math.Vector3

/**
 * PHO-56: the contract every catalogue form keeps — exactly `count` cells, all inside the unit
 * sphere, the same answer for the same inputs — checked over [Forms.All], plus each form's own
 * geometry.
 */
class FormCatalogueTest {
    @Test
    fun `every catalogue form returns exactly count positions inside the unit sphere`() {
        for ((name, form) in Forms.All) {
            for (count in COUNTS) {
                val sample = form.sample(count, LumosLook.BASE, 0f)
                assertEquals(count, sample.count, "$name at count $count")
                sample.positions.forEachIndexed { index, position ->
                    // Build the message only on failure: this loop visits ~210K cells, and formatting
                    // four floats per cell is what pushed the browser runners past mocha's timeout.
                    val length = position.length()
                    if (length > 1f + BOUNDS_TOLERANCE) {
                        fail("$name at count $count: cell $index is at $position, length $length")
                    }
                }
            }
        }
    }

    @Test
    fun `every catalogue form is deterministic and holds its sample while count is unchanged`() {
        for ((name, form) in Forms.All) {
            val first = form.sample(80, LumosLook.BASE, 0f)
            val held = form.sample(80, LumosLook.BASE, 12.5f)
            assertSame(first, held, "$name rebuilt its sample for an unchanged count")

            val other = form.sample(81, LumosLook.BASE, 0f)
            assertNotSame(first, other, "$name returned an 80-cell sample for 81")

            val again = form.sample(80, LumosLook.BASE, 99f)
            assertEquals(first.positions, again.positions, "$name gave different positions for the same count")
        }
    }

    @Test
    fun `a catalogue form rejects a count below one`() {
        for ((name, form) in Forms.All) {
            assertFailsWith<IllegalArgumentException>(name) { form.sample(0, LumosLook.BASE, 0f) }
        }
    }

    @Test
    fun `catalogue forms scale distance like the presets' lattice`() {
        val sample = Forms.Fibonacci.sample(50, LumosLook.BASE, 0f)
        for (index in 0 until sample.count) {
            assertEquals(Forms.DISTANCE_SCALE, sample.distance[index], 1e-3f, "cell $index")
        }
    }

    @Test
    fun `the Fibonacci sphere runs top to bottom on the golden-angle spiral`() {
        val count = 80
        val sample = Forms.Fibonacci.sample(count, LumosLook.BASE, 0f)
        val goldenAngle = (kotlin.math.PI * (3.0 - sqrt(5.0))).toFloat()

        for (i in 0 until count) {
            val y = 1f - 2f * (i + 0.5f) / count
            val r = sqrt(1f - y * y)
            val expected = Vector3(kotlin.math.cos(i * goldenAngle) * r, y, kotlin.math.sin(i * goldenAngle) * r)
            assertVectorEquals(expected, sample.positions[i], "cell $i")
            assertEquals(1f, sample.positions[i].length(), 1e-4f, "cell $i sits on the sphere")
        }
        assertTrue(sample.positions.first().y > 0.97f, "the first cell is at the top")
        assertTrue(sample.positions.last().y < -0.97f, "the last cell is at the bottom")
    }

    @Test
    fun `the Fibonacci sphere is unframed`() {
        assertEquals(1f, Forms.Fibonacci.scale)
        assertEquals(0f, Forms.Fibonacci.tipDegrees)
        // Half the mean spacing of 80 points over the unit sphere, 0.5 · √(4π / 80).
        assertEquals(0.198f, Forms.Fibonacci.cellRadius(80), 1e-3f)
    }

    @Test
    fun `eight cells of cube are its corners touching the sphere`() {
        val sample = Forms.Cube.sample(8, LumosLook.BASE, 0f)
        val corner = (1.0 / sqrt(3.0)).toFloat()

        val expected =
            buildList {
                for (x in listOf(-corner, corner)) {
                    for (y in listOf(-corner, corner)) {
                        for (z in listOf(-corner, corner)) add(Vector3(x, y, z))
                    }
                }
            }
        assertEquals(expected, sample.positions)
        sample.positions.forEach { assertEquals(1f, it.length(), 1e-5f) }
    }

    @Test
    fun `the cube keeps every cell on its surface at a step count that covers count`() {
        val count = 500
        // n = ceil(√((500 − 2) / 6)) = 10: 602 grid points, thinned to 500.
        val n = 10
        val sample = Forms.Cube.sample(count, LumosLook.BASE, 0f)
        val half = (1.0 / sqrt(3.0)).toFloat()

        for (position in sample.positions) {
            val onFace =
                listOf(position.x, position.y, position.z).any { abs(abs(it) - half) < 1e-5f }
            assertTrue(onFace, "$position is not on a face")
        }
        assertEquals(1.2f, Forms.Cube.scale)
        assertEquals(half / n, Forms.Cube.cellRadius(count), 1e-6f)
        assertEquals(count, sample.positions.distinct().size, "the thinned cube repeats a cell")
    }

    @Test
    fun `the inline orb count is four cells per pixel and never fewer than eight`() {
        assertEquals(80, Forms.inlineOrbCount(20))
        assertEquals(40, Forms.inlineOrbCount(20, density = 0.5f))
        assertEquals(8, Forms.inlineOrbCount(1))
        assertEquals(Forms.MIN_INLINE_ORB_COUNT, Forms.inlineOrbCount(2, density = 0.1f))
    }

    @Test
    fun `counted fixes the count and passes the framing through`() {
        val inline = Forms.Fibonacci.counted(80)
        val cube = Forms.Cube.counted(26)

        assertEquals(80, inline.sample(4_801, LumosLook.BASE, 0f).count)
        assertSame(Forms.Fibonacci.sample(80, LumosLook.BASE, 0f), inline.sample(1, LumosLook.BASE, 3f))
        assertEquals(1.2f, cube.scale)
        assertEquals(Forms.Cube.cellRadius(26), cube.cellRadius(26))
        assertEquals("Forms.Fibonacci.counted(80)", inline.toString())
        assertFailsWith<IllegalArgumentException> { Forms.Fibonacci.counted(0) }
    }

    @Test
    fun `counted wraps an unframed form with the default framing`() {
        val one = LumosForm { _, _, _ -> FormSample.derive(listOf(Vector3.ZERO)) }
        val counted = one.counted(5)

        assertEquals(1f, counted.scale)
        assertEquals(0f, counted.tipDegrees)
        assertEquals(0.5f * sqrt(4f * kotlin.math.PI.toFloat() / 5f), counted.cellRadius(5), 1e-6f)
    }

    @Test
    fun `byName finds catalogue forms case-insensitively`() {
        assertSame(Forms.Fibonacci, Forms.byName("fibonacci"))
        assertSame(Forms.Cube, Forms.byName("Cube"))
        assertNull(Forms.byName("lattice"))
        assertNotNull(Forms.All.firstOrNull { (name, _) -> name == "cube" })
    }

    private fun assertVectorEquals(
        expected: Vector3,
        actual: Vector3,
        message: String,
    ) {
        assertEquals(expected.x, actual.x, 1e-5f, "$message x")
        assertEquals(expected.y, actual.y, 1e-5f, "$message y")
        assertEquals(expected.z, actual.z, 1e-5f, "$message z")
    }

    private companion object {
        const val BOUNDS_TOLERANCE = 1e-4f

        /**
         * Every count where a catalogue form changes path, each with its neighbours, the counts the
         * builder really asks for, and a coarse sweep between — 56 counts, ~210K cells over [Forms.All].
         *
         * - 1 and 2: the cube's `max(1, …)` clamp, where `count − 2 ≤ 0` leaves no step count to round up.
         * - 7, 8, 9: [Forms.MIN_INLINE_ORB_COUNT] ± 1. 8 is also the cube's eight bare corners, `6·1² + 2`.
         * - `6n² + 1`, `6n² + 2`, `6n² + 3` at n = 2, 3 and 28: one grid point to drop, a grid exactly full
         *   (thinning's untouched path), and the count that forces `n + 1` steps (the deepest thinning).
         *   The small steps thin hardest, 28 is the last step under 4,801, and the sweep crosses the rest.
         * - 80: the 20 px inline orb.
         * - [VoxelSphere.totalCount] at resolutions 0 to 10, 1 to 4,801: what the builder passes a form
         *   at each resolution. 4,801 is the presets' and the top of the range.
         * - Every 150th count from 8. Every 13th kept pushing the wasm runner past mocha's 2 s default,
         *   which `useMocha { timeout }` does not reach for wasm targets.
         */
        val COUNTS: List<Int> =
            (
                listOf(1, 2, 7, 8, 9, 80, 4_801) +
                    listOf(2, 3, 28).flatMap { n -> (1..3).map { 6 * n * n + it } } +
                    (0..10).map { VoxelSphere.totalCount(it) } +
                    (8..4_801 step 150)
            ).distinct().sorted()
    }
}
