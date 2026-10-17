package link.socket.phosphor.lumos

import kotlin.math.min

/**
 * A clock an orb's driver moves with its frames: `OrbStepper` calls [tick] with every step it
 * hands the source. [LumosSequencePlayer] is one; the interface is what the stepper names, so a
 * bundle that steps orbs without a sequence carries no player.
 */
fun interface SequenceClock {
    /** [dt] seconds have passed for the orb. */
    fun tick(dt: Float)
}

/**
 * The clock of a [LumosSequence], with no framework in it: whoever owns the loop advances it, and
 * everyone showing the sequence reads [snapshot].
 *
 * Time moves only in [advance], and only while the player is *ticking*: at least one consumer is
 * connected ([connect]), nobody has taken control ([control]), and it is [playing]. A driver —
 * `OrbStepper`, `SignalGenerator.frames`, `Oscilloscope`'s fixed step — calls [advance] with the
 * step it hands the orb; each call is capped at [MAX_STEP_SECONDS], the same cap `OrbStepper`
 * puts on a frame gap, so a return from the background carries on rather than jumping.
 *
 * [control] is for a scroll position or any external driver that wants to own time: while a
 * controller holds, [advance] moves nothing and [seek] is how time moves, and the renderer keeps
 * drawing whatever time it is set to. [connect] lets several consumers share one clock without
 * any of them owning it; the last one to release stops the clock.
 *
 * Stateful and single-threaded, like the sources that read it.
 *
 * @property sequence What is played.
 * @param autoplay Whether the player starts [playing]; true by default.
 */
class LumosSequencePlayer(
    val sequence: LumosSequence,
    autoplay: Boolean = true,
) : SequenceClock {
    /** Sequence time, in seconds from the start of the first pass. */
    var timeSeconds: Float = 0f
        private set

    /** Whether time moves when the gate allows; see [play] and [pause]. */
    var playing: Boolean = autoplay
        private set

    /** How many consumers hold a [connect] handle. */
    var connections: Int = 0
        private set

    /** How many external drivers hold a [control] handle. */
    var controllers: Int = 0
        private set

    /** Whether [advance] moves time right now: connected, uncontrolled and playing. */
    val ticking: Boolean get() = connections > 0 && controllers == 0 && playing

    /** Where the sequence is at [timeSeconds]; `sequence.sample(timeSeconds)`. */
    val snapshot: LumosSequenceSnapshot get() = sequence.sample(timeSeconds)

    /** Let time move. A finite sequence that has ended restarts; see [restart]. */
    fun play() {
        if (!sequence.loop && snapshot.ended) timeSeconds = 0f
        playing = true
    }

    /** Hold time where it is. */
    fun pause() {
        playing = false
    }

    /**
     * Move to [seconds] from the start. Negative is the start; past the end a looping sequence
     * wraps and a finite one ends. Allowed whoever owns the clock: this is how a controller
     * drives it.
     */
    fun seek(seconds: Float) {
        timeSeconds = if (seconds.isNaN()) 0f else seconds.coerceAtLeast(0f)
    }

    /** Back to the start, playing. */
    fun restart() {
        timeSeconds = 0f
        playing = true
    }

    /**
     * Take ownership of time. Until the returned release is called, [advance] moves nothing and
     * [seek] is the clock. Releasing twice does nothing.
     */
    fun control(): () -> Unit {
        controllers++
        return once { controllers-- }
    }

    /**
     * Join the consumers sharing this clock. Time moves only while at least one holds a handle.
     * Releasing twice does nothing.
     */
    fun connect(): () -> Unit {
        connections++
        return once { connections-- }
    }

    /**
     * Move time forward by [dt] seconds, capped at [MAX_STEP_SECONDS], if [ticking]. A finite
     * sequence that reaches its end pauses there.
     *
     * @return The snapshot after the step, whether or not time moved.
     */
    fun advance(dt: Float): LumosSequenceSnapshot {
        if (ticking && dt > 0f && !dt.isNaN()) {
            timeSeconds += min(dt, MAX_STEP_SECONDS)
            if (!sequence.loop && timeSeconds >= sequence.durationSeconds) {
                timeSeconds = sequence.durationSeconds
                playing = false
            }
        }
        return snapshot
    }

    /** [advance], as a driver's [SequenceClock]. */
    override fun tick(dt: Float) {
        advance(dt)
    }

    private inline fun once(crossinline release: () -> Unit): () -> Unit {
        var released = false
        return {
            if (!released) {
                released = true
                release()
            }
        }
    }

    companion object {
        /** The most one [advance] moves time, in seconds: `LookClock.MAX_GAP_SECONDS`. */
        const val MAX_STEP_SECONDS: Float = 0.1f
    }
}
