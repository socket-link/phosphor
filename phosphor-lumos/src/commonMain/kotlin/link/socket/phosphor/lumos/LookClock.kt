package link.socket.phosphor.lumos

import kotlin.math.min

/**
 * One clock per look, so every orb wearing that look keeps the same time.
 *
 * Orbs of one look at one speed share a [LookClock.key] and read one time from it, in seconds.
 * An orb that joins a key late, or comes back after scrolling out of view, reads the time its
 * peers are at and takes their phase ([LumosFrameSource.align]), so a column of orbs in the same
 * state breathes together however long ago each one was first drawn.
 *
 * A key's time moves by the wall time since it last moved, capped at
 * [LookClock.MAX_GAP_SECONDS] and scaled by speed. A key nobody read for an hour resumes a tenth
 * of a second on, not an hour on, so an orb in a backgrounded window carries on where it was
 * rather than jumping. Ticking a key twice for one instant moves it once, however many orbs ask,
 * and its time never runs backwards.
 *
 * Framework-free and single-threaded: tick it from the thread that draws.
 */
class LookClock {
    private val keys = HashMap<String, KeyTime>()

    /**
     * [key]'s time in seconds, once it has caught up to [nowSeconds].
     *
     * @param nowSeconds The caller's clock, such as a frame time. Every caller sharing this
     *  instance must read the same monotonic clock; in Compose, every frame clock does.
     * @param speed How fast [key]'s time runs against the wall.
     */
    fun tick(
        key: String,
        nowSeconds: Double,
        speed: Float,
    ): Double {
        val time = keys.getOrPut(key) { KeyTime(lastNowSeconds = nowSeconds) }
        val gap = nowSeconds - time.lastNowSeconds
        if (gap > 0.0) {
            time.seconds += min(gap, MAX_GAP_SECONDS) * speed
            time.lastNowSeconds = nowSeconds
        }
        return time.seconds
    }

    /** [key]'s time as last ticked, or null when nothing has ticked it. Moves nothing. */
    fun timeOf(key: String): Double? = keys[key]?.seconds

    private class KeyTime(
        var lastNowSeconds: Double,
    ) {
        var seconds: Double = 0.0
    }

    companion object {
        /** The most wall time one tick moves a key by: a tenth of a second, Thinking Orbs' rule. */
        const val MAX_GAP_SECONDS: Double = 0.1

        /**
         * The key for [look] at [speed]. Orbs at different speeds cannot hold one phase, so they
         * never share a key.
         */
        fun key(
            look: String,
            speed: Float,
        ): String = "$look@$speed"
    }
}
