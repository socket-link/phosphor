package link.socket.phosphor.lumos.projection

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.OrbViewport
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.signal.LumosInput

/**
 * PHO-83: [PixelLattice.project] carries the pointer onto the canvas frame in pixels — the frame's
 * own by default, or the one the driver holds — mapped as a voxel on the front of the unit sphere
 * at that view-space point would be: Y flipped, tilted, placed in the viewport.
 */
class PixelLatticePointerTest {
    @Test
    fun `a frame with no input carries no pointer`() {
        assertNull(lattice().project(frameOf()).pointer)
    }

    @Test
    fun `the frame's pointer lands in canvas pixels with Y down`() {
        val frame = frameOf(input = LumosInput(Vector2(0.5f, 0.5f)))

        val pointer = assertNotNull(lattice().project(frame).pointer)

        assertNear(150f, pointer.x, "x")
        assertNear(50f, pointer.y, "y")
    }

    @Test
    fun `the centre maps to the canvas centre and the top-left corner to the top-left pixel`() {
        val lattice = lattice()

        assertEquals(Vector2(100f, 100f), lattice.canvasPointer(Vector2(0f, 0f)))
        assertEquals(Vector2(0f, 0f), lattice.canvasPointer(Vector2(-1f, 1f)))
    }

    @Test
    fun `a pointer handed to project replaces the frame's own`() {
        val frame = frameOf(input = LumosInput(Vector2(0.5f, 0.5f)))

        val replaced = assertNotNull(lattice().project(frame, Vector2(-0.5f, -0.5f)).pointer)
        assertNear(50f, replaced.x, "x")
        assertNear(150f, replaced.y, "y")

        assertNull(lattice().project(frame, null).pointer, "null clears the frame's pointer")
    }

    @Test
    fun `a tilt carries the pointer where the front of the orb went`() {
        val tilted = PixelLattice(widthPx = SIZE, heightPx = SIZE, tiltDegrees = 30f)
        val front = tilted.project(frameOf(cell(0f, 0f, 1f))).voxels.single()

        val pointer = tilted.canvasPointer(Vector2(0f, 0f))

        assertNear(front.screenX, pointer.x, "x")
        assertNear(front.screenY, pointer.y, "y")
        assertNear(SIZE / 2f + SIZE / 2f * sin(TILT), pointer.y, "below the centre by sin(tilt)")
    }

    @Test
    fun `a tilt leaves a pointer on the rim on the rim`() {
        val tilted = PixelLattice(widthPx = SIZE, heightPx = SIZE, tiltDegrees = 30f)
        val rim = tilted.project(frameOf(cell(0f, -1f, 0f))).voxels.single()

        val pointer = tilted.canvasPointer(Vector2(0f, -1f))

        assertNear(rim.screenX, pointer.x, "x")
        assertNear(rim.screenY, pointer.y, "y")
    }

    @Test
    fun `a pointer past the rim maps on the equatorial plane`() {
        val tilted = PixelLattice(widthPx = SIZE, heightPx = SIZE, tiltDegrees = 30f)

        val pointer = tilted.canvasPointer(Vector2(1.5f, 0f))

        assertNear(250f, pointer.x, "x")
        assertNear(100f, pointer.y, "y")
    }

    @Test
    fun `a viewport places the pointer about its centre`() {
        val lattice =
            PixelLattice(widthPx = 1000, heightPx = 400, viewport = OrbViewport(600f, 200f, 100f))

        assertEquals(Vector2(600f, 200f), lattice.canvasPointer(Vector2(0f, 0f)))
        assertEquals(Vector2(700f, 100f), lattice.canvasPointer(Vector2(1f, 1f)))
    }

    @Test
    fun `the aspect ratio scales the pointer's Y as it scales the voxels`() {
        val lattice = PixelLattice(widthPx = SIZE, heightPx = SIZE, aspectRatio = 2f)
        val top = lattice.project(frameOf(cell(0f, 1f, 0f))).voxels.single()

        val pointer = lattice.canvasPointer(Vector2(0f, 1f))

        assertNear(top.screenY, pointer.y, "y")
    }

    private fun lattice() = PixelLattice(widthPx = SIZE, heightPx = SIZE)

    private fun cell(
        x: Float,
        y: Float,
        z: Float,
    ) = VoxelCell(x = x, y = y, z = z, scale = 1f, red = 1f, green = 1f, blue = 1f)

    private fun frameOf(
        vararg cells: VoxelCell,
        input: LumosInput? = null,
    ) = VoxelFrame(
        tick = 1L,
        timestampEpochMillis = 0L,
        resolution = 10,
        cells = cells.toList(),
        ambient = VoxelAmbient(0f, 0f, 0f, 0f, orbRotationX = 0f, orbRotationY = 0f, orbRotationZ = 0f),
        input = input,
    )

    private fun assertNear(
        expected: Float,
        actual: Float,
        label: String,
    ) = assertTrue(abs(expected - actual) < 1e-3f, "$label: expected $expected, was $actual")

    private companion object {
        const val SIZE = 200
        val TILT = (30.0 * PI / 180.0).toFloat()
    }
}
