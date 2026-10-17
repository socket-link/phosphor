package link.socket.phosphor.lumos

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.field.Choreography
import link.socket.phosphor.palette.AtmospherePresets

/**
 * PHO-74 Task B: `burst` pushes the body out and lets it home, from rest or in
 * front of a morph, driven through a [LiveFrameSource] as a surface would.
 */
class VoxelFrameBuilderBurstTest {
    // Everything that moves the skin is off, so a cell's position is its rest position.
    private val still =
        AtmospherePresets.IDLE.copy(
            resolution = 4,
            noise = 0f,
            surfaceBump = 0f,
            pulseAmplitude = 0f,
            ySquash = 1f,
            rotationX = 0f,
            rotationY = 0f,
        )

    @Test
    fun `a burst from rest pushes every cell out and then the form renders exactly as if it never fired`() {
        val source = LiveFrameSource(still)
        val twin = LiveFrameSource(still)
        val rest = source.next(DT)
        twin.next(DT)

        val form = source.builder.form
        source.builder.burst(6f)
        assertTrue(source.builder.isMorphing, "a burst from rest starts a trip to spring back on")
        assertSame(form, source.builder.form, "the form does not change")

        val pushed = source.next(DT)
        twin.next(DT)
        val restRadii = radii(rest)
        val pushedRadii = radii(pushed)
        for (i in restRadii.indices) {
            // The lattice is a solid ball: its centre cell sits on the centroid and has no outward.
            if (restRadii[i] < 1e-3) continue
            assertTrue(pushedRadii[i] > restRadii[i], "cell $i should move out: ${restRadii[i]} -> ${pushedRadii[i]}")
        }

        var frames = 1
        var last = pushed
        var twinLast = last
        while (source.builder.isMorphing) {
            last = source.next(DT)
            twinLast = twin.next(DT)
            frames++
            assertTrue(frames < 60, "the burst's trip should settle within a second")
        }
        assertEquals(twinLast, last, "once home, the frame is the form at rest")
    }

    @Test
    fun `a burst then a morph carries the push into the morph and settles where the morph alone does`() {
        val bursting = LiveFrameSource(still)
        val plain = LiveFrameSource(still)
        bursting.next(DT)
        plain.next(DT)

        bursting.builder.burst(6f)
        bursting.builder.morphTo(Forms.Fibonacci, choreography = Choreography.DIRECT)
        plain.builder.morphTo(Forms.Fibonacci, choreography = Choreography.DIRECT)

        val burst = bursting.next(DT)
        val morph = plain.next(DT)
        assertTrue(
            radii(burst).average() > radii(morph).average() + 0.05,
            "the burst should still be pushing in the morph's first frame",
        )

        var frames = 1
        var a = burst
        var b = morph
        while (bursting.builder.isMorphing || plain.builder.isMorphing) {
            a = bursting.next(DT)
            b = plain.next(DT)
            frames++
            assertTrue(frames < 300, "both morphs should settle within five seconds")
        }
        assertSame(Forms.Fibonacci, bursting.builder.form)
        assertEquals(b, a, "settled, the burst leaves no trace")
    }

    @Test
    fun `a burst mid-morph acts on the trip in flight`() {
        val source = LiveFrameSource(still)
        source.next(DT)
        source.builder.morphTo(Forms.Fibonacci, choreography = Choreography.DIRECT)
        repeat(30) { source.next(DT) }

        source.builder.burst(6f)
        assertSame(Forms.Fibonacci, source.builder.form)
        assertTrue(source.builder.isMorphing)
        while (source.builder.isMorphing) source.next(DT)
        assertFalse(source.builder.isMorphing)
    }

    @Test
    fun `reduced motion drops a burst with the morph`() {
        val source = LiveFrameSource(still)
        source.next(DT)
        source.builder.burst(6f)
        source.still(DT)
        assertFalse(source.builder.isMorphing)
    }

    @Test
    fun `burst rejects a non-finite strength and a non-positive duration`() {
        val builder = LiveFrameSource(still).builder
        assertFailsWith<IllegalArgumentException> { builder.burst(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { builder.burst(6f, durationSeconds = 0f) }
        assertFalse(builder.isMorphing, "a rejected burst starts nothing")
    }

    private fun radii(frame: VoxelFrame): List<Double> =
        frame.cells.map { sqrt((it.x * it.x + it.y * it.y + it.z * it.z).toDouble()) }

    private companion object {
        const val DT = 1f / 60f
    }
}
