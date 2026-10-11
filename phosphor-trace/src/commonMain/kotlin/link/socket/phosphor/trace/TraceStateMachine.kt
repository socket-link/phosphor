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
     * Costs nothing at runtime, and the switch lands exactly on the seam — so it
     * pops no more than the seam does.
     *
     * **Precondition: the outgoing loop's content wraps continuously.** Capture
     * does not guarantee it by default. `Oscilloscope` records whatever the
     * simulation produced, and a loop whose phase accumulators do not complete
     * together jumps at its seam — at the switch, and on every ordinary
     * iteration as well. Capture with `CaptureConfig.seamBlendFrames` to bake the
     * seam out, and check the recording with [seamErrors]: a
     * [SeamError.contentRatio] near `1` wraps like one more frame, while a loop
     * cut untouched from a canonical preset at a few seconds reads in the tens.
     * Rotation is the machine's to keep continuous, not the recording's; see
     * the class documentation.
     *
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
 * How a [TraceStateMachine] starts a recorded `"from→to"` one-shot.
 *
 * A one-shot is continuous only with the frame recorded just before it. The
 * request, though, arrives wherever the outgoing loop's playhead happens to be,
 * and the loop's content — pattern, surface bump, breath pulse — has drifted
 * from that frame by up to a whole loop's worth. Entered as is from a 4 s loop
 * at 24 fps, the typical entry steps 29 to 45 times the loop's median frame step
 * in its worst cell channel, and 6 to 21 times an ordinary frame's pixel change
 * in a Compose raster. This chooses who pays for the difference.
 *
 * Waiting for the loop seam is deliberately not an option. A loop captured with
 * `CaptureConfig.seamBlendFrames` ends on the frame before its *own* start, not
 * on the frame before the one-shot, so its seam is no closer to the one-shot than
 * the rest of the loop: measured, it still jumps 22 to 73 steps. Only a loop
 * captured untouched, immediately before its one-shot, meets it at the seam, and
 * that loop pops at every wrap instead. Nor is there a better frame to wait for:
 * the closest frame of a baked loop is still ten or more steps away.
 */
sealed interface OneShotEntry {
    /**
     * Start the one-shot on its first recorded frame, the instant it is requested.
     *
     * Costs nothing and adds no latency. The entry jumps by however far the
     * loop's content has drifted from the frame the one-shot was recorded after.
     */
    data object Immediate : OneShotEntry

