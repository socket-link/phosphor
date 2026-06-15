package link.socket.phosphor.lumos.probe

/**
 * Percentile statistics for a single [FramePhase], in nanoseconds.
 *
 * Percentiles use the nearest-rank method over the samples currently retained
 * in the probe's ring buffer (at most its capacity). With an empty sample set
 * every figure is zero and [sampleCount] is 0.
 *
 * @property phase The phase these statistics describe.
 * @property sampleCount Number of samples the statistics were computed from.
 * @property p50Nanos Median (50th percentile) duration.
 * @property p95Nanos 95th percentile duration.
 * @property maxNanos Largest retained sample.
 */
data class PhaseStats(
    val phase: FramePhase,
    val sampleCount: Int,
    val p50Nanos: Long,
    val p95Nanos: Long,
    val maxNanos: Long,
)

/**
 * A snapshot of [PhaseStats] per measured [FramePhase].
 *
 * Phases that have never recorded a sample are absent from [stats]; [forPhase]
 * returns an empty (all-zero) [PhaseStats] for them so callers can render a
 * full table without null checks.
 */
data class FrameProbeSummary(
    val stats: Map<FramePhase, PhaseStats>,
) {
    /** Statistics for [phase], or an all-zero [PhaseStats] when none were recorded. */
    fun forPhase(phase: FramePhase): PhaseStats =
        stats[phase] ?: PhaseStats(phase, sampleCount = 0, p50Nanos = 0, p95Nanos = 0, maxNanos = 0)
}
