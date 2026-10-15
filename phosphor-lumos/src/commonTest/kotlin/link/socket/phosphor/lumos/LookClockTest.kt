package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LookClockTest {
    private val key = LookClock.key("idle", 1f)

    @Test
    fun `a key starts at zero whatever the wall clock reads`() {
        assertEquals(0.0, LookClock().tick(key, nowSeconds = 1_234.5, speed = 1f))
    }

    @Test
    fun `a key moves by the wall gap times speed`() {
        val clock = LookClock()
        clock.tick(key, nowSeconds = 10.0, speed = 2f)

        assertEquals(0.1, clock.tick(key, nowSeconds = 10.05, speed = 2f), EPSILON)
    }

    @Test
    fun `a gap longer than a tenth of a second moves a key a tenth`() {
        val clock = LookClock()
        clock.tick(key, nowSeconds = 0.0, speed = 1f)

        assertEquals(LookClock.MAX_GAP_SECONDS, clock.tick(key, nowSeconds = 3_600.0, speed = 1f), EPSILON)
    }

    @Test
    fun `the gap cap applies before speed`() {
        val fast = LookClock.key("idle", 3f)
        val clock = LookClock()
        clock.tick(fast, nowSeconds = 0.0, speed = 3f)

        assertEquals(0.3, clock.tick(fast, nowSeconds = 60.0, speed = 3f), EPSILON)
    }

    @Test
    fun `ticking one instant twice moves a key once`() {
        val clock = LookClock()
        clock.tick(key, nowSeconds = 1.0, speed = 1f)

        val first = clock.tick(key, nowSeconds = 1.016, speed = 1f)
        val second = clock.tick(key, nowSeconds = 1.016, speed = 1f)

        assertEquals(first, second)
    }

    @Test
    fun `time never runs backwards`() {
        val clock = LookClock()
        clock.tick(key, nowSeconds = 5.0, speed = 1f)
        val reached = clock.tick(key, nowSeconds = 5.05, speed = 1f)

        assertEquals(reached, clock.tick(key, nowSeconds = 4.0, speed = 1f))
        assertEquals(reached + 0.016, clock.tick(key, nowSeconds = 5.066, speed = 1f), EPSILON)
    }

    @Test
    fun `two orbs ticking one key in the same frames read the same time`() {
        val clock = LookClock()
        val first = mutableListOf<Double>()
        val second = mutableListOf<Double>()

        for (frame in 0 until 120) {
            val now = 50.0 + frame / 60.0
            first += clock.tick(key, now, speed = 1f)
            second += clock.tick(key, now, speed = 1f)
        }

        assertEquals(first, second)
        assertEquals(119 / 60.0, first.last(), EPSILON)
    }

    @Test
    fun `a key ticked from two frame clocks still keeps wall time`() {
        // Two windows, each with its own frame clock, eight milliseconds apart. Each tick moves the
        // key by the gap since the other window's tick, so neither window's orbs fall behind.
        val clock = LookClock()
        var time = 0.0
        for (frame in 0 until 60) {
            val first = frame / 60.0
            time = clock.tick(key, first, speed = 1f)
            time = clock.tick(key, first + 0.008, speed = 1f)
        }

        assertEquals(59 / 60.0 + 0.008, time, EPSILON)
    }

    @Test
    fun `keys keep separate time`() {
        val clock = LookClock()
        val thinking = LookClock.key("thinking", 1f)
        clock.tick(key, nowSeconds = 0.0, speed = 1f)
        clock.tick(key, nowSeconds = 0.05, speed = 1f)

        assertEquals(0.0, clock.tick(thinking, nowSeconds = 0.05, speed = 1f))
        assertEquals(0.05, clock.timeOf(key)!!, EPSILON)
    }

    @Test
    fun `timeOf reads a key without moving it`() {
        val clock = LookClock()
        assertNull(clock.timeOf(key))

        clock.tick(key, nowSeconds = 0.0, speed = 1f)
        clock.tick(key, nowSeconds = 0.04, speed = 1f)

        assertEquals(clock.timeOf(key), clock.timeOf(key))
        assertEquals(0.04, clock.timeOf(key)!!, EPSILON)
    }

    @Test
    fun `a key names both the look and the speed`() {
        assertNotEquals(LookClock.key("idle", 1f), LookClock.key("idle", 2f))
        assertNotEquals(LookClock.key("idle", 1f), LookClock.key("thinking", 1f))
        assertTrue(LookClock.key("idle", 1f).startsWith("idle@"))
    }

    private companion object {
        const val EPSILON: Double = 1e-9
    }
}
