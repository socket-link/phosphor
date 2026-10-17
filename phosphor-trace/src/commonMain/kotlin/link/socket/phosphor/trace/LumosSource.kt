package link.socket.phosphor.trace

import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
 * `PixelLattice`, `CliLattice`, and `LumosCanvas` all consume a `VoxelFrame`
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
     * **Rotation is carried across a loop's seam, not replayed.** A recorded
     * rotation only ever advances, so a looping segment shorter than a full
     * turn cannot wrap it: replayed as recorded, every iteration would drop the
     * orb back by the loop's whole travel. [frames] offsets each further
     * iteration's three ambient angles so it starts where the last frame left
     * off, one ordinary step on — the same carry [TraceStateMachine] applies.
     * The cells are the recorded ones, the first pass through a segment is the
     * recording itself, and a held frame is one instance. The carry covers the
     * wrap only: [TracePlayer.setSegment] and [TracePlayer.seek] are cuts, and
     * restart rotation from the recorded angle. It is applied where frames
     * leave this source, so the player's own [TracePlayer.currentFrame],
     * [TracePlayer.advance], and [TracePlayer.frames] still replay exactly what
     * was recorded.
     *
     * @property player The playhead over the recording. Reach for it to
     *  `play`, `pause`, `seek`, or change segment while the stream is being
     *  collected.
     * @property carryRotation Whether [frames] keeps the orb's rotation
     *  continuous across a looping segment's seam. On by default; pass `false`
     *  to emit recorded rotations untouched.
     */
    data class Trace(
        val player: TracePlayer,
        val carryRotation: Boolean = true,
    ) : LumosSource {
        /**
         * Build a trace source from a decoded recording.
         *
         * @param trace The recording to play, typically from [TraceCodec.decode].
         * @param initialSegment Segment to start on, or `null` for the whole trace.
         * @param carryRotation Whether to keep rotation continuous across loop seams; see [Trace].
         * @throws TracePlaybackError.UnknownSegment when the trace carries no such segment.
         */
        constructor(
            trace: VoxelTrace,
            initialSegment: String? = null,
            carryRotation: Boolean = true,
        ) : this(TracePlayer(trace, initialSegment), carryRotation)
    }
}

/**
 * Open this source as a frame stream — the single factory behind both arms.
 *
 * A live source emits one fixed simulation step per frame; a trace source emits
 * a recorded frame whenever the playhead crosses into it, with its rotation
 * carried unless [LumosSource.Trace.carryRotation] is off. Both pace themselves
 * against [timeSource] and neither ever emits faster than the source's fps.
 *
 * @param timeSource Clock both arms pace against. Defaults to the system
 *  monotonic clock; pass a test time source to run a stream in virtual time.
 */
fun LumosSource.frames(timeSource: TimeSource = TimeSource.Monotonic): Flow<VoxelFrame> =
    when (this) {
        is LumosSource.Live -> generator.frames(timeSource)
        is LumosSource.Trace -> if (carryRotation) player.carriedFrames(timeSource) else player.frames(timeSource)
    }

/**
 * [TracePlayer.frames] with each loop iteration's rotation carried.
 *
 * Paced the same way — one tick per recorded frame, the playhead advanced by
 * however much time has passed — but it emits when the *carried* frame
 * changes rather than when the index does, because a loop's first frame on
 * its second pass is the same index at a different angle. A held frame is the
 * identical instance on every tick, so holding still emits nothing.
 *
 * The carried angle is a function of the active segment and the playhead
 * alone, so collecting again resumes on the angle the last collection showed.
 */
private fun TracePlayer.carriedFrames(timeSource: TimeSource): Flow<VoxelFrame> =
    flow {
        val carry = RotationCarry(trace, frameDuration.inWholeNanoseconds, enabled = true)
        var entered: TraceSegment? = null
        val mark = timeSource.markNow()
        var consumed = Duration.ZERO
        var emitted: VoxelFrame? = null
        while (true) {
            val elapsed = mark.elapsedNow()
            val recorded = advance(elapsed - consumed)
            consumed = elapsed

            // setSegment can land between ticks; a cut restarts from the recording.
            if (activeSegment != entered) {
                entered = activeSegment
                carry.enter(activeSegment, rotation = null)
            }
            val frame = carry.apply(recorded, playhead.inWholeNanoseconds)
            if (frame !== emitted) {
                emitted = frame
                emit(frame)
            }
            delay(frameDuration)
        }
    }
