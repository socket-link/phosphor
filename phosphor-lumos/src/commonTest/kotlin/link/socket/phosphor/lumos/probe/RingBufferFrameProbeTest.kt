package link.socket.phosphor.lumos.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RingBufferFrameProbeTest {
    @Test
    fun `summary math is correct on a full synthetic sequence`() {
        val probe = RingBufferFrameProbe(capacity = 600)
        // Record 1..600 ns; sorted order is identical to insertion order here.
        for (n in 1..600) probe.record(FramePhase.BUILD, n.toLong())

        val stats = probe.summary().forPhase(FramePhase.BUILD)
        assertEquals(600, stats.sampleCount)
        // Nearest-rank: p50 -> rank 300 -> value 300; p95 -> rank 570 -> value 570.
        assertEquals(300L, stats.p50Nanos)
        assertEquals(570L, stats.p95Nanos)
        assertEquals(600L, stats.maxNanos)
    }

    @Test
    fun `ring buffer wraps and retains only the most recent capacity samples`() {
        val probe = RingBufferFrameProbe(capacity = 600)
        // 900 records into a 600-slot ring: the first 300 are overwritten,
        // leaving values 301..900.
        for (n in 1..900) probe.record(FramePhase.PROJECT, n.toLong())

        val stats = probe.summary().forPhase(FramePhase.PROJECT)
        assertEquals(600, stats.sampleCount)
        assertEquals(600L, stats.p50Nanos) // sorted[299] of 301..900
        assertEquals(870L, stats.p95Nanos) // sorted[569] of 301..900
        assertEquals(900L, stats.maxNanos)
    }

    @Test
    fun `unsorted input still yields correct percentiles`() {
        val probe = RingBufferFrameProbe(capacity = 8)
        // Insert in scrambled order; percentiles must reflect sorted ranks.
        listOf(40L, 10L, 30L, 20L).forEach { probe.record(FramePhase.DRAW, it) }

        val stats = probe.summary().forPhase(FramePhase.DRAW)
        assertEquals(4, stats.sampleCount)
        assertEquals(20L, stats.p50Nanos) // rank 2 of [10,20,30,40]
        assertEquals(40L, stats.p95Nanos) // rank 4
        assertEquals(40L, stats.maxNanos)
    }

    @Test
    fun `a single sample reports itself for every statistic`() {
        val probe = RingBufferFrameProbe(capacity = 4)
        probe.record(FramePhase.BUILD, 42L)

        val stats = probe.summary().forPhase(FramePhase.BUILD)
        assertEquals(1, stats.sampleCount)
        assertEquals(42L, stats.p50Nanos)
        assertEquals(42L, stats.p95Nanos)
        assertEquals(42L, stats.maxNanos)
    }

    @Test
    fun `phases accumulate independently`() {
        val probe = RingBufferFrameProbe(capacity = 16)
        probe.record(FramePhase.BUILD, 5L)
        probe.record(FramePhase.BUILD, 7L)
        probe.record(FramePhase.PROJECT, 100L)

        val summary = probe.summary()
        assertEquals(2, summary.forPhase(FramePhase.BUILD).sampleCount)
        assertEquals(1, summary.forPhase(FramePhase.PROJECT).sampleCount)
        // DRAW never recorded -> absent from the map, zeroed via forPhase.
        assertNull(summary.stats[FramePhase.DRAW])
        assertEquals(0, summary.forPhase(FramePhase.DRAW).sampleCount)
        assertEquals(0L, summary.forPhase(FramePhase.DRAW).maxNanos)
    }

    @Test
    fun `reset discards all samples`() {
        val probe = RingBufferFrameProbe(capacity = 8)
        repeat(8) { probe.record(FramePhase.DRAW, 1L) }
        probe.reset()

        assertTrue(probe.summary().stats.isEmpty())
        // Reusable after reset.
        probe.record(FramePhase.DRAW, 9L)
        assertEquals(1, probe.summary().forPhase(FramePhase.DRAW).sampleCount)
    }

    @Test
    fun `measure records one sample per call and returns the block result`() {
        val probe = RingBufferFrameProbe(capacity = 4)
        val result = probe.measure(FramePhase.BUILD) { 1 + 1 }
        assertEquals(2, result)
        assertEquals(1, probe.summary().forPhase(FramePhase.BUILD).sampleCount)
    }

    @Test
    fun `measure records even when the block throws`() {
        val probe = RingBufferFrameProbe(capacity = 4)
        assertFailsWith<IllegalStateException> {
            probe.measure(FramePhase.PROJECT) { error("boom") }
        }
        assertEquals(1, probe.summary().forPhase(FramePhase.PROJECT).sampleCount)
    }

    @Test
    fun `disabled probe never records and returns the block result`() {
        val probe = FrameProbe.Disabled
        assertFalse(probe.isEnabled)
        val result = probe.measure(FramePhase.DRAW) { "ok" }
        assertEquals("ok", result)
        assertTrue(probe.summary().stats.isEmpty())
    }

    @Test
    fun `capacity must be positive`() {
        assertFailsWith<IllegalArgumentException> { RingBufferFrameProbe(capacity = 0) }
        assertFailsWith<IllegalArgumentException> { RingBufferFrameProbe(capacity = -1) }
    }

    @Test
    fun `percentile nearest-rank boundaries`() {
        val sorted = longArrayOf(10, 20, 30, 40, 50)
        assertEquals(10L, RingBufferFrameProbe.percentile(sorted, 1))
        assertEquals(30L, RingBufferFrameProbe.percentile(sorted, 50))
        assertEquals(50L, RingBufferFrameProbe.percentile(sorted, 95))
        assertEquals(50L, RingBufferFrameProbe.percentile(sorted, 100))
    }
}
