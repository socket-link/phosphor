package link.socket.phosphor.trace

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame

/**
 * Typed playback failures thrown by [TracePlayer].
 *
 * Trace *loading* follows the house Result pattern — see [TraceCodec.decode].
 * Playback failures are thrown instead, because by the time a player exists the
 * payload has already been validated and what remains is caller error.
 */
sealed class TracePlaybackError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * A segment was requested by a name the trace does not carry.
     *
     * @property name The name that was asked for.
     * @property available Segment names the trace does carry, in declaration order.
     */
    class UnknownSegment(
        val name: String,
        val available: List<String>,
    ) : TracePlaybackError(
            "Unknown segment '$name'; this trace carries " +
                (
                    if (available.isEmpty()) {
                        "no named segments"
                    } else {
                        available.joinToString(prefix = "[", postfix = "]")
                    }
                ),
        )
}

/**
 * Plays a recorded [VoxelTrace] back as a `VoxelFrame` stream.
 *
 * The counterpart to [Oscilloscope]: where the oscilloscope records a live
 * signal to a trace, the player drives a recorded trace back onto the wire. The
 * frames it emits enter the pipeline at exactly the point live frames do —
 * upstream of both projection lattices — so projection, rotation, glyph
 * overlay, and theming all stay live and unchanged behind a replay.
 *
 * **Time maps to frames by truncation.** Wall time is divided by [frameDuration]
 * and floored, so every instant inside a recorded frame's window resolves to
 * that frame. A display refreshing at 120 Hz over a 30 fps trace therefore sees
 * each recorded frame four times, and sees the *identical instance* each time —
 * the player holds the last frame it built and only reconstructs when the
 * resolved index moves. Nothing is allocated on a refresh that holds.
 *
 * **Reconstruction is lazy and per-frame.** A frame is assembled on demand by
 * slicing the trace's columnar channel arrays, never via [VoxelTrace.toFrames],
 * which materializes the whole recording at once (a 10 s capture at 60 fps and
 * resolution 10 is 600 × 4,801 cells — far too much to hold for playback).
 *
 * The player is stateful and single-threaded, like `VoxelFrameBuilder`: one
 * player per stream, driven from one coroutine. Collecting [frames] twice
 * concurrently makes two collectors fight over one playhead.
 *
 * @param trace The recording to play. Must carry at least one frame.
 * @param initialSegment Name of the segment to start on, or `null` to play the
 *  whole trace as one non-looping segment.
 * @throws TracePlaybackError.UnknownSegment when [initialSegment] names a
 *  segment the trace does not carry.
 */
