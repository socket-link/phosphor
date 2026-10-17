package link.socket.phosphor.trace

import link.socket.phosphor.field.Choreography
import link.socket.phosphor.lumos.LumosForm
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.LumosSequence
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase

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
 * Cues are the frame-indexed, capture-only way to script a clip. The preferred
 * path is [CaptureConfig.sequence]: a [LumosSequence] is data, names its own
 * segments, and plays live through `SignalGenerator.play` from the same
 * definition. Cues remain for callers that script by frame.
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
 * A scripted form change applied during capture: `VoxelFrameBuilder.morphTo`
 * fired immediately before the step that produces frame [atFrame], so that
 * frame is the first in which the cells have left their old positions. Cells
 * keep their index across the morph, so a v3 trace keeps delta-coding them and
 * a crossfade keeps pairing them; a [count] other than the current one makes
 * that frame a cut for both.
 *
 * The segment a morph is recorded into is named like any transition, with the
 * arrow between the two forms: `"sphere→fibonacci"`; see [TraceSegment].
 *
 * @property atFrame Zero-based frame index at which the cue fires. Must be in `0 until frameCount`.
 * @property form The form to morph to.
 * @property count Cells to sample it at; null keeps the current count.
 * @property choreography The arc cells travel on.
 * @property durationSeconds How long the spring takes to settle; `0` snaps.
 * @property easingName Easing for the arc envelope, resolved by `Easing.byName`; null is linear.
 */
data class FormCue(
    val atFrame: Int,
    val form: LumosForm,
    val count: Int? = null,
    val choreography: Choreography = Choreography.FLOW,
    val durationSeconds: Float = VoxelFrameBuilder.DEFAULT_MORPH_SECONDS,
    val easingName: String? = null,
)

/**
 * A scripted phase change applied during capture: `SignalGenerator.setPhase`
 * fired immediately before the step that produces frame [atFrame], after that
 * frame's [AtmosphereCue]s and [FormCue]s. Phase cues at the same frame apply
 * in list order; the last one wins.
 *
 * The first phase cue installs the generator's look switch, so the orb's look
 * follows the phase from then on: [atFrame] of the first cue is the switch's
 * baseline and wears its look outright, and every later change blends over
 * 0.6 s and fires its glyph, if it carries one. The look bakes into the
 * recorded cells; the trace format does not change.
 *
 * Use phase cues for one-shots and tests, not for `loop` segments. A look
 * baked into a loop and replayed through [TraceStateMachine] drifts off the
 * front under rotation carry, by the loop's net yaw on every pass, until look
 * traces bake their yaw (PHO-59).
 *
 * @property atFrame Zero-based frame index at which the cue fires. Must be in `0 until frameCount`.
 * @property phase The phase the scene's agent moves to.
 */
data class PhaseCue(
    val atFrame: Int,
    val phase: CognitivePhase,
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
 *  Empty when [sequence] is set: the sequence names its own.
 * @property cues Scripted atmosphere changes; each must fire inside `0 until frameCount`. The
 *  frame-indexed, capture-only path; [sequence] is the preferred one. Empty when [sequence] is set.
 * @property formCues Scripted form changes; each must fire inside `0 until frameCount`. Empty when
 *  [sequence] is set.
 * @property phaseCues Scripted phase changes; each must fire inside `0 until frameCount`. Empty
 *  when [sequence] is set.
 * @property phosphorVersion Library version stamped into the trace header.
 * @property createdAtEpochMs Creation timestamp stamped into the trace header. Defaults to `0`
 *  so captures are reproducible; callers that want a real timestamp supply one explicitly.
 * @property seamBlendFrames Frames at the end of every `loop` segment that fade toward the
 *  loop's own steady state run backwards from its first frame, so the loop wraps continuously
 *  even when a transition ends the frame before it starts; see [Oscilloscope]. `0`, the
 *  default, records the simulation untouched. Must be `>= 0` and shorter than every loop segment.
 * @property sequence The clip as a [LumosSequence]: the preferred way to script a capture. When
 *  set, the generator plays it (`SignalGenerator.play`) and one [TraceSegment] is derived per
 *  step, named as the step is and carrying its `loop`, so the clip replays through
 *  [TraceStateMachine] by step name with nothing else written. Frame `k` is the sequence at
 *  `k / fps`; a step shorter than a frame is an error. [frameCount] must cover one pass of the
 *  sequence — [forSequence] sizes it — and [segments], [cues], [formCues] and [phaseCues] must be
 *  empty. Needs `fps >= ` [MIN_SEQUENCE_FPS], the rate below which a fixed step would exceed the
 *  player's cap.
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
    val seamBlendFrames: Int = 0,
    val formCues: List<FormCue> = emptyList(),
    val phaseCues: List<PhaseCue> = emptyList(),
    val sequence: LumosSequence? = null,
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

        /**
         * The slowest rate a [sequence] captures at: `1 / fps` must not exceed
         * `LumosSequencePlayer.MAX_STEP_SECONDS`, or the fixed step would be cut.
         */
        const val MIN_SEQUENCE_FPS: Int = 10

        /**
         * A config that captures one pass of [sequence] at [fps]: [durationMs] is the
         * shortest that yields every frame of the sequence and no more, so the clip
         * ends where the sequence does and nothing wraps.
         *
         * @param sequence The clip, as data.
         * @param fps Frames per second; at least [MIN_SEQUENCE_FPS].
         * @param params Tuning parameters; the starting atmosphere should be the one the
         *  sequence's first step expects.
         * @param seed Producer seed, as [CaptureConfig.seed].
         * @param seamBlendFrames Baked seam for every `loop` step, as [CaptureConfig.seamBlendFrames].
         * @param phosphorVersion As [CaptureConfig.phosphorVersion].
         * @param createdAtEpochMs As [CaptureConfig.createdAtEpochMs].
         */
        fun forSequence(
            sequence: LumosSequence,
            fps: Int,
            params: CaptureParams = CaptureParams(),
            seed: Long = 0L,
            seamBlendFrames: Int = 0,
            phosphorVersion: String = DEFAULT_PHOSPHOR_VERSION,
            createdAtEpochMs: Long = 0L,
        ): CaptureConfig {
            require(fps > 0) { "fps must be > 0, was $fps" }
            val frames = sequence.frameLayout(fps).frameCount
            // The shortest duration whose truncated frame count is at least `frames`.
            val durationMs = (frames * 1_000L + fps - 1) / fps
            return CaptureConfig(
                params = params,
                seed = seed,
                durationMs = durationMs,
                fps = fps,
                phosphorVersion = phosphorVersion,
                createdAtEpochMs = createdAtEpochMs,
                seamBlendFrames = seamBlendFrames,
                sequence = sequence,
            )
        }
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
     * The [CaptureConfig] is inconsistent: non-positive fps or duration, a
     * segment or cue outside the clip, a sequence the clip cannot hold or that
     * is given alongside cues, or a seam blend that cannot fit or pair.
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
