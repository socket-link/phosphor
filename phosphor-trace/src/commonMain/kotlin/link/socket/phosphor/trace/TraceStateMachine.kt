package link.socket.phosphor.trace

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import link.socket.phosphor.lumos.VoxelFrame

/**
 * What a [TraceStateMachine] does when the recording carries no `"from→to"`
 * segment for a requested state change.
 *
 * A recorded transition always wins — it is the authored motion, baked with its
 * own easing — so a policy is the fallback for a pair nobody recorded, or one an
 * asset budget cut.
 */
sealed interface TransitionPolicy {
    /**
     * Let the outgoing loop finish its iteration, then switch at the seam.
     *
     * Costs nothing and pops nothing: a loop is recorded to wrap, so its seam is
     * already visually continuous, and the switch lands on exactly that frame.
     * The price is latency — up to one full loop iteration before the state
     * change is visible.
     */
    data object FinishLoopThenSwitch : TransitionPolicy

    /**
     * Run both segments at once for [durationMs], ramping a linear weight from
     * the outgoing frame to the incoming one.
     *
     * Switches immediately at the cost of a blend per recorded frame inside the
     * window. See [FrameBlend] for where the blend happens and why.
     *
     * @property durationMs Length of the fade in milliseconds. Must be `> 0`.
     */
    data class Crossfade(
        val durationMs: Long,
    ) : TransitionPolicy {
        init {
            require(durationMs > 0L) { "Crossfade durationMs must be > 0, was $durationMs" }
        }
    }
}

/**
 * Drives a recorded [VoxelTrace] through state changes without a visible pop.
 *
 * Traces are linear; cognition is not. A clip cannot anticipate the moment a
 * state changes, so this machine bridges the two: named states map to
 * [TraceSegment]s, and a transition policy decides how the playhead moves
 * between them.
 *
 * **Deliberately generic.** It maps strings to segments and knows nothing about
 * what the strings mean. Binding a live signal's states to segment names belongs
 * to whoever owns the signal; everything emitting `VoxelFrame`s gets
 * state-driven playback from the same type.
 *
 * ### Transition resolution, checked at request time
 *
 * 1. A segment named `"$current→$target"` in the recording wins outright. It
 *    plays once, immediately — no loop boundary is waited for — and the target's
 *    loop is entered the instant it ends.
 * 2. Otherwise the configured [TransitionPolicy] decides:
 *    [TransitionPolicy.FinishLoopThenSwitch] waits out the current iteration,
 *    [TransitionPolicy.Crossfade] runs two heads and blends.
 *
 * With a full set of authored transitions recorded, path 1 covers every pair and
 * the policy never fires.
 *
 * ### Easing is already in the frames
 *
 * `AtmosphereChoreographer` applies the transition curve at capture time, so a
 * recorded `"idle→ready"` already overshoots. This machine selects and sequences
 * segments and applies no curve of its own; the crossfade weight is linear for
 * the same reason.
 *
 * ### Time
 *
 * The machine owns the clock. It drives its heads through
 * [TracePlayer.frameAt], never through their own playheads, so there is one
 * source of truth for where playback is. Drive it with [advance] from a render
 * loop or collect [frames]; given a deterministic clock, the frame sequence is
 * deterministic, which is what makes a replay auditable.
 *
 * Stateful and single-threaded, like the players it owns: one machine per
 * stream, driven from one coroutine.
 *
 * @param trace The recording to drive. Must carry the segment named by [initialState].
 * @param initialState Name of the segment to rest on at construction.
 * @param policy Fallback for state changes the recording has no transition segment for.
 * @throws TracePlaybackError.UnknownSegment when [initialState] names a segment
 *  the trace does not carry. Construction throws where [requestState] returns a
 *  failure, because an initial state is written by the caller while a request
 *  arrives from outside.
 */