class TracePlayer(
    private val trace: VoxelTrace,
    initialSegment: String? = null,
) {
    /**
     * Wall-clock duration of one recorded frame, `1 / fps`.
     *
     * Truncated to whole nanoseconds, so a 30 fps trace holds each frame for
     * 33,333,333 ns rather than an exact third of a tenth of a second. The
     * player divides by this same value, so playback stays internally
     * consistent; the ~10 ns per frame it gives up against the wall clock is
     * three orders of magnitude below a display refresh.
     */
    val frameDuration: Duration = (1.seconds / trace.fps)

    /** Names of the segments this trace carries, in declaration order. */
    val segmentNames: List<String> = trace.segments.map(TraceSegment::name)

    private val frameNanos: Long = frameDuration.inWholeNanoseconds

    // Prefix sums into the columnar cell channels: cellOffsets[i] is the index
    // of frame i's first cell. Computed once so seek is O(1) rather than a scan.
    private val cellOffsets: IntArray

    private var segment: TraceSegment
    private var playheadNanos: Long = 0L
    private var cachedIndex: Int = -1
    private var cachedFrame: VoxelFrame? = null

    init {
        require(trace.frameCount > 0) {
            "A TracePlayer requires a trace with at least one frame"
        }
        require(frameNanos > 0L) {
            "fps=${trace.fps} yields a frame shorter than a nanosecond"
        }

        cellOffsets = IntArray(trace.frameCount)
        var cursor = 0
        for (frameIndex in 0 until trace.frameCount) {
            cellOffsets[frameIndex] = cursor
            cursor += trace.cellCounts[frameIndex]
        }

        segment = initialSegment?.let(::resolveSegment) ?: wholeTraceSegment()
    }

    /**
     * Whether the playhead advances when time passes. Starts `true`; a paused
     * player holds its current frame indefinitely.
     */
    var isPlaying: Boolean = true
        private set

    /** The segment currently being played. */
    val activeSegment: TraceSegment get() = segment

    /** How far the playhead has travelled into [activeSegment]. */
    val playhead: Duration get() = playheadNanos.nanoseconds

    /** Absolute index into the trace of the frame the playhead currently rests on. */
    val currentFrameIndex: Int get() = indexAtNanos(playheadNanos)

    /** The frame the playhead currently rests on. */
    val currentFrame: VoxelFrame get() = frameFor(currentFrameIndex)

    /** Resume advancing the playhead. */
    fun play() {
        isPlaying = true
    }

    /** Stop advancing the playhead. The current frame is held until [play]. */
    fun pause() {
        isPlaying = false
    }

    /**
     * Move the playhead to [frameIndex], an absolute index into the trace.
     *
     * The index is clamped into [activeSegment], so seeking outside the active
     * slice lands on its nearest edge rather than silently leaving it. Seeking
     * does not change [isPlaying].
     *
     * @throws IllegalArgumentException when [frameIndex] is outside the trace.
     */
    fun seek(frameIndex: Int) {
        require(frameIndex in 0 until trace.frameCount) {
            "frameIndex $frameIndex is outside the trace's ${trace.frameCount} frames"
        }
        val landed = frameIndex.coerceIn(segment.startFrame, segment.endFrame)
        playheadNanos = (landed - segment.startFrame).toLong() * frameNanos
    }

    /**
     * Switch to the segment named [name] and rewind the playhead to its first
     * frame. Does not change [isPlaying].
     *
     * Crossfading and scripted transitions between segments are deliberately
     * absent: a player plays one segment at a time.
     *
     * @throws TracePlaybackError.UnknownSegment when the trace carries no such segment.
     */
    fun setSegment(name: String) {
        segment = resolveSegment(name)
        playheadNanos = 0L
    }

    /**
     * The frame [elapsed] into [activeSegment], with hold-last semantics.
     *
     * Pure in everything but the active segment: it reads neither the playhead
     * nor [isPlaying], so it is the function to reach for when mapping an
     * externally-owned clock onto the recording.
     */
    fun frameAt(elapsed: Duration): VoxelFrame = frameFor(frameIndexAt(elapsed))

    /**
     * The absolute trace index that [elapsed] into [activeSegment] resolves to.
     *
     * A looping segment wraps with no gap at the seam: the instant after its
     * last frame's window closes resolves to its first frame. A non-looping
     * segment clamps on its last frame and stays there.
     */
    fun frameIndexAt(elapsed: Duration): Int = indexAtNanos(elapsed.inWholeNanoseconds)

    /**
     * Advance the playhead by [delta] and return the frame it now rests on.
     *
     * A no-op on the playhead while paused — but still returns the held frame,
     * so a caller driving the player from its own render loop can treat this as
     * "give me the frame for right now".
     */
    fun advance(delta: Duration): VoxelFrame {
        require(delta >= Duration.ZERO) { "delta must be >= 0, was $delta" }
        if (isPlaying) {
            playheadNanos += delta.inWholeNanoseconds
        }
        return currentFrame
    }

    /**
     * Emit the recording as a frame stream paced by [timeSource].
     *
     * The flow wakes every [tickInterval], advances the playhead by however much
     * real time has passed, and emits **only when the resolved frame index
     * moves**. Holding a frame therefore emits nothing and allocates nothing: a
     * paused player goes quiet after its first emission, and a 30 fps trace on a
     * 120 Hz display emits 30 times a second, not 120. Consumers that hold the
     * last collected value — Compose state, for one — see exactly the right
     * frame at every refresh regardless.
     *
     * The flow is cold but the player is not: each collection drives the same
     * shared playhead. Collect it once per player.
     *
     * @param timeSource Clock the playhead reads. Defaults to the system
     *  monotonic clock; pass a test time source to drive playback in virtual time.
     * @param tickInterval How often to re-resolve the playhead. Defaults to one
     *  recorded frame, which is as often as the answer can change on its own;
     *  shorten it when [play], [pause], [seek], or [setSegment] are called from
     *  another coroutine and should land faster than a frame.
     */
    fun frames(
        timeSource: TimeSource = TimeSource.Monotonic,
        tickInterval: Duration = frameDuration,
    ): Flow<VoxelFrame> {
        require(tickInterval > Duration.ZERO) { "tickInterval must be > 0, was $tickInterval" }
        return flow {
            val mark = timeSource.markNow()
            var consumed = Duration.ZERO
            var emittedIndex = -1
            while (true) {
                val elapsed = mark.elapsedNow()
                advance(elapsed - consumed)
                consumed = elapsed

                val index = currentFrameIndex
                if (index != emittedIndex) {
                    emittedIndex = index
                    emit(frameFor(index))
                }
                delay(tickInterval)
            }
        }
    }

    private fun resolveSegment(name: String): TraceSegment =
        trace.segments.firstOrNull { it.name == name }
            ?: throw TracePlaybackError.UnknownSegment(name, segmentNames)

    private fun wholeTraceSegment(): TraceSegment =
        TraceSegment(
            name = WHOLE_TRACE_SEGMENT,
            startFrame = 0,
            endFrame = trace.frameCount - 1,
            loop = false,
        )

    private fun indexAtNanos(nanos: Long): Int {
        val raw = if (nanos <= 0L) 0L else nanos / frameNanos
        val span = segment.frameCount.toLong()
        val within = if (segment.loop) raw % span else raw.coerceAtMost(span - 1L)
        return segment.startFrame + within.toInt()
    }

    private fun frameFor(index: Int): VoxelFrame {
        cachedFrame?.let { held -> if (index == cachedIndex) return held }
        val frame = reconstruct(index)
        cachedIndex = index
        cachedFrame = frame
        return frame
    }

    // Slice one frame out of the columnar channel arrays. This is the only place
    // in playback that allocates, and it runs once per recorded frame rather
    // than once per display refresh.
    private fun reconstruct(index: Int): VoxelFrame {
        val base = cellOffsets[index]
        val count = trace.cellCounts[index]
        val cells = ArrayList<VoxelCell>(count)
        for (offset in 0 until count) {
            val cell = base + offset
            val alpha = trace.cellAlpha[cell]
            cells +=
                VoxelCell(
                    x = trace.cellX[cell],
                    y = trace.cellY[cell],
                    z = trace.cellZ[cell],
                    scale = trace.cellScale[cell],
                    red = trace.cellRed[cell],
                    green = trace.cellGreen[cell],
                    blue = trace.cellBlue[cell],
                    alpha = if (alpha.isNaN()) null else alpha,
                )
        }
        return VoxelFrame(
            tick = trace.ticks[index],
            timestampEpochMillis = trace.timestampsEpochMillis[index],
            resolution = trace.staticLattice.resolution,
            cells = cells,
            ambient = trace.ambient[index],
            glyph = trace.glyphs[index],
        )
    }

    companion object {
        /**
         * Name of the implicit segment covering the whole recording, used when a
         * player is constructed without one. Bracketed so it cannot collide with
         * the recorded convention (`"idle"`, `"thinking"`, `"idle→thinking"`).
         */
        const val WHOLE_TRACE_SEGMENT: String = "<trace>"
    }
}
