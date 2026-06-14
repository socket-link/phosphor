package link.socket.phosphor.lumos.probe

/**
 * Enabled [FrameProbe] backed by a fixed-size ring buffer per phase.
 *
 * Each phase keeps the most recent [capacity] samples in a preallocated
 * [LongArray]; [record] overwrites the oldest slot once full, so the hot path
 * never allocates. Only [summary] allocates — it copies and sorts the retained
 * samples — and it is intended to run off the frame loop (once, for reporting).
 *
 * Not thread-safe: drive it from the single render thread, matching the rest of
 * the Lumos pipeline.
 *
 * @param capacity Samples retained per phase. Defaults to [DEFAULT_CAPACITY].
 */
class RingBufferFrameProbe(
    val capacity: Int = DEFAULT_CAPACITY,
) : FrameProbe {
    init {
        require(capacity > 0) { "capacity must be > 0, got $capacity" }
    }

    override val isEnabled: Boolean = true

    // One ring per phase, indexed by FramePhase.ordinal. Preallocated; never resized.
    private val rings: Array<LongArray> = Array(FramePhase.entries.size) { LongArray(capacity) }

    // Monotonic count of records per phase. The live slice is the last
    // min(written, capacity) entries; written % capacity is the next write slot.
    private val written: LongArray = LongArray(FramePhase.entries.size)

    override fun record(
        phase: FramePhase,
        nanos: Long,
    ) {
        val i = phase.ordinal
        val count = written[i]
        rings[i][(count % capacity).toInt()] = nanos
        written[i] = count + 1
    }

    override fun summary(): FrameProbeSummary {
        val stats = HashMap<FramePhase, PhaseStats>(FramePhase.entries.size)
        for (phase in FramePhase.entries) {
            val i = phase.ordinal
            val size = minOf(written[i], capacity.toLong()).toInt()
            if (size == 0) continue
            stats[phase] = computeStats(phase, rings[i], size)
        }
        return FrameProbeSummary(stats)
    }

    override fun reset() {
        written.fill(0L)
    }

    private fun computeStats(
        phase: FramePhase,
        ring: LongArray,
        size: Int,
    ): PhaseStats {
        // Copy the live slice and sort ascending; ring order doesn't matter for percentiles.
        val sorted = ring.copyOf(size)
        sorted.sort()
        return PhaseStats(
            phase = phase,
            sampleCount = size,
            p50Nanos = percentile(sorted, 50),
            p95Nanos = percentile(sorted, 95),
            maxNanos = sorted[size - 1],
        )
    }

    companion object {
        /** Default retained samples per phase: 600 (~10s at 60fps, ~20s at 30fps). */
        const val DEFAULT_CAPACITY: Int = 600

        /**
         * Nearest-rank percentile of an ascending-[sorted] array.
         *
         * For [percent] `p` and size `n`, returns the element at rank
         * `ceil(p/100 * n)` (1-based), clamped to `[1, n]`. So `p95` of 600
         * samples is `sorted[569]` and `p50` of an even count rounds up to the
         * upper-middle element. Requires `sorted` non-empty.
         */
        internal fun percentile(
            sorted: LongArray,
            percent: Int,
        ): Long {
            val n = sorted.size
            // ceil(percent * n / 100) without floating point.
            val rank = ((percent.toLong() * n + 99) / 100).toInt().coerceIn(1, n)
            return sorted[rank - 1]
        }
    }
}
