package link.socket.phosphor.lumos.look

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook

/**
 * PHO-59: retrying's `rewind`, ported from Thinking Orbs' milliseconds, and the
 * yaw it hands the builder.
 */
class RetryingRewindTest {
    @Test
    fun `rewind at 2 s is 2 s of turn, the source's 2000 times w`() {
        val w = 0.7
        assertEquals(2.0 * w, RetryingRewind.rewind(2.0, w), EXACT)
        assertEquals(1.0 * w, RetryingRewind.rewind(1.0, w), EXACT, "the turn is the plain spin")
        assertEquals(0.0, RetryingRewind.rewind(0.0, w), EXACT)
    }

    @Test
    fun `the spring starts at 0, ends at exactly 1 and overshoots between`() {
        assertEquals(0.0, RetryingRewind.spring(0.0), EXACT)
        assertEquals(1.0, RetryingRewind.spring(1.0), EXACT)
        val peak = (0..100).maxOf { RetryingRewind.spring(it / 100.0) }
        assertTrue(peak > 1.15, "springs past where it lets go: $peak")
    }

    @Test
    fun `a cycle brakes, gives back 60 percent of the turn and stacks 0_94 s of turn`() {
        val w = 1.0
        val stopped = RetryingRewind.rewind(2.2, w)
        assertEquals(2.1, stopped, EXACT, "the turn plus half the brake")
        assertEquals(2.1 * 0.4, RetryingRewind.rewind(3.0, w), EXACT, "60 % given back")
        assertEquals(0.94, RetryingRewind.rewind(3.2, w), EXACT, "one whole cycle")
        assertEquals(5 * 0.94 + 1.5, RetryingRewind.rewind(5 * 3.2 + 1.5, w), 1e-9, "cycles stack")
        assertEquals(-0.94 + 1.5, RetryingRewind.rewind(-3.2 + 1.5, w), 1e-9, "and run backward from zero")
    }

    @Test
    fun `rewind never jumps`() {
        // Sampled at 1 ms, the largest step is the spring's snap back, about 11 ms of turn.
        val w = 1.0
        var previous = RetryingRewind.rewind(-0.5, w)
        var largest = 0.0
        var step = -499
        while (step <= 7_000) {
            val next = RetryingRewind.rewind(step / 1000.0, w)
            largest = maxOf(largest, abs(next - previous))
            previous = next
            step++
        }
        assertTrue(largest < 0.012, "largest 1 ms step was $largest")
    }

    @Test
    fun `the look runs rewind at double time and half rate, so the turn is the look's 13 s pace`() {
        val retrying = RetryingRewind()
        val rate = 2.0 * PI / 13.0

        // Half a second in, still turning: half a second at the look's pace.
        assertEquals((0.5 * rate).toFloat(), retrying.yaw(0.5f), 1e-6f)
        // One cycle of look time is 1.6 s and gains 0.94 s of source turn at half rate.
        assertEquals((0.94 * rate / 2).toFloat(), retrying.yaw(Look.RETRYING_PERIOD_SECONDS), 1e-6f)
        assertEquals(Look.RETRYING_PERIOD_SECONDS.toDouble(), RetryingRewind.CYCLE / 2, 1e-6)
    }

    @Test
    fun `the look's yaw is wrapped into a turn`() {
        val retrying = RetryingRewind()
        for (t in listOf(0f, 100f, 1_000f, 86_400f)) {
            val yaw = retrying.yaw(t)
            assertTrue(yaw >= 0f && yaw < (2.0 * PI).toFloat(), "yaw($t) = $yaw")
        }
    }

    @Test
    fun `retrying owns the yaw and the four T6 idioms do not`() {
        val sample = Forms.Lattice(2).sample(0, LumosLook.BASE, 0f)
        assertTrue(Looks.forLook(Look(LumosLook.RETRYING), sample) is RetryingRewind)
        for (look in listOf(LumosLook.BASE, LumosLook.WORKING, LumosLook.SEARCHING, LumosLook.REASONING)) {
            assertNull(Looks.forLook(Look(look), sample).yaw(1f), "$look keeps the atmosphere's yaw")
        }
    }

    @Test
    fun `cells wear the base rim fade under the rewind`() {
        val out = CellModulation()
        val back = ViewPoint().apply { z = -1f }
        RetryingRewind().modulate(0, back, 0.3f, out)
        assertEquals(0f, out.alpha)
        assertEquals(0.5f / 1.9f, out.radius, 1e-6f)
    }

    private companion object {
        const val EXACT: Double = 1e-12
    }
}
