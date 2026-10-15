package link.socket.phosphor.field

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.time.measureTime
import link.socket.phosphor.math.Vector3

/**
 * PHO-57 Task A recon, kept as the matcher's cost gauge: what a greedy match
 * costs at 4,801 cells, naively and through [FormMatcher]'s grid. Timings print
 * to stdout; the assertion is that the grid returns the naive assignment.
 *
 * Measured 2026-10-16 on Apple Silicon: naive 20–30 ms; `FormMatcher()` 0.7 ms
 * when the forms coincide, 8–10 ms between the lattice and a Fibonacci sphere.
 */
class FormMatcherReconTest {
    @Test
    fun `FormMatcher returns the naive greedy assignment at resolution 10 and reports timings`() {
        val lattice = VoxelSphere(10).voxels.map { it.normalizedPos }
        val fibonacci = fibonacciSphere(lattice.size)
        val cases =
            listOf(
                "lattice→lattice (identity)" to (lattice to lattice),
                "lattice→fibonacci surface" to (lattice to fibonacci),
                "lattice→fibonacci ball" to (lattice to fibonacciBall(lattice.size)),
                "lattice→shuffled lattice" to (lattice to lattice.shuffled(Random(57))),
                "fibonacci surface→lattice" to (fibonacci to lattice),
            )
        val matcher = FormMatcher()
        println("cells: ${lattice.size}")
        for ((name, pair) in cases) {
            val (origins, targets) = pair
            val naive = FormMatcherTest.naive(origins, targets)
            assertContentEquals(naive, matcher.match(origins, targets), name)
            val naiveMs = median { FormMatcherTest.naive(origins, targets) }
            val gridMs = median { matcher.match(origins, targets) }
            println("$name: naive ${fmt(naiveMs)} ms | FormMatcher ${fmt(gridMs)} ms")
        }
    }

    private fun median(block: () -> Unit): Double {
        repeat(3) { block() }
        return List(7) { measureTime(block).inWholeMicroseconds / 1_000.0 }.sorted()[3]
    }

    private fun fmt(value: Double): String = ((value * 100).toLong() / 100.0).toString()

    private fun fibonacciSphere(count: Int): List<Vector3> {
        val golden = PI * (3.0 - sqrt(5.0))
        return List(count) { i ->
            val y = 1.0 - (i + 0.5) / count * 2.0
            val r = sqrt(1.0 - y * y)
            Vector3((cos(golden * i) * r).toFloat(), y.toFloat(), (sin(golden * i) * r).toFloat())
        }
    }

    private fun fibonacciBall(count: Int): List<Vector3> {
        val golden = PI * (3.0 - sqrt(5.0))
        return List(count) { i ->
            val y = 1.0 - (i + 0.5) / count * 2.0
            val r = sqrt(1.0 - y * y)
            val radius = Math.cbrt((i + 0.5) / count)
            Vector3(
                (cos(golden * i) * r * radius).toFloat(),
                (y * radius).toFloat(),
                (sin(golden * i) * r * radius).toFloat(),
            )
        }
    }
}
