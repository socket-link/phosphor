package link.socket.phosphor.lumos.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.compose.projection.ComposeLattice
import link.socket.phosphor.lumos.projection.PixelLattice

/**
 * PHO-85: the names the frame and its projection had before they moved to `phosphor-lumos` still
 * compile, used as Socket's `LumosService` uses them, and are the same classes under the new ones.
 */
class LegacyNamesTest {
    @Test
    fun `the old names project the frame the new ones do`() {
        val lattice: ComposeLattice = ComposeLattice(widthPx = 100, heightPx = 100, voxelRadiusPx = 2f)
        val projected: LumosCanvasFrame = lattice.project(FRAME)

        assertEquals(PixelLattice(widthPx = 100, heightPx = 100, voxelRadiusPx = 2f).project(FRAME), projected)
    }

    @Test
    fun `the old names are the moved classes`() {
        assertSame(link.socket.phosphor.lumos.frame.LumosCanvasFrame.serializer(), LumosCanvasFrame.serializer())
        assertEquals(PixelLattice::class, ComposeLattice::class)
    }

    private companion object {
        val FRAME =
            VoxelFrame(
                tick = 7L,
                timestampEpochMillis = 0L,
                resolution = 10,
                cells =
                    listOf(VoxelCell(x = 0.25f, y = -0.5f, z = 0.5f, scale = 1f, red = 1f, green = 0.5f, blue = 0.25f)),
                ambient =
                    VoxelAmbient(
                        glowRed = 0f,
                        glowGreen = 0f,
                        glowBlue = 0f,
                        glowIntensity = 0f,
                        orbRotationX = 0.3f,
                        orbRotationY = 0.6f,
                        orbRotationZ = 0f,
                    ),
            )
    }
}
