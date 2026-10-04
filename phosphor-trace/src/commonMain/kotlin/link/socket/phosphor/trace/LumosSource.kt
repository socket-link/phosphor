package link.socket.phosphor.trace

import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.runtime.SceneConfiguration

/**
 * Where a stream of `VoxelFrame`s comes from: a running simulation, or a recording.
 *
 * This is the whole consumer-facing seam. A caller declares the source and
 * collects [frames]; swapping a live orb for a baked one is a one-line change at
 * the declaration and nothing else moves:
 *
 * ```kotlin
 * val source = LumosSource.Live(sceneConfiguration, fps = 60)
 * // val source = LumosSource.Trace(trace, initialSegment = "thinking")
 *
 * val frame by source.frames().collectAsState(initial = null)
 * frame?.let { LumosCanvas(lattice.project(it)) }
 * ```
 *
 * The seam sits *upstream* of projection, which is what makes the swap free.
 * `ComposeLattice`, `CliLattice`, and `LumosCanvas` all consume a `VoxelFrame`
 * and have no idea whether it was simulated a microsecond ago or recorded last
 * week — so rotation, depth sorting, halo, theming, and the glyph overlay stay
 * live over a replay without a line of adapter code.
 *
 * Each arm carries the handle that controls it, so declaring a source and
 * driving it stay separate concerns: [Trace] exposes its [TracePlayer] for
 * `play`/`pause`/`seek`/`setSegment`, [Live] exposes its [SignalGenerator] for
 * atmosphere and glyph changes.
 */
sealed interface LumosSource {
    /**
     * Frames from a simulation running right now.
     *
     * @property generator The running simulation. Reach for it to change
     *  atmosphere or fire a glyph while the stream is being collected.
     */
    data class Live(
        val generator: SignalGenerator,
    ) : LumosSource {
        /**
         * Build a live source from the runtime inputs directly.
         *
         * @param configuration Scene to simulate; atmosphere must be enabled.
         * @param renderConfig Embedder knobs forwarded to the frame builder.
         * @param fps Fixed simulation rate. Match a trace's fps to compare the two.
         */
        constructor(
            configuration: SceneConfiguration,
            renderConfig: LumosRenderConfig = LumosRenderConfig(),
            fps: Int = SignalGenerator.DEFAULT_FPS,
        ) : this(SignalGenerator(configuration, renderConfig, fps))
    }

    /**
     * Frames from a recording.
     *
     * @property player The playhead over the recording. Reach for it to
     *  `play`, `pause`, `seek`, or change segment while the stream is being
     *  collected.
     */
    data class Trace(
        val player: TracePlayer,
    ) : LumosSource {
        /**
         * Build a trace source from a decoded recording.
         *
         * @param trace The recording to play, typically from [TraceCodec.decode].
         * @param initialSegment Segment to start on, or `null` for the whole trace.
         * @throws TracePlaybackError.UnknownSegment when the trace carries no such segment.
         */
        constructor(
            trace: VoxelTrace,
            initialSegment: String? = null,
        ) : this(TracePlayer(trace, initialSegment))
    }
}

/**
 * Open this source as a frame stream — the single factory behind both arms.
 *
 * A live source emits one fixed simulation step per frame; a trace source emits
 * a recorded frame whenever the playhead crosses into it. Both pace themselves
 * against [timeSource] and neither ever emits faster than the source's fps.
 *
 * @param timeSource Clock both arms pace against. Defaults to the system
 *  monotonic clock; pass a test time source to run a stream in virtual time.
 */
fun LumosSource.frames(timeSource: TimeSource = TimeSource.Monotonic): Flow<VoxelFrame> =
    when (this) {
        is LumosSource.Live -> generator.frames(timeSource)
        is LumosSource.Trace -> player.frames(timeSource)
    }
