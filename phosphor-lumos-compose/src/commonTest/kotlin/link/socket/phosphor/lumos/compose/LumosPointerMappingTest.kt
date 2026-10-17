package link.socket.phosphor.lumos.compose

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.projection.PixelLattice
import link.socket.phosphor.math.Vector2

/**
 * PHO-83: the mapping behind [lumosPointer], from a pixel on the node to the orb's view space,
 * inverts [PixelLattice]'s projection: exact everywhere untilted, and on the front of the sphere
 * under a tilt.
 */
class LumosPointerMappingTest {
    @Test
    fun `the centre is the origin and the top-left corner is minus one plus one`() {
        assertEquals(Vector2(0f, 0f), viewSpacePointer(100f, 100f, 200f, 200f))
        assertEquals(Vector2(-1f, 1f), viewSpacePointer(0f, 0f, 200f, 200f))
        assertEquals(Vector2(1f, -1f), viewSpacePointer(200f, 200f, 200f, 200f))
    }

    @Test
    fun `a pointer past the node strays past the orb`() {
        assertEquals(Vector2(1.5f, 1.25f), viewSpacePointer(250f, -25f, 200f, 200f))
    }

    @Test
    fun `a 30 degree tilt lifts the front centre by the sine of the tilt`() {
        val pointer = viewSpacePointer(100f, 100f, 200f, 200f, COS_TILT, SIN_TILT)

        assertNear(0f, pointer.x, "x")
        assertNear(SIN_TILT, pointer.y, "y")
    }

    @Test
    fun `under a tilt the mapping inverts the lattice's on the front of the sphere`() {
        val lattice = PixelLattice(widthPx = 200, heightPx = 200, tiltDegrees = 30f)
        val points = listOf(Vector2(0f, 0f), Vector2(0.3f, -0.4f), Vector2(-0.6f, 0.2f), Vector2(0.7f, 0.7f))

        for (point in points) {
            val px = lattice.canvasPointer(point)
            val back = viewSpacePointer(px.x, px.y, 200f, 200f, COS_TILT, SIN_TILT)
            assertNear(point.x, back.x, "x of $point")
            assertNear(point.y, back.y, "y of $point")
        }
    }

    @Test
    fun `untilted the mapping inverts the lattice's everywhere`() {
        val lattice = PixelLattice(widthPx = 320, heightPx = 200)
        val point = Vector2(1.4f, -1.2f)

        val px = lattice.canvasPointer(point)
        val back = viewSpacePointer(px.x, px.y, 320f, 200f)

        assertNear(point.x, back.x, "x")
        assertNear(point.y, back.y, "y")
    }

    private fun assertNear(
        expected: Float,
        actual: Float,
        label: String,
    ) = assertTrue(abs(expected - actual) < 1e-4f, "$label: expected $expected, was $actual")

    private companion object {
        val TILT = (30.0 * PI / 180.0)
        val COS_TILT = cos(TILT).toFloat()
        val SIN_TILT = sin(TILT).toFloat()
    }
}
