package link.socket.phosphor.lumos

import kotlinx.serialization.Serializable

/**
 * The idiom the orb is wearing: the way its cells move for a cognitive moment,
 * as distinct from the atmosphere that colours them.
 *
 * Thinking Orbs' catalogue, one entry per agent state. Each look is a per-cell
 * modulation of radius, opacity and position computed in view space from a
 * clock every orb of that look shares; see [link.socket.phosphor.lumos.look.LookModulator].
 * [LumosForm.sample] takes a look so a form can lay itself out differently per
 * idiom. The first four idioms are ported (PHO-58), and retrying (PHO-59); the
 * rest wear [BASE]'s spin until PHO-59 ports them, and every variant draws as
 * `"default"` until then.
 *
 * @property variants The names a [Look] may carry for this look, `"default"` first.
 */
enum class LumosLook(val variants: List<String>) {
    /** The resting idiom: the atmosphere's own spin, with an optional rim fade. */
    BASE(listOf("default")),

    /** A ring of light runs down the sphere every 1.7 s. */
    WORKING(listOf("default", "gyro")),

    /** A lit walk hops between neighbouring cells, steered toward the viewer. */
    REASONING(listOf("default", "twins")),

    /** A lens hops between spots on the front of the sphere, magnifying what it covers. */
    SEARCHING(listOf("default", "lighthouse")),

    /** Not yet ported; draws as [BASE]. */
    BACKGROUND(listOf("default", "spiral")),

    /**
     * The orb turns, brakes, springs back 60 % of the turn and tries again, a
     * cycle every 1.6 s. The one look that owns the yaw: the atmosphere's
     * rotation is not read while it is worn.
     */
    RETRYING(listOf("default", "surge")),

    /** Not yet ported; draws as [BASE]. */
    COMPACTING(listOf("default", "squeeze", "fuse")),

    /** Not yet ported; draws as [BASE]. */
    WAITING(listOf("default")),
}

/**
 * A [LumosLook] with one of its variants: what a [VoxelFrameBuilder] wears.
 *
 * Serialises as `{"look":"WORKING","variant":"gyro"}`, the variant left out when it is
 * the default, so a `LumosSequence` step can name one.
 *
 * @property look The idiom.
 * @property variant One of [LumosLook.variants]; `"default"` unless asked otherwise.
 * @throws IllegalArgumentException when [variant] is not one the look offers.
 */
@Serializable
data class Look(
    val look: LumosLook,
    val variant: String = "default",
) {
    init {
        require(variant in look.variants) {
            "$look has no variant \"$variant\"; it offers ${look.variants}"
        }
    }

    /**
     * The look's name for a clock key: `"working"` for the default variant,
     * `"working-gyro"` for another, lower case.
     */
    val key: String
        get() = if (variant == DEFAULT_VARIANT) look.name.lowercase() else "${look.name.lowercase()}-$variant"

    /**
     * The shortest time after which the look's clock-driven timing repeats, in
     * seconds: the ring's 1.7 s, the lens's 1.8 s hop, the walk's 220 ms hop,
     * the retry's 1.6 s cycle. 0 for a look with nothing clock-driven, which
     * [LumosLook.BASE] is: its spin is the atmosphere's. A loop capture of a look
     * wants a duration that is a multiple of this. The lens's spot sequence and
     * the walk's path do not repeat on it; only their timing does. Nor does the
     * retry's yaw, which gains the same angle every cycle.
     */
    val periodSeconds: Float
        get() =
            when (look) {
                LumosLook.WORKING -> WORKING_PERIOD_SECONDS
                LumosLook.SEARCHING -> SEARCHING_PERIOD_SECONDS
                LumosLook.REASONING -> REASONING_PERIOD_SECONDS
                LumosLook.RETRYING -> RETRYING_PERIOD_SECONDS
                else -> 0f
            }

    companion object {
        /** The variant every look offers. */
        const val DEFAULT_VARIANT: String = "default"

        /** The working ring's period: one ring every 1.7 s. */
        const val WORKING_PERIOD_SECONDS: Float = 1.7f

        /** The searching lens's hop: a new spot every 1.8 s. */
        const val SEARCHING_PERIOD_SECONDS: Float = 1.8f

        /** The reasoning walk's hop: a new cell every 220 ms. */
        const val REASONING_PERIOD_SECONDS: Float = 0.22f

        /** The retry's cycle: Thinking Orbs' 3.2 s `rewind` at double time. */
        const val RETRYING_PERIOD_SECONDS: Float = 1.6f
    }
}
