package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import link.socket.phosphor.field.VoxelSphere

class FormsTest {
    @Test
    fun `the lattice sample equals VoxelSphere's voxels index for index`() {
        listOf(0, 3, 10).forEach { resolution ->
            val voxels = VoxelSphere(resolution).voxels
            val sample = Forms.Lattice(resolution).sample(0, LumosLook.BASE, 0f)

            assertEquals(voxels.size, sample.count, "count at resolution $resolution")
            assertEquals(voxels.map { it.normalizedPos }, sample.positions, "positions at resolution $resolution")
            assertEquals(voxels.map { it.unitDirection }, sample.unitDirections, "directions at resolution $resolution")
            assertEquals(voxels.map { it.jitter }, sample.jitter, "jitter at resolution $resolution")
            assertContentEquals(FloatArray(voxels.size) { voxels[it].theta }, sample.theta, "theta at $resolution")
            assertContentEquals(FloatArray(voxels.size) { voxels[it].phi }, sample.phi, "phi at $resolution")
            assertContentEquals(
                FloatArray(voxels.size) { voxels[it].distance },
                sample.distance,
                "distance at $resolution",
            )
        }
    }

    @Test
    fun `the lattice fixes its count by resolution`() {
        assertEquals(1, Forms.Lattice(0).count)
        assertEquals(171, Forms.Lattice(3).count)
        assertEquals(1_141, Forms.Lattice(6).count)
        assertEquals(4_801, Forms.Lattice(10).count)
        assertEquals(4_801, Forms.Lattice(10).sample(12, LumosLook.BASE, 0f).count)
    }

    @Test
    fun `the lattice ignores count and look and time and returns one instance`() {
        val lattice = Forms.Lattice(3)

        val first = lattice.sample(0, LumosLook.BASE, 0f)
        val second = lattice.sample(9_999, LumosLook.BASE, 123.4f)

        assertSame(first, second)
        assertEquals(lattice.sphere.resolution, lattice.resolution)
    }

    @Test
    fun `a negative resolution is rejected`() {
        assertFailsWith<IllegalArgumentException> { Forms.Lattice(-1) }
    }
}
