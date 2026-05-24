package link.socket.phosphor.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import link.socket.phosphor.math.Vector3

class VoxelSphereTest {
    @Test
    fun `resolution 7 produces documented voxel count`() {
        val sphere = VoxelSphere(7)

        assertEquals(1743, sphere.count)
        assertEquals(1743, VoxelSphere.totalCount(7))
    }

    @Test
    fun `first voxel normalized position is in unit range`() {
        val first = VoxelSphere(7).voxels.first()

        assertTrue(first.normalizedPos.x in -1f..1f)
        assertTrue(first.normalizedPos.y in -1f..1f)
        assertTrue(first.normalizedPos.z in -1f..1f)
    }

    @Test
    fun `same resolution produces identical voxels and jitter`() {
        val first = VoxelSphere(7)
        val second = VoxelSphere(7)

        assertEquals(first.voxels, second.voxels)
        first.voxels.zip(second.voxels).forEach { (a, b) ->
            assertEquals(a.jitter, b.jitter)
        }
    }

    @Test
    fun `rebuild with same resolution is equivalent`() {
        val sphere = VoxelSphere(7)

        assertEquals(VoxelSphere(7).voxels, sphere.rebuild(7).voxels)
    }

    @Test
    fun `world scale keeps orb size constant across resolutions`() {
        assertEquals(11f, VoxelSphere(0).worldScale)
        assertEquals(11f, VoxelSphere(1).worldScale)
        assertEquals(11f / 7f, VoxelSphere(7).worldScale)
        assertEquals(1f, VoxelSphere(11).worldScale)
    }

    @Test
    fun `jitter components stay in expected range`() {
        VoxelSphere(7).voxels.forEach { voxel ->
            assertTrue(voxel.jitter.x in -0.5f..0.5f)
            assertTrue(voxel.jitter.y in -0.5f..0.5f)
            assertTrue(voxel.jitter.z in -0.5f..0.5f)
        }
    }

    @Test
    fun `facing camera uses rotated unit direction`() {
        val front =
            Voxel(
                gridX = 0,
                gridY = 0,
                gridZ = 1,
                normalizedPos = Vector3.FORWARD,
                unitDirection = Vector3.FORWARD,
                theta = 0f,
                phi = 0f,
                distance = 1f,
                jitter = Vector3.ZERO,
            )
        val back =
            front.copy(
                gridZ = -1,
                normalizedPos = -Vector3.FORWARD,
                unitDirection = -Vector3.FORWARD,
            )

        assertTrue(front.facingCamera(Vector3.ZERO))
        assertFalse(back.facingCamera(Vector3.ZERO))
        assertFalse(front.facingCamera(Vector3(0f, kotlin.math.PI.toFloat(), 0f)))
    }
}
