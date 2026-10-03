package link.socket.phosphor.trace

import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState

/**
 * Tuning parameters that shape every frame of a capture.
 *
 * These are the values a Workbench user tunes and are what [VoxelTrace.paramSnapshot]
 * records, so a trace can always be traced back to the parameters that produced it.
 *
 * @property atmosphere Atmosphere the scene starts in. Later changes are scripted with [AtmosphereCue]s.
 * @property renderConfig Embedder knobs forwarded to `VoxelFrameBuilder`.
 */
data class CaptureParams(
    val atmosphere: AtmosphereState = AtmospherePresets.IDLE,
    val renderConfig: LumosRenderConfig = LumosRenderConfig(),
)

/**
 * A scripted atmosphere change applied during capture.
 *
 * The cue fires immediately before the simulation step that produces frame
 * [atFrame], so that frame is the first one to reflect the start of the
 * transition. Cues at the same frame are applied in list order; the last one wins.
 *
 * @property atFrame Zero-based frame index at which the cue fires. Must be in `0 until frameCount`.
 * @property atmosphere Target atmosphere. When it equals a registered preset the
 *  transition table in `AtmosphereChoreographer` is consulted by reverse lookup.
 */
data class AtmosphereCue(
    val atFrame: Int,
    val atmosphere: AtmosphereState,
)

/**
 * Everything [Oscilloscope.capture] needs to produce a [VoxelTrace], and nothing
 * it may read from the environment.
 *
 * Determinism rule: two equal configs must yield byte-identical traces. That is
 * why wall-clock values ([createdAtEpochMs]) and the library version
 * ([phosphorVersion]) are inputs here rather than looked up during capture.
 *
 * @property params Tuning parameters recorded into [VoxelTrace.paramSnapshot].
 * @property seed Producer seed forwarded to `SceneConfiguration.seed` and recorded in the trace header.
 * @property durationMs Length of the clip in milliseconds. Must be `> 0`.
 * @property fps Frames per second; the simulation is stepped at exactly `1f / fps`. Must be `> 0`.
 * @property segments Named slices of the captured stream; each must fit inside `0 until frameCount`.
 * @property cues Scripted atmosphere changes; each must fire inside `0 until frameCount`.
 * @property phosphorVersion Library version stamped into the trace header.
 * @property createdAtEpochMs Creation timestamp stamped into the trace header. Defaults to `0`
 *  so captures are reproducible; callers that want a real timestamp supply one explicitly.
 */
data class CaptureConfig(
    val params: CaptureParams = CaptureParams(),
    val seed: Long = 0L,
    val durationMs: Long,
    val fps: Int,
    val segments: List<TraceSegment> = emptyList(),
    val cues: List<AtmosphereCue> = emptyList(),
    val phosphorVersion: String = DEFAULT_PHOSPHOR_VERSION,
    val createdAtEpochMs: Long = 0L,
) {
    /**
     * Number of frames the capture produces: `durationMs * fps / 1000`, truncated.
     * Zero or negative when [durationMs] or [fps] is invalid; [Oscilloscope.capture]
     * reports that as [CaptureError.InvalidConfig].
     */
    val frameCount: Int
        get() =
            ((durationMs * fps) / 1_000L)
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                .toInt()

    companion object {
        /** Placeholder version recorded when the caller does not supply one. */
        const val DEFAULT_PHOSPHOR_VERSION: String = "dev"
    }
}

/**
 * Typed failure modes returned by [Oscilloscope.capture].
 *
 * Capture follows the house Result pattern: it never throws, it returns a
 * [Result] whose failure cause is one of these.
 */
sealed class CaptureError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The [CaptureConfig] is inconsistent: non-positive fps or duration, or a
     * segment or cue outside the clip.
     */
    class InvalidConfig(
        message: String,
    ) : CaptureError(message)

    /** The simulation or trace assembly threw; the original exception is the [cause]. */
    class SimulationFailure(
        message: String,
        cause: Throwable,
    ) : CaptureError(message, cause)
}