    /**
     * Start the one-shot at once, but fade into it from the outgoing loop over
     * its first [durationMs], the way [TransitionPolicy.Crossfade] fades
     * between two loops.
     *
     * The outgoing loop keeps playing underneath, the one-shot plays from its
     * first frame, and a linear weight moves from one to the other; the jump
     * the entry would have made is spread across the window instead of landing
     * on one frame. Its price is a [FrameBlend] per recorded frame inside the
     * window, every time a recorded transition starts — about 35 µs each on a
     * desktop JVM at 4,801 voxels, so 0.4 ms per switch at the default. The
     * one-shot's own length is not changed, so the machine still arrives when it
     * ends. A window longer than the one-shot is cut to the one-shot.
     *
     * Measured over every entry frame of 4 s baked loops, the default 500 ms
     * brings the worst step anywhere in the window to 4 to 7 median steps, and
     * the Compose raster's to 2.1 to 6.4 — in line with the one-shot's own
     * authored motion and the loop's own largest step. Two moving sides add up, so through the
     * window the frame can move up to about twice as fast as either side alone,
     * as a [TransitionPolicy.Crossfade] does.
     *
     * **Precondition: the outgoing loop wraps continuously** — capture it with
     * `CaptureConfig.seamBlendFrames`. The outgoing loop keeps playing through
     * the window, so an entry close to an unbaked seam blends that seam's jump
     * in too, at up to the weight the outgoing side still has.
     *
     * The blend pairs cells by index, so it needs every frame of the recording
     * to carry every voxel — a capture with `LumosRenderConfig.omitBelowScale > 0`
     * does not. On such a trace this enters immediately, as
     * [OneShotEntry.Immediate] does, rather than holding the loop for half the
     * window and then cutting.
     *
     * @property durationMs Length of the fade in milliseconds. Must be `> 0`.
     */
    data class BlendIn(
        val durationMs: Long,
    ) : OneShotEntry {
        init {
            require(durationMs > 0L) { "BlendIn durationMs must be > 0, was $durationMs" }
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
 *    loop is entered the instant it ends. [OneShotEntry] decides whether it
 *    starts on its first frame or fades in from the outgoing loop.
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
 * ### Rotation is carried, not replayed
 *
 * A recorded rotation only ever advances, so a loop shorter than a full turn
 * cannot wrap it: replayed as recorded, every iteration would drop the orb back
 * by the loop's whole travel. Cells are recorded unrotated and the angles ride
 * in `VoxelAmbient`, so the machine offsets them instead. Each loop iteration,
 * and each segment a head enters, starts where the last frame left off, one
 * ordinary step on. The offset touches three floats in the ambient state; the
 * cells are the recorded ones, and a frame whose offset is zero is the recorded
 * instance. Pass `carryRotation = false` to emit recorded rotations untouched.
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
 * @param carryRotation Whether to keep the orb's rotation continuous across loop seams and
 *  segment changes; see "Rotation is carried, not replayed" above. On by default.
 * @param oneShotEntry How a recorded transition starts from wherever the outgoing loop is. A
 *  500 ms [OneShotEntry.BlendIn] by default; pass [OneShotEntry.Immediate] for the
 *  first-frame entry, which costs nothing and jumps.
 * @throws TracePlaybackError.UnknownSegment when [initialState] names a segment
 *  the trace does not carry. Construction throws where [requestState] returns a
 *  failure, because an initial state is written by the caller while a request
 *  arrives from outside.
 */
class TraceStateMachine(
    private val trace: VoxelTrace,
    initialState: String,
    private val policy: TransitionPolicy = TransitionPolicy.FinishLoopThenSwitch,
    private val carryRotation: Boolean = true,
    private val oneShotEntry: OneShotEntry = OneShotEntry.BlendIn(DEFAULT_BLEND_IN_MS),
) {
    /** Every name [requestState] accepts, in declaration order. */
    val stateNames: List<String> = trace.segments.map(TraceSegment::name)

    // Whether cell i is the same voxel in every frame, which a blend-in needs.
    private val cellsAligned: Boolean = trace.cellCounts.all { it == trace.cellCounts.first() }

    // Two heads over one recording. Both exist for the machine's lifetime and
    // swap roles when a crossfade lands, so a fade allocates no player and
    // TracePlayer keeps its "one segment at a time" invariant intact.
    private var primary: TracePlayer = TracePlayer(trace, initialState)
    private var secondary: TracePlayer = TracePlayer(trace, initialState)

    /** Wall-clock duration of one recorded frame, `1 / fps`. */
    val frameDuration: Duration = primary.frameDuration

    private val frameNanos: Long = frameDuration.inWholeNanoseconds

    // One carry per head, swapped with the heads when a crossfade lands.
    private var primaryCarry: RotationCarry =
        RotationCarry(trace, frameNanos, carryRotation).also { it.enter(primary.activeSegment, null) }
    private var secondaryCarry: RotationCarry =
        RotationCarry(trace, frameNanos, carryRotation).also { it.enter(secondary.activeSegment, null) }

    private var primaryNanos: Long = 0L
    private var secondaryNanos: Long = 0L
    private var fadeNanos: Long = 0L

    private var phase: Phase = Phase.Settled(initialState)
    private var pendingTarget: String? = null

    // Blend cache, keyed on how many recorded frames each head has stepped and
    // the quantized weight step — the three things a blended frame is a
    // function of. A step count rather than a frame index, because a carried
    // rotation differs between two passes over the same index. Holding it means
    // a display refreshing faster than the recording reuses the identical
    // instance, exactly as TracePlayer does for a recorded frame.
    private var cachedOutgoingSteps: Long = -1L
    private var cachedIncomingSteps: Long = -1L
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

    /** Segment the incoming head rests on during a crossfade or a [OneShotEntry.BlendIn], else null. */
    val incomingSegment: TraceSegment? get() = if (phase is Phase.Fade) secondary.activeSegment else null

    /** Absolute trace index the outgoing head rests on. */
    val currentFrameIndex: Int get() = primary.frameIndexAt(primaryNanos.nanoseconds)

    /** Absolute trace index the incoming head rests on during a crossfade or a blend-in, else null. */
    val incomingFrameIndex: Int?
        get() = if (phase is Phase.Fade) secondary.frameIndexAt(secondaryNanos.nanoseconds) else null

    /**
     * The two heads and the weight between them while a crossfade or a blend-in runs, else null.
     *
     * [currentFrame] already collapses this into a single frame. Read it
     * directly to blend somewhere cheaper — a shader, say — and ignore
     * [currentFrame] while it is non-null.
     */
    val blend: FrameBlend?
        get() {
            val fade = phase as? Phase.Fade ?: return null
            return FrameBlend(
                outgoing = primaryFrame(),
                incoming = secondaryFrame(),
                weight = weightFor(fade),
            )
        }

    /** The frame the machine currently shows, blended while a crossfade or a blend-in runs. */
    val currentFrame: VoxelFrame
        get() =
            when (phase) {
                is Phase.Fade -> blendedFrame()
                else -> primaryFrame()
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
            val spanNanos = oneShot.frameCount.toLong() * frameNanos
            val entry = oneShotEntry
            if (entry is OneShotEntry.BlendIn && cellsAligned) {
                // Cut to the one-shot: the fade must land while it is still playing.
                val fadeNanos = minOf(entry.durationMs * NANOS_PER_MILLI, spanNanos)
                beginFade(oneShot.name, target, fadeNanos, oneShotSpanNanos = spanNanos)
                return
            }
            val rotation = handoffRotation()
            primary.setSegment(oneShot.name)
            primaryNanos = 0L
            primaryCarry.enter(primary.activeSegment, rotation)
            phase = Phase.OneShot(target, spanNanos)
            return
        }

        when (policy) {
            is TransitionPolicy.FinishLoopThenSwitch ->
                if (nanosUntilLoopBoundary() <= 0L) {
                    enterLoop(target)
                } else {
                    phase = Phase.AwaitLoop(from, target)
                }
            is TransitionPolicy.Crossfade -> beginFade(target, target, policy.durationMs * NANOS_PER_MILLI)
        }
    }

    private fun cancelWait(settledState: String) {
        // The outgoing loop never stopped playing, so there is nothing to rewind.
        phase = Phase.Settled(settledState)
        mutableRequestedState.value = settledState
    }

    /**
     * Start the incoming head on [segment] and fade to it over [durationNanos].
     *
     * [target] is the state the fade is heading for. It names [segment] itself
     * when the fade is between two loops; when the incoming segment is a
     * recorded one-shot, [oneShotSpanNanos] is its length and the fade hands
     * over to it rather than settling.
     */
    private fun beginFade(
        segment: String,
        target: String,
        durationNanos: Long,
        oneShotSpanNanos: Long? = null,
    ) {
        secondary.setSegment(segment)
        secondaryNanos = 0L
        // The incoming head starts at the angle the outgoing one is showing, so
        // the two blend from one orientation rather than across a turn.
        secondaryCarry.enter(secondary.activeSegment, handoffRotation())
        fadeNanos = 0L
        invalidateBlend()
        // primaryNanos deliberately keeps running: the outgoing loop carries on
        // from where it was, which is half of what makes the fade read smoothly.
        phase = Phase.Fade(target, durationNanos, oneShotSpanNanos)
    }

    private fun enterLoop(target: String) {
        val rotation = handoffRotation()
        primary.setSegment(target)
        primaryNanos = 0L
        primaryCarry.enter(primary.activeSegment, rotation)
        settle(target)
    }

    private fun promoteIncoming(fade: Phase.Fade) {
        val retired = primary
        primary = secondary
        secondary = retired
        val retiredCarry = primaryCarry
        primaryCarry = secondaryCarry
        secondaryCarry = retiredCarry
        primaryNanos = secondaryNanos
        secondaryNanos = 0L
        fadeNanos = 0L
        invalidateBlend()
        // A faded-into one-shot carries on from where the incoming head was;
        // the machine arrives when it ends, as if it had been entered directly.
        // A fade as long as the one-shot lands on its end, so arrive now rather
        // than holding its last frame until the next advance.
        val oneShotSpan = fade.oneShotSpanNanos
        when {
            oneShotSpan == null -> settle(fade.target)
            primaryNanos >= oneShotSpan -> enterLoop(fade.target)
            else -> phase = Phase.OneShot(fade.target, oneShotSpan)
        }
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
            is Phase.Fade -> promoteIncoming(current)
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
    // Rotation
    // ------------------------------------------------------------------

    private fun primaryFrame(): VoxelFrame = primaryCarry.apply(primary.frameAt(primaryNanos.nanoseconds), primaryNanos)

    private fun secondaryFrame(): VoxelFrame =
        secondaryCarry.apply(secondary.frameAt(secondaryNanos.nanoseconds), secondaryNanos)

    /**
     * The angle the outgoing head is at right now, for whichever head enters a
     * segment next to start from — or null, to start it as recorded, when
     * rotation is not being carried.
     *
     * Read on a boundary, this is already one step past the outgoing frame: a
     * loop's next iteration has begun, and a finished one-shot carries on at
     * its final rate. So the entering segment's first frame continues the
     * motion rather than repeating the last angle.
     */
    private fun handoffRotation(): FloatArray? =
        if (carryRotation) FloatArray(ROTATION_AXES).also { primaryCarry.rotationAt(primaryNanos, it) } else null

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
        val outgoingSteps = primaryNanos / frameNanos
        val incomingSteps = secondaryNanos / frameNanos
        val steps = fadeSteps(fade)
        val step = fadeStep(fade, steps)

        cachedBlend?.let { held ->
            if (outgoingSteps == cachedOutgoingSteps &&
                incomingSteps == cachedIncomingSteps &&
                step == cachedStep
            ) {
                return held
            }
        }

        val blended =
            FrameBlend(
                outgoing = primaryFrame(),
                incoming = secondaryFrame(),
                weight = step.toFloat() / steps.toFloat(),
            ).transduce()

        cachedOutgoingSteps = outgoingSteps
        cachedIncomingSteps = incomingSteps
        cachedStep = step
        cachedBlend = blended
        return blended
    }

    private fun invalidateBlend() {
        cachedOutgoingSteps = -1L
        cachedIncomingSteps = -1L
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

        /**
         * Both heads running, [durationNanos] of linear weight from the outgoing
         * frame to the incoming one. The incoming head plays [target]'s loop,
         * or — when [oneShotSpanNanos] is set — the recorded one-shot to
         * [target], [oneShotSpanNanos] long, which carries on once the fade lands.
         */
        data class Fade(
            override val target: String,
            val durationNanos: Long,
            val oneShotSpanNanos: Long? = null,
        ) : Phase
    }

    companion object {
        /** Separator in a recorded transition segment's name, as in `"idle→thinking"`. */
        const val TRANSITION_ARROW: String = "→"

        private const val NANOS_PER_MILLI: Long = 1_000_000L

        /** The default [OneShotEntry.BlendIn] length: half a second, measured in PHO-46. */
        private const val DEFAULT_BLEND_IN_MS: Long = 500L

        private const val ROTATION_AXES: Int = 3

        /** Name a recorded one-shot transition from [from] to [to] would carry. */
        fun transitionName(
            from: String,
            to: String,
        ): String = "$from$TRANSITION_ARROW$to"
    }
}