class TraceStateMachine(
    private val trace: VoxelTrace,
    initialState: String,
    private val policy: TransitionPolicy = TransitionPolicy.FinishLoopThenSwitch,
) {
    /** Every name [requestState] accepts, in declaration order. */
    val stateNames: List<String> = trace.segments.map(TraceSegment::name)

    // Two heads over one recording. Both exist for the machine's lifetime and
    // swap roles when a crossfade lands, so a fade allocates no player and
    // TracePlayer keeps its "one segment at a time" invariant intact.
    private var primary: TracePlayer = TracePlayer(trace, initialState)
    private var secondary: TracePlayer = TracePlayer(trace, initialState)

    /** Wall-clock duration of one recorded frame, `1 / fps`. */
    val frameDuration: Duration = primary.frameDuration

    private val frameNanos: Long = frameDuration.inWholeNanoseconds

    private var primaryNanos: Long = 0L
    private var secondaryNanos: Long = 0L
    private var fadeNanos: Long = 0L

    private var phase: Phase = Phase.Settled(initialState)
    private var pendingTarget: String? = null

    // Blend cache, keyed on both head indices and the quantized weight step —
    // the three things a blended frame is a function of. Holding it means a
    // display refreshing faster than the recording reuses the identical
    // instance, exactly as TracePlayer does for a recorded frame.
    private var cachedOutgoingIndex: Int = -1
    private var cachedIncomingIndex: Int = -1
    private var cachedStep: Long = -1L
    private var cachedBlend: VoxelFrame? = null

    private val mutableState: MutableStateFlow<String> = MutableStateFlow(initialState)
    private val mutableRequestedState: MutableStateFlow<String> = MutableStateFlow(initialState)

    /**
     * The state the orb is actually in: the segment whose loop is playing.
     *
     * Flips when a target's loop is entered, not when it is asked for, so an
     * observer reading `"idle"` means idle is on screen right now. Pair it with
     * [requestedState] to tell "arriving" from "arrived".
     */
    val state: StateFlow<String> = mutableState.asStateFlow()

    /**
     * The state the machine is heading toward: the last accepted request, or
     * [state] when it has settled there.
     */
    val requestedState: StateFlow<String> = mutableRequestedState.asStateFlow()

    /** Whether a transition is in flight. */
    val isTransitioning: Boolean get() = phase !is Phase.Settled

    /** Segment the outgoing head rests on. */
    val activeSegment: TraceSegment get() = primary.activeSegment

    /** Segment the incoming head rests on during a crossfade, else null. */
    val incomingSegment: TraceSegment? get() = if (phase is Phase.Fade) secondary.activeSegment else null

    /** Absolute trace index the outgoing head rests on. */
    val currentFrameIndex: Int get() = primary.frameIndexAt(primaryNanos.nanoseconds)

    /** Absolute trace index the incoming head rests on during a crossfade, else null. */
    val incomingFrameIndex: Int?
        get() = if (phase is Phase.Fade) secondary.frameIndexAt(secondaryNanos.nanoseconds) else null

    /**
     * The two heads and the weight between them while a crossfade runs, else null.
     *
     * [currentFrame] already collapses this into a single frame. Read it
     * directly to blend somewhere cheaper — a shader, say — and ignore
     * [currentFrame] while it is non-null.
     */
    val blend: FrameBlend?
        get() {
            val fade = phase as? Phase.Fade ?: return null
            return FrameBlend(
                outgoing = primary.frameAt(primaryNanos.nanoseconds),
                incoming = secondary.frameAt(secondaryNanos.nanoseconds),
                weight = weightFor(fade),
            )
        }

    /** The frame the machine currently shows, blended if a crossfade is running. */
    val currentFrame: VoxelFrame
        get() =
            when (phase) {
                is Phase.Fade -> blendedFrame()
                else -> primary.frameAt(primaryNanos.nanoseconds)
            }

    /**
     * Ask for the state named [name].
     *
     * Resolution happens here, in the order documented on the class. Re-entrant
     * requests collapse: asking for the state already being headed toward is a
     * no-op, and asking for the current state while waiting on a loop boundary
     * cancels the wait. A request arriving mid-transition is held and resolved
     * from wherever the machine lands, so a rapid `a → b → a` plays both
     * recorded one-shots and settles on `a` rather than cutting the first one
     * short.
     *
     * @return [Result.success] when the request was accepted or collapsed;
     *  [Result.failure] carrying [TracePlaybackError.UnknownSegment] when the
     *  trace has no segment by that name. A failed request changes nothing and
     *  playback continues — which is the point of returning rather than
     *  throwing, since state names cross into this type from outside.
     */
    fun requestState(name: String): Result<Unit> {
        if (stateNames.none { it == name }) {
            return Result.failure(TracePlaybackError.UnknownSegment(name, stateNames))
        }
        if (name == mutableRequestedState.value) return Result.success(Unit)

        when (val current = phase) {
            is Phase.Settled -> resolve(current.target, name)
            is Phase.AwaitLoop ->
                // Nothing has started yet, so this is free: either abandon the
                // pending switch or resolve the new one from scratch.
                if (name == current.from) cancelWait(current.from) else resolve(current.from, name)
            is Phase.OneShot, is Phase.Fade -> {
                // Cutting a recorded transition mid-flight is the pop this type
                // exists to prevent. Land first, resolve from there.
                pendingTarget = name
                mutableRequestedState.value = name
            }
        }
        return Result.success(Unit)
    }

    /**
     * Advance the clock by [delta] and return the frame the machine now shows.
     *
     * Time is consumed up to the next phase boundary at a time, so a delta that
     * steps over the end of a transition carries its remainder into whatever
     * comes next. Playback is therefore a function of total elapsed time and not
     * of how finely the caller ticks.
     */
    fun advance(delta: Duration): VoxelFrame {
        require(delta >= Duration.ZERO) { "delta must be >= 0, was $delta" }
        var remaining = delta.inWholeNanoseconds
        while (remaining > 0L) {
            // Floored at zero so time can never run backwards on a boundary the
            // playhead is already sitting on; crossing it moves the phase on, so
            // the next pass always has room.
            val boundary = nanosUntilBoundary().coerceAtLeast(0L)
            if (boundary > remaining) {
                consume(remaining)
                remaining = 0L
            } else {
                consume(boundary)
                remaining -= boundary
                crossBoundary()
            }
        }
        return currentFrame
    }

    /**
     * Emit the machine's output as a frame stream paced by [timeSource].
     *
     * Emits only when the frame changes. Both a held recorded frame and a held
     * blend are the identical instance across refreshes, so the comparison is a
     * reference check and a frame that holds costs nothing.
     *
     * @param timeSource Clock the machine reads. Pass a test time source to run
     *  a sequence of state changes in virtual time.
     * @param tickInterval How often to re-resolve. Defaults to one recorded
     *  frame; shorten it when [requestState] is called from another coroutine
     *  and should land faster than a frame.
     */
    fun frames(
        timeSource: TimeSource = TimeSource.Monotonic,
        tickInterval: Duration = frameDuration,
    ): Flow<VoxelFrame> {
        require(tickInterval > Duration.ZERO) { "tickInterval must be > 0, was $tickInterval" }
        return flow {
            val mark = timeSource.markNow()
            var consumed = Duration.ZERO
            var emitted: VoxelFrame? = null
            while (true) {
                val elapsed = mark.elapsedNow()
                val frame = advance(elapsed - consumed)
                consumed = elapsed
                if (frame !== emitted) {
                    emitted = frame
                    emit(frame)
                }
                delay(tickInterval)
            }
        }
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    private fun resolve(
        from: String,
        target: String,
    ) {
        mutableRequestedState.value = target

        val oneShot = trace.segments.firstOrNull { it.name == transitionName(from, target) }
        if (oneShot != null) {
            primary.setSegment(oneShot.name)
            primaryNanos = 0L
            phase = Phase.OneShot(target, oneShot.frameCount.toLong() * frameNanos)
            return
        }

        when (policy) {
            is TransitionPolicy.FinishLoopThenSwitch ->
                if (nanosUntilLoopBoundary() <= 0L) {
                    enterLoop(target)
                } else {
                    phase = Phase.AwaitLoop(from, target)
                }
            is TransitionPolicy.Crossfade -> beginFade(target, policy.durationMs)
        }
    }

    private fun cancelWait(settledState: String) {
        // The outgoing loop never stopped playing, so there is nothing to rewind.
        phase = Phase.Settled(settledState)
        mutableRequestedState.value = settledState
    }

    private fun beginFade(
        target: String,
        durationMs: Long,
    ) {
        secondary.setSegment(target)
        secondaryNanos = 0L
        fadeNanos = 0L
        invalidateBlend()
        // primaryNanos deliberately keeps running: the outgoing loop carries on
        // from where it was, which is half of what makes the fade read smoothly.
        phase = Phase.Fade(target, durationMs * NANOS_PER_MILLI)
    }

    private fun enterLoop(target: String) {
        primary.setSegment(target)
        primaryNanos = 0L
        settle(target)
    }

    private fun promoteIncoming(target: String) {
        val retired = primary
        primary = secondary
        secondary = retired
        primaryNanos = secondaryNanos
        secondaryNanos = 0L
        fadeNanos = 0L
        invalidateBlend()
        settle(target)
    }

    private fun settle(settledState: String) {
        phase = Phase.Settled(settledState)
        mutableState.value = settledState

        val pending = pendingTarget
        pendingTarget = null
        if (pending != null && pending != settledState) {
            resolve(settledState, pending)
        } else {
            mutableRequestedState.value = settledState
        }
    }

    // ------------------------------------------------------------------
    // Clock
    // ------------------------------------------------------------------

    private fun consume(nanos: Long) {
        primaryNanos += nanos
        if (phase is Phase.Fade) {
            secondaryNanos += nanos
            fadeNanos += nanos
        }
    }

    private fun nanosUntilBoundary(): Long =
        when (val current = phase) {
            is Phase.Settled -> Long.MAX_VALUE
            is Phase.OneShot -> current.spanNanos - primaryNanos
            is Phase.AwaitLoop -> nanosUntilLoopBoundary()
            is Phase.Fade -> current.durationNanos - fadeNanos
        }

    private fun crossBoundary() {
        when (val current = phase) {
            is Phase.Settled -> Unit
            is Phase.OneShot -> enterLoop(current.target)
            is Phase.AwaitLoop -> enterLoop(current.target)
            is Phase.Fade -> promoteIncoming(current.target)
        }
    }

    /**
     * Nanoseconds until the outgoing segment's current iteration closes.
     *
     * Always `> 0` for a looping segment — landing exactly on a seam means a
     * whole iteration remains, because that is what "finish the current
     * iteration" means at its first frame. A non-looping segment has no seam to
     * wait for and returns `<= 0` once it has played out, which switches at once.
     */
    private fun nanosUntilLoopBoundary(): Long {
        val segment = primary.activeSegment
        val span = segment.frameCount.toLong() * frameNanos
        return if (segment.loop) span - (primaryNanos % span) else span - primaryNanos
    }

    // ------------------------------------------------------------------
    // Blending
    // ------------------------------------------------------------------

    /**
     * The crossfade weight, quantized onto the recording's own frame grid.
     *
     * Reading the weight off raw elapsed time would make the fade depend on how
     * finely the caller ticks — a 120 Hz consumer would see four times as many
     * distinct weights as a 30 fps one, and two runs of the same request
     * sequence would not match. Stepping it per recorded frame makes the fade a
     * function of the recording, keeps the blended frame cacheable, and is what
     * lets a playback log be compared across runs.
     */
    private fun weightFor(fade: Phase.Fade): Float {
        val steps = fadeSteps(fade)
        return fadeStep(fade, steps).toFloat() / steps.toFloat()
    }

    private fun fadeSteps(fade: Phase.Fade): Long = (fade.durationNanos / frameNanos).coerceAtLeast(1L)

    private fun fadeStep(
        fade: Phase.Fade,
        steps: Long,
    ): Long = (fadeNanos / frameNanos).coerceIn(0L, steps)

    private fun blendedFrame(): VoxelFrame {
        val fade = phase as Phase.Fade
        val outgoingIndex = primary.frameIndexAt(primaryNanos.nanoseconds)
        val incomingIndex = secondary.frameIndexAt(secondaryNanos.nanoseconds)
        val steps = fadeSteps(fade)
        val step = fadeStep(fade, steps)

        cachedBlend?.let { held ->
            if (outgoingIndex == cachedOutgoingIndex &&
                incomingIndex == cachedIncomingIndex &&
                step == cachedStep
            ) {
                return held
            }
        }

        val blended =
            FrameBlend(
                outgoing = primary.frameAt(primaryNanos.nanoseconds),
                incoming = secondary.frameAt(secondaryNanos.nanoseconds),
                weight = step.toFloat() / steps.toFloat(),
            ).transduce()

        cachedOutgoingIndex = outgoingIndex
        cachedIncomingIndex = incomingIndex
        cachedStep = step
        cachedBlend = blended
        return blended
    }

    private fun invalidateBlend() {
        cachedOutgoingIndex = -1
        cachedIncomingIndex = -1
        cachedStep = -1L
        cachedBlend = null
    }

    /**
     * Where the playhead is between request and arrival.
     *
     * [Settled] is the resting shape; the other three are the three ways a state
     * change can be in flight. Each carries the target it will land on, so
     * arrival needs no second lookup.
     */
    private sealed interface Phase {
        val target: String

        /** Looping [target]'s segment. */
        data class Settled(
            override val target: String,
        ) : Phase

        /** Playing a recorded `"from→target"` segment once, [spanNanos] long. */
        data class OneShot(
            override val target: String,
            val spanNanos: Long,
        ) : Phase

        /** Still looping [from], waiting for its iteration to close before switching to [target]. */
        data class AwaitLoop(
            val from: String,
            override val target: String,
        ) : Phase

        /** Both heads running, [durationNanos] of linear weight from the outgoing frame to [target]'s. */
        data class Fade(
            override val target: String,
            val durationNanos: Long,
        ) : Phase
    }

    companion object {
        /** Separator in a recorded transition segment's name, as in `"idle→thinking"`. */
        const val TRANSITION_ARROW: String = "→"

        private const val NANOS_PER_MILLI: Long = 1_000_000L

        /** Name a recorded one-shot transition from [from] to [to] would carry. */
        fun transitionName(
            from: String,
            to: String,
        ): String = "$from$TRANSITION_ARROW$to"
    }
}
