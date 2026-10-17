package link.socket.phosphor.lumos.projection

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.OrbViewport
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.frame.LumosCanvasFrame.CanvasVoxel

/**
 * PHO-73: [PixelLattice.viewport] draws the orb at a centre and radius in pixels on a canvas
 * larger than itself, clipping only at the canvas edge.
 */
class PixelLatticeViewportTest {
    @Test
    fun `a viewport centred on the canvas projects as the fit-to-canvas lattice voxel for voxel`() {
        val frame = frameOf(ball(), rotationX = 0.4f, rotationY = 1.1f, rotationZ = 0.2f)
        val viewport = OrbViewport(SIZE / 2f, SIZE / 2f, min(SIZE, SIZE) / 2f)

        val fitted = PixelLattice(widthPx = SIZE, heightPx = SIZE, tiltDegrees = 20f).project(frame)
        val framed =
            PixelLattice(
                widthPx = SIZE,
                heightPx = SIZE,
                tiltDegrees = 20f,
                viewport = viewport,
            ).project(frame)

        assertEquals(fitted.width, framed.width)
        assertEquals(fitted.height, framed.height)
        assertEquals(frame.cells.size, fitted.voxels.size, "the ball sits wholly inside the canvas")
        assertEquals(fitted.voxels.size, framed.voxels.size)
        fitted.voxels.zip(framed.voxels).forEach { (expected, actual) ->
            assertNear(expected.screenX, actual.screenX, "screenX")
            assertNear(expected.screenY, actual.screenY, "screenY")
            assertEquals(expected, actual.copy(screenX = expected.screenX, screenY = expected.screenY))
        }
        assertEquals(fitted.ambient, framed.ambient)
        assertEquals(fitted.tick, framed.tick)
    }

    @Test
    fun `an off-centre viewport keeps voxels the fit-to-canvas lattice drops`() {
        // A cell flown out past the rim toward a layout element, and one flown off the canvas.
        val outbound = cell(x = 1.5f, y = 0f, z = 0f)
        val offCanvas = cell(x = -1.5f, y = 0f, z = 0f)
        val frame = frameOf(listOf(cell(0f, 0f, 0f), outbound, offCanvas))
        val viewport = OrbViewport(centerXPx = 100f, centerYPx = 200f, radiusPx = 100f)

        val fitted = PixelLattice(widthPx = SIZE, heightPx = SIZE).project(frame)
        val framed = PixelLattice(widthPx = SIZE, heightPx = SIZE, viewport = viewport).project(frame)

        assertEquals(1, fitted.voxels.size, "fit-to-canvas drops both cells past the rim")
        assertEquals(2, framed.voxels.size, "the viewport keeps the one still on the canvas")
        val kept = framed.voxels.single { it.screenX > 100f }
        assertNear(250f, kept.screenX, "1.5 radii right of the centre")
        assertNear(200f, kept.screenY, "on the centre's row")
        assertTrue(framed.voxels.none { it.screenX < 0f }, "the canvas edge still clips")
    }

    @Test
    fun `a viewport maps the unit sphere to centre plus or minus radius with Y up`() {
        val viewport = OrbViewport(centerXPx = 300f, centerYPx = 120f, radiusPx = 80f)
        val lattice = PixelLattice(widthPx = 640, heightPx = 360, viewport = viewport)

        val voxels =
            lattice
                .project(frameOf(listOf(cell(0f, 0f, 0f), cell(0.5f, 1f, 0.1f), cell(-1f, -0.5f, 0.2f))))
                .voxels

        assertVoxelAt(300f, 120f, voxels[0])
        assertVoxelAt(340f, 40f, voxels[1])
        assertVoxelAt(220f, 160f, voxels[2])
    }

    @Test
    fun `a viewport divides only the radius by the aspect ratio and not the centre`() {
        val viewport = OrbViewport(centerXPx = 200f, centerYPx = 200f, radiusPx = 100f)
        val lattice = PixelLattice(widthPx = SIZE, heightPx = SIZE, aspectRatio = 2f, viewport = viewport)

        val top = lattice.project(frameOf(listOf(cell(0f, 1f, 0f)))).voxels.single()

        assertVoxelAt(200f, 150f, top)
    }

    @Test
    fun `a viewport keeps the z sort`() {
        val zs = listOf(0.5f, -0.9f, 0.0f, 0.5f, -0.2f)
        val frame = frameOf(zs.mapIndexed { i, z -> cell(x = i * 0.4f - 0.8f, y = 0f, z = z) })
        val lattice = PixelLattice(widthPx = 1000, heightPx = SIZE, viewport = OrbViewport(600f, 200f, 300f))

        repeat(3) { assertEquals(zs.sorted(), lattice.project(frame).voxels.map { it.z }) }
    }

    @Test
    fun `a viewport needs a finite centre and a positive finite radius`() {
        assertFailsWith<IllegalArgumentException> { OrbViewport(Float.NaN, 0f, 1f) }
        assertFailsWith<IllegalArgumentException> { OrbViewport(0f, Float.POSITIVE_INFINITY, 1f) }
        assertFailsWith<IllegalArgumentException> { OrbViewport(0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { OrbViewport(0f, 0f, -1f) }
        assertFailsWith<IllegalArgumentException> { OrbViewport(0f, 0f, Float.POSITIVE_INFINITY) }
        OrbViewport(-50f, 2000f, 0.5f)
    }

    // A 9³ grid kept inside radius 0.95, so no cell straddles the canvas edge under either path.
    private fun ball(): List<VoxelCell> {
        val steps = 9
        return buildList {
            for (i in 0 until steps) for (j in 0 until steps) for (k in 0 until steps) {
                val x = i * 2f / (steps - 1) - 1f
                val y = j * 2f / (steps - 1) - 1f
                val z = k * 2f / (steps - 1) - 1f
                if (sqrt(x * x + y * y + z * z) <= 0.95f) add(cell(x, y, z, scale = 0.5f + i * 0.05f))
            }
        }
    }

    private fun assertVoxelAt(
        x: Float,
        y: Float,
        voxel: CanvasVoxel,
    ) {
        assertNear(x, voxel.screenX, "screenX")
        assertNear(y, voxel.screenY, "screenY")
    }

    private fun assertNear(
        expected: Float,
        actual: Float,
        label: String,
    ) {
        assertTrue(abs(expected - actual) <= 1e-3f, "$label: expected $expected, was $actual")
    }

    private fun cell(
        x: Float,
        y: Float,
        z: Float,
        scale: Float = 1f,
    ) = VoxelCell(x = x, y = y, z = z, scale = scale, red = 0.2f, green = 0.6f, blue = 1f, alpha = 0.8f)

    private fun frameOf(
        cells: List<VoxelCell>,
        rotationX: Float = 0f,
        rotationY: Float = 0f,
        rotationZ: Float = 0f,
    ) = VoxelFrame(
        tick = 7L,
        timestampEpochMillis = 0L,
        resolution = 10,
        cells = cells,
        ambient =
            VoxelAmbient(
                glowRed = 0f,
                glowGreen = 0f,
                glowBlue = 0f,
                glowIntensity = 0f,
                orbRotationX = rotationX,
                orbRotationY = rotationY,
                orbRotationZ = rotationZ,
            ),
    )

    private companion object {
        const val SIZE = 400
    }
}
