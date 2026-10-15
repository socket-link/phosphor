package link.socket.phosphor.lumos

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import link.socket.phosphor.math.Vector3

class FormSampleTest {
    private val halfPi = (PI / 2).toFloat()

    @Test
    fun `derive computes angles and radius and normals as the lattice defines them`() {
        val sample =
            FormSample.derive(
                listOf(
                    Vector3(0.5f, 0f, 0f),
                    Vector3(0f, 0f, 0.5f),
                    Vector3(0f, 1f, 0f),
                    Vector3(0f, -1f, 0f),
                ),
                radiusScale = 4f,
            )

        assertEquals(4, sample.count)
        assertEquals(Vector3.RIGHT, sample.unitDirections[0])
        assertEquals(Vector3.FORWARD, sample.unitDirections[1])
        assertEquals(0f, sample.theta[0])
        assertEquals(halfPi, sample.theta[1], 1e-6f)
        assertEquals(halfPi, sample.phi[0], 1e-6f)
        assertEquals(0f, sample.phi[2])
        assertEquals(PI.toFloat(), sample.phi[3], 1e-6f)
        assertEquals(2f, sample.distance[0])
        assertEquals(4f, sample.distance[2])
    }

    @Test
    fun `a cell at the origin has no normal and no angles`() {
        val sample = FormSample.derive(listOf(Vector3.ZERO))

        assertEquals(Vector3.ZERO, sample.unitDirections[0])
        assertEquals(0f, sample.theta[0])
        assertEquals(0f, sample.phi[0])
        assertEquals(0f, sample.distance[0])
    }

    @Test
    fun `jitter is deterministic per index and stays within half a cell`() {
        val positions = List(64) { Vector3.ZERO }

        val first = FormSample.derive(positions)
        val second = FormSample.derive(positions)

        assertEquals(first.jitter, second.jitter)
        first.jitter.forEach { jitter ->
            assertTrue(jitter.x in -0.5f..0.5f && jitter.y in -0.5f..0.5f && jitter.z in -0.5f..0.5f, "$jitter")
        }
        assertNotEquals(first.jitter[0], first.jitter[1])
        assertEquals(64, first.jitter.distinct().size, "jitter should differ across indices")
    }

    @Test
    fun `derived from the lattice's positions the generic derivation agrees up to float rounding`() {
        // The lattice copies its fields from VoxelSphere because this agreement is
        // not bit-for-bit: atan2 and acos of a normalized position round differently
        // from the same functions of the integer grid position. acos is steep at the
        // poles, so a last-bit difference in y / |p| there is a few 1e-4 in phi.
        val lattice = Forms.Lattice(3).sample(0, LumosLook.BASE, 0f)

        val derived = FormSample.derive(lattice.positions, radiusScale = 3f)

        assertEquals(lattice.unitDirections, derived.unitDirections)
        for (index in 0 until lattice.count) {
            assertTrue(abs(lattice.theta[index] - derived.theta[index]) < 1e-5f, "theta at $index")
            assertTrue(abs(lattice.phi[index] - derived.phi[index]) < 1e-3f, "phi at $index")
            assertTrue(abs(lattice.distance[index] - derived.distance[index]) < 1e-4f, "distance at $index")
        }
    }

    @Test
    fun `fields of different lengths are rejected`() {
        val two = FormSample.derive(listOf(Vector3.ZERO, Vector3.UP))

        assertFailsWith<IllegalArgumentException> {
            FormSample(
                positions = two.positions,
                unitDirections = two.unitDirections.take(1),
                theta = two.theta,
                phi = two.phi,
                distance = two.distance,
                jitter = two.jitter,
            )
        }
        assertFailsWith<IllegalArgumentException> { FormSample.derive(two.positions, radiusScale = 0f) }
    }
}
