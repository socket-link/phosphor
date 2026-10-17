package link.socket.phosphor.lumos.look

import kotlin.math.floor
import kotlin.math.sin
import link.socket.phosphor.lumos.FormSample
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook

/**
 * The catalogue of [LookModulator]s: Thinking Orbs' idioms, ported.
 *
 * Four are ported here (PHO-58): [LumosLook.BASE]'s rim fade, the working ring,
 * the searching lens and the reasoning walk; PHO-59 adds the retry's rewind. The
 * other looks, and every variant, draw as the base spin until PHO-59 ports them.
 */
object Looks {
    /**
     * The modulator for [look] over [sample], with whatever tables it needs
     * built now: the reasoning walk's 24-nearest table is an `O(N²)` pass over
     * the sample, about 23 million distance checks at 4,801 cells. Build once per
     * `(look, sample)`, which `VoxelFrameBuilder` does, and never per frame.
     */
    fun forLook(
        look: Look,
        sample: FormSample,
    ): LookModulator =
        when (look.look) {
            LumosLook.BASE -> BaseSpin(radiusRamp = true)
            LumosLook.WORKING -> WorkingRing()
            LumosLook.SEARCHING -> SearchingLens()
            LumosLook.REASONING -> ReasoningWalk(sample, NearestTable(sample.positions, ReasoningWalk.REACH))
            LumosLook.RETRYING -> RetryingRewind()
            LumosLook.BACKGROUND,
            LumosLook.COMPACTING,
            LumosLook.WAITING,
            -> BaseSpin(radiusRamp = true)
        }

    /**
     * Thinking Orbs' hash, as written: `fract(sin(n · 127.1) · 43758.5453)`, in
     * `[0, 1)`. The only randomness any look uses, so every orb of a look hops
     * and walks alike. Worked in doubles, as the source's JavaScript does.
     */
    fun hash(n: Float): Float {
        val value = sin(n.toDouble() * HASH_FREQUENCY) * HASH_GAIN
        return (value - floor(value)).toFloat()
    }

    /** Hermite ease of [t] clamped to `0..1`. */
    internal fun smoothstep(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    private const val HASH_FREQUENCY: Double = 127.1
    private const val HASH_GAIN: Double = 43758.5453
}
