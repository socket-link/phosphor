package link.socket.phosphor.trace

import kotlin.time.Duration.Companion.seconds
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.VoxelFrame

/**
 * This source as a [LumosFrameSource], for a driver such as `LumosOrb` that owns
 * the clock: [LumosSource.Live] through [SignalGenerator.asFrameSource], and
 * [LumosSource.Trace] through its player's `asFrameSource`, carrying rotation
 * as its [carryRotation][LumosSource.Trace.carryRotation] says.
 *
 * The declared source is the same one-line swap it always was; only who paces
 * it changes. `frames()` paces itself against a clock it reads, while a frame
 * source is stepped by a driver that decides how much time has passed.
 */
fun LumosSource.asFrameSource(): LumosFrameSource =
    when (this) {
        is LumosSource.Live -> generator.asFrameSource()
        is LumosSource.Trace -> player.asFrameSource(carryRotation)
    }

/**
 * This player as a [LumosFrameSource], for a driver such as `LumosOrb` that
 * owns the clock.
 *
 * Each step advances the playhead by the driver's time and returns the frame
 * it rests on, with a looping segment's rotation carried across its seam unless
 * [carryRotation] is off — the carry a [LumosSource.Trace] stream applies, with
 * the same cuts: [TracePlayer.setSegment] and [TracePlayer.seek] restart
 * rotation from the recording. The player's own `play` and `pause` still apply.
 *
 * Reduced motion holds the frame the playhead rests on; a segment change or a
 * seek still lands, since both are cuts already. A paused driver redraws when
 * either moves the playhead. The look key is the segment's name. Orbs replaying
 * one segment do not yet share a phase: aligning a recording to a shared clock
 * is not implemented, so each keeps its own playhead.
 */
fun TracePlayer.asFrameSource(carryRotation: Boolean = true): LumosFrameSource =
    TracePlayerFrameSource(this, carryRotation)

/**
 * This machine as a [LumosFrameSource], for a driver such as `LumosOrb` that
 * owns the clock.
 *
 * Each step [advances][TraceStateMachine.advance] the machine by the driver's
 * time. Reduced motion lands any transition in flight at once — and any request
 * held behind it — then holds the target's frame, so a state change still
 * shows. A paused driver redraws when a new state is requested. The look key is
 * the state the machine is in or heading to, named as the presets are, so a
 * recorded orb and a live one in the same state share a clock; recorded orbs do
 * not yet take its phase.
 */
fun TraceStateMachine.asFrameSource(): LumosFrameSource = TraceStateMachineFrameSource(this)

private class TracePlayerFrameSource(
    private val player: TracePlayer,
    carryRotation: Boolean,
) : LumosFrameSource {
    private val carry = RotationCarry(player.trace, player.frameDuration.inWholeNanoseconds, carryRotation)
    private var entered: TraceSegment? = null

    private var seenSegment: TraceSegment? = null
    private var seenPlayheadNanos: Long = Long.MIN_VALUE
    private var changes: Long = 0L

    override fun next(dt: Float): VoxelFrame = carried(player.advance(dt.toDouble().seconds))

    override fun still(dt: Float): VoxelFrame = carried(player.currentFrame)

    override val revision: Long
        get() {
            val segment = player.activeSegment
            val playhead = player.playhead.inWholeNanoseconds
            if (segment != seenSegment || playhead != seenPlayheadNanos) {
                seenSegment = segment
                seenPlayheadNanos = playhead
                changes++
            }
            return changes
        }

    override val lookKey: String?
        get() = player.activeSegment.name.takeUnless { it == TracePlayer.WHOLE_TRACE_SEGMENT }

    // setSegment can land between steps; a cut restarts rotation from the recording.
    private fun carried(recorded: VoxelFrame): VoxelFrame {
        val segment = player.activeSegment
        if (segment != entered) {
            entered = segment
            carry.enter(segment, rotation = null)
        }
        return carry.apply(recorded, player.playhead.inWholeNanoseconds)
    }
}

private class TraceStateMachineFrameSource(
    private val machine: TraceStateMachine,
) : LumosFrameSource {
    private var seenRequest: String? = null
    private var changes: Long = 0L

    override fun next(dt: Float): VoxelFrame = machine.advance(dt.toDouble().seconds)

    override fun still(dt: Float): VoxelFrame {
        machine.land()
        return machine.currentFrame
    }

    override val revision: Long
        get() {
            val requested = machine.requestedState.value
            if (requested != seenRequest) {
                seenRequest = requested
                changes++
            }
            return changes
        }

    override val lookKey: String
        get() = machine.requestedState.value
}
