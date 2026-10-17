package link.socket.phosphor.lumos.projection

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame

/**
 * PHO-53: [PixelLattice.tiltDegrees] views the orb from above its equator — a rotation about the
 * screen's X axis applied after the orb's own, not added to it.
 */
class PixelLatticeTiltTest {
    @Test
    fun `no tilt projects exactly as a lattice without one`() {
        val frame = frameOf(cell(0.3f, -0.4f, 0.5f), cell(-0.6f, 0.2f, -0.1f), rotationX = 0.4f, rotationY = 1.1f)

        val untilted = PixelLattice(widthPx = SIZE, heightPx = SIZE).project(frame)
        val zeroTilt = PixelLattice(widthPx = SIZE, heightPx = SIZE, tiltDegrees = 0f).project(frame)

        assertEquals(untilted, zeroTilt)
    }

    @Test
    fun `a tilt from above brings the north pole toward the viewer`() {
        val pole = project(cell(0f, 1f, 0f), tiltDegrees = 20f)

        assertNear(screenY(cos(TILT)), pole.screenY, "the pole drops inside the rim")
        assertNear(sin(TILT), pole.z, "and faces the viewer")
    }

    @Test
    fun `a tilt from above carries the face the viewer saw below the centre`() {
        val face = project(cell(0f, 0f, 1f), tiltDegrees = 20f)

        assertNear(screenY(-sin(TILT)), face.screenY, "0.34 of a radius below centre")
        assertTrue(face.screenY > SIZE / 2f)
    }

    @Test
    fun `the tilt comes after the orb's own spin`() {
        // A quarter turn about Y carries the +X cell to the back of the orb; the tilt then lifts it.
        // Added to orbRotationX instead, the tilt would act first, on a cell lying along the X axis,
        // and leave it at the centre.
        val spun = project(cell(1f, 0f, 0f), tiltDegrees = 20f, rotationY = (PI / 2).toFloat())

        assertNear(screenY(sin(TILT)), spun.screenY, "the back of the orb shows above the centre")
    }

    @Test
    fun `a tilt must be finite`() {
        assertFailsWith<IllegalArgumentException> {
            PixelLattice(widthPx = SIZE, heightPx = SIZE, tiltDegrees = Float.NaN)
        }
    }

    private fun project(
        cell: VoxelCell,
        tiltDegrees: Float,
        rotationY: Float = 0f,
    ) = PixelLattice(widthPx = SIZE, heightPx = SIZE, tiltDegrees = tiltDegrees)
        .project(frameOf(cell, rotationY = rotationY))
        .voxels
        .single()

    private fun screenY(rotatedY: Float): Float = (1f - (rotatedY + 1f) * 0.5f) * SIZE

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
    ) = VoxelCell(x = x, y = y, z = z, scale = 1f, red = 1f, green = 1f, blue = 1f)

    private fun frameOf(
        vararg cells: VoxelCell,
        rotationX: Float = 0f,
        rotationY: Float = 0f,
    ) = VoxelFrame(
        tick = 0L,
        timestampEpochMillis = 0L,
        resolution = 10,
        cells = cells.toList(),
        ambient =
            VoxelAmbient(
                glowRed = 0f,
                glowGreen = 0f,
                glowBlue = 0f,
                glowIntensity = 0f,
                orbRotationX = rotationX,
                orbRotationY = rotationY,
                orbRotationZ = 0f,
            ),
    )

    private companion object {
        const val SIZE = 400
        val TILT: Float = (20.0 * PI / 180.0).toFloat()
    }
}
