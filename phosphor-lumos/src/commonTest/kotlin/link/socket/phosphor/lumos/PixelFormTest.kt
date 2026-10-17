package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.look.ViewPoint
import link.socket.phosphor.lumos.look.ViewRotation

/**
 * PHO-72 Task B: [PixelForm.roundedRect] lays Dots' element target out in root
 * pixels, and carries it back through the frame's rotation so it is seen where
 * it was laid out.
 */
class PixelFormTest {
    private val viewport = OrbViewport(centerXPx = 200f, centerYPx = 200f, radiusPx = 160f)
    private val button = RectPx(left = 120f, top = 170f, width = 160f, height = 60f, cornerRadius = 14f)

    private val shapes =
        listOf(
            button,
            // A pill: the radius past half the height clamps to it.
            RectPx(left = 40f, top = 300f, width = 220f, height = 40f, cornerRadius = 100f),
            // A circle.
            RectPx(left = 150f, top = 150f, width = 100f, height = 100f, cornerRadius = 50f),
            // Square corners, and a rect past the orb's radius.
            RectPx(left = -20f, top = 10f, width = 440f, height = 90f, cornerRadius = 0f),
        )

    @Test
    fun `yields exactly the count asked for on every shape`() {
        for (rect in shapes) {
            for (count in listOf(1, 2, 7, 80, 333, 1141)) {
                val sample = PixelForm.roundedRect(rect, viewport).sample(count, LumosLook.BASE, 0f)
                assertEquals(count, sample.count, "$rect at $count")
            }
        }
    }

    @Test
    fun `the perimeter share sits on the rounded edge and the rest inside it`() {
        for (rect in shapes) {
            for (count in listOf(80, 1141)) {
                val form = PixelForm.roundedRect(rect, viewport)
                val sample = form.sample(count, LumosLook.BASE, 0f)
                val outline = (count * PixelForm.PERIMETER_FRACTION).roundToInt()
                val halfCellPx = form.cellRadius(count) * viewport.radiusPx
                for (i in 0 until count) {
                    val p = sample.positions[i]
                    val d = rect.signedDistance(viewport.pixelX(p.x), viewport.pixelY(p.y))
                    if (i < outline) {
                        assertTrue(abs(d) <= halfCellPx, "$rect at $count: outline cell $i is $d px off the edge")
                    } else {
                        assertTrue(d <= 1e-3f, "$rect at $count: fill cell $i is $d px outside")
                    }
                }
            }
        }
    }

    @Test
    fun `the outline spreads around the whole edge`() {
        val sample = PixelForm.roundedRect(button, viewport).sample(1141, LumosLook.BASE, 0f)
        val outline = (1141 * PixelForm.PERIMETER_FRACTION).roundToInt()
        val xs = (0 until outline).map { viewport.pixelX(sample.positions[it].x) }
        val ys = (0 until outline).map { viewport.pixelY(sample.positions[it].y) }
        assertTrue(xs.min() < button.left + 0.5f && xs.max() > button.right - 0.5f, "reaches both sides")
        assertTrue(ys.min() < button.top + 0.5f && ys.max() > button.bottom - 0.5f, "reaches top and bottom")
    }

    @Test
    fun `laid out flat in view space with each cell facing the viewer`() {
        val sample = PixelForm.roundedRect(button, viewport).sample(300, LumosLook.BASE, 0f)
        for (i in 0 until sample.count) {
            assertEquals(0f, sample.positions[i].z)
            assertEquals(0f, sample.unitDirections[i].x)
            assertEquals(0f, sample.unitDirections[i].y)
            assertEquals(1f, sample.unitDirections[i].z)
        }
    }

    @Test
    fun `the same rect and count lay out the same cells in the same order`() {
        val a = PixelForm.roundedRect(button, viewport).sample(500, LumosLook.BASE, 0f)
        val b = PixelForm.roundedRect(button, viewport).sample(500, LumosLook.BASE, 3f)
        assertEquals(a.positions, b.positions)
        assertEquals(a.jitter, b.jitter)
        assertTrue(a.theta.contentEquals(b.theta) && a.phi.contentEquals(b.phi) && a.distance.contentEquals(b.distance))
    }

    @Test
    fun `a rect or count that changes lays out anew and an equal one does not`() {
        val form = PixelForm.roundedRect(button, viewport)
        val first = form.sample(200, LumosLook.BASE, 0f)
        assertSame(first, form.sample(200, LumosLook.BASE, 1f))

        form.rect = button.copy()
        assertSame(first, form.sample(200, LumosLook.BASE, 0f), "an equal rect keeps the layout")

        form.rect = button.copy(left = button.left + 30f)
        val moved = form.sample(200, LumosLook.BASE, 0f)
        assertNotSame(first, moved)
        assertEquals(first.positions[0].x + 30f / viewport.radiusPx, moved.positions[0].x, 1e-5f)

        assertNotSame(moved, form.sample(201, LumosLook.BASE, 0f))
    }

