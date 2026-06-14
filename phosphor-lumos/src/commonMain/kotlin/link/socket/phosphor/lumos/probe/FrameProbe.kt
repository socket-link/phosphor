package link.socket.phosphor.lumos.probe

import kotlin.time.TimeSource

/**
 * Per-phase frame-timing sink. A `Probe` inspects without altering — measuring
 * a phase never changes the value the wrapped block produces.
 *
 * The probe is **off by default**: callers that do not opt in receive
 * [Disabled], a no-op singleton. Because [measure] is `inline`, a disabled
 * probe compiles down to the wrapped block plus a single predictable branch on
 * [isEnabled] — no [TimeSource] read, no lambda allocation, no per-frame
 * garbage. See [RingBufferFrameProbe] for the enabled implementation.
 *
 * Implementations are single-threaded by contract, matching the Lumos frame
 * loop (`CognitiveSceneRuntime` and `VoxelFrameBuilder` are themselves
 * single-threaded, pull-based). [record] and [summary] must not be called
 * concurrently.
 */
interface FrameProbe {
    /**
     * Whether this probe records samples. When false, [measure] skips all
     * timing work. Constant for the probe's lifetime so the branch in [measure]
     * stays predictable.
     */
    val isEnabled: Boolean

    /** Record a single [nanos] duration sample for [phase]. */
    fun record(
        phase: FramePhase,
        nanos: Long,
    )

    /** Snapshot the current p50/p95/max statistics for every phase. */
    fun summary(): FrameProbeSummary

    /** Drop all recorded samples. Used to discard warm-up before a measured run. */
    fun reset()

    /**
     * The shared no-op probe. [isEnabled] is false, so [measure] never times
     * anything and the other members do nothing.
     */
    object Disabled : FrameProbe {
        override val isEnabled: Boolean = false

        override fun record(
            phase: FramePhase,
            nanos: Long,
        ) = Unit

        override fun summary(): FrameProbeSummary = FrameProbeSummary(emptyMap())

        override fun reset() = Unit
    }
}

/**
 * Time [block] as [phase] and return its result unchanged.
 *
 * Inlined so that when [FrameProbe.isEnabled] is false this is exactly `block()`
 * behind one branch — zero added allocation. When enabled, the elapsed
 * monotonic nanos are forwarded to [FrameProbe.record] even if [block] throws.
 */
inline fun <T> FrameProbe.measure(
    phase: FramePhase,
    block: () -> T,
): T {
    if (!isEnabled) return block()
    val mark = TimeSource.Monotonic.markNow()
    try {
        return block()
    } finally {
        record(phase, mark.elapsedNow().inWholeNanoseconds)
    }
}