    @Test
    fun `the frame's rotation carries every cell back to where it was laid out`() {
        val form = PixelForm.roundedRect(button, viewport)
        val laidOut = form.sample(400, LumosLook.BASE, 0f).positions.toList()
        val rotation = ViewRotation()
        val view = ViewPoint()
        for ((rx, ry) in listOf(0.3f to 0.9f, -1.2f to 2.6f, 3.1f to -0.4f, 0f to 4.4f)) {
            rotation.set(rx, ry, 0.2f, 0.5f)
            val body = form.sample(400, LumosLook.BASE, 0f, rotation)
            for (i in 0 until body.count) {
                val p = body.positions[i]
                val u = body.unitDirections[i]
                rotation.place(view, p.x, p.y, p.z, u.x, u.y, u.z)
                assertEquals(laidOut[i].x, view.x, 1e-5f, "x of cell $i at ($rx, $ry)")
                assertEquals(laidOut[i].y, view.y, 1e-5f, "y of cell $i at ($rx, $ry)")
                assertEquals(0f, view.z, 1e-5f, "z of cell $i at ($rx, $ry)")
                assertEquals(1f, view.uz, 1e-5f, "cell $i faces the viewer at ($rx, $ry)")
            }
        }
    }

    @Test
    fun `a turning orb rewrites the same sample and the same buffers every frame`() {
        val form = PixelForm.roundedRect(button, viewport)
        val rotation = ViewRotation()
        val first = form.sample(1141, LumosLook.BASE, 0f, rotation)
        val positions = first.positions
        val directions = first.unitDirections
        val theta = first.theta
        val phi = first.phi
        val distance = first.distance
        val jitter = first.jitter
        val firstCell = positions[0]
        for (frame in 1..100) {
            rotation.set(0.01f * frame, 0.03f * frame, 0.6f, 1.8f)
            val sample = form.sample(1141, LumosLook.BASE, frame / 60f, rotation)
            assertSame(first, sample, "frame $frame")
            assertSame(positions, sample.positions)
            assertSame(directions, sample.unitDirections)
            assertSame(theta, sample.theta)
            assertSame(phi, sample.phi)
            assertSame(distance, sample.distance)
            assertSame(jitter, sample.jitter)
        }
        assertFalse(firstCell == positions[0], "the positions were rewritten")
    }

    @Test
    fun `a rotation that has not moved leaves the sample untouched`() {
        val form = PixelForm.roundedRect(button, viewport)
        val rotation = ViewRotation()
        rotation.set(0.4f, 1.1f, 0f, 0f)
        val sample = form.sample(100, LumosLook.BASE, 0f, rotation)
        val cell = sample.positions[7]
        form.sample(100, LumosLook.BASE, 1f, rotation)
        assertSame(cell, sample.positions[7])
    }

    @Test
    fun `rigid and sized at half the fill's spacing`() {
        val form = PixelForm.roundedRect(button, viewport)
        assertTrue(form.rigid)
        assertEquals(1f, form.scale)
        val count = 500
        val fill = count - (count * PixelForm.PERIMETER_FRACTION).roundToInt()
        val spacingPx = sqrt(button.area / fill)
        assertEquals(0.5f * spacingPx / viewport.radiusPx, form.cellRadius(count), 1e-6f)
    }

    @Test
    fun `counted keeps a view form a rigid view form`() {
        val counted = assertIs<ViewForm>(PixelForm.roundedRect(button, viewport).counted(90))
        assertTrue(counted.rigid)
        val rotation = ViewRotation()
        rotation.set(0.5f, 1.5f, 0f, 0f)
        val sample = counted.sample(4801, LumosLook.BASE, 0f, rotation)
        assertEquals(90, sample.count)
        assertTrue(sample.positions.any { it.z != 0f }, "the rotation reached the inner form")

        assertFalse(Forms.Fibonacci.counted(90) is ViewForm)
        assertFalse(Forms.Fibonacci.counted(90).rigid)
    }

    @Test
    fun `ViewRotation NONE is the identity`() {
        val view = ViewPoint()
        ViewRotation.NONE.place(view, 0.3f, -0.2f, 0.5f, 0f, 0f, 1f)
        assertEquals(0.3f, view.x)
        assertEquals(-0.2f, view.y)
        assertEquals(0.5f, view.z)
    }

    @Test
    fun `viewport maps pixels to unit space with Y flipped and back`() {
        assertEquals(0f, viewport.unitX(200f))
        assertEquals(1f, viewport.unitX(360f))
        assertEquals(1f, viewport.unitY(40f), "up is positive")
        assertEquals(123f, viewport.pixelX(viewport.unitX(123f)), 1e-4f)
        assertEquals(321f, viewport.pixelY(viewport.unitY(321f)), 1e-4f)
    }

    @Test
    fun `signed distance is negative inside and zero on the edge and positive outside`() {
        assertEquals(-30f, button.signedDistance(200f, 200f), 1e-4f)
        assertEquals(0f, button.signedDistance(200f, button.top), 1e-4f)
        assertEquals(10f, button.signedDistance(button.right + 10f, 200f), 1e-4f)
        // The square corner is outside a rounded one.
        assertTrue(button.signedDistance(button.left, button.top) > 0f)
    }

    @Test
    fun `rejects degenerate rects and viewports and fractions and counts`() {
        assertFailsWith<IllegalArgumentException> { RectPx(0f, 0f, 0f, 10f, 0f) }
        assertFailsWith<IllegalArgumentException> { RectPx(0f, 0f, 10f, -1f, 0f) }
        assertFailsWith<IllegalArgumentException> { RectPx(0f, 0f, 10f, 10f, -1f) }
        assertFailsWith<IllegalArgumentException> { OrbViewport(0f, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { PixelForm.roundedRect(button, viewport, perimeterFraction = 1.5f) }
        assertFailsWith<IllegalArgumentException> {
            PixelForm.roundedRect(
                button,
                viewport,
            ).sample(0, LumosLook.BASE, 0f)
        }
    }
}
