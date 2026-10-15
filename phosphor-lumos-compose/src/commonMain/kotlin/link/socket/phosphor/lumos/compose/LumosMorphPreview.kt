package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import link.socket.phosphor.field.Choreography
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosForm
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.palette.AtmospherePresets

/**
 * Preview composable for the form morph (PHO-57): a strip of [MORPH_STRIP_ORBS] orbs, each the
 * same orb cycling lattice → Fibonacci → cube → lattice on [MorphingFrameSource], started
 * [MORPH_STRIP_STEP_SECONDS] further into the cycle than the one to its left. Read left to right
 * at any instant it is a frame strip of the morph; watched, every orb morphs in turn.
 *
 * Like [LumosOrbPreview], for a host project's preview pane or sample app, not a test.
 */
@Composable
fun LumosMorphPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(MORPH_PREVIEW_GAP)) {
        for (index in 0 until MORPH_STRIP_ORBS) {
            MorphOrb(
                startSeconds = index * MORPH_STRIP_STEP_SECONDS,
                size = MORPH_PREVIEW_SIZE,
                config = LumosConfig(label = "Lumos, morphing"),
            )
        }
    }
}

/** One orb of the morph strip, [startSeconds] into its cycle when first drawn. */
@Composable
internal fun MorphOrb(
    startSeconds: Float,
    size: Dp,
    config: LumosConfig = LumosConfig(),
    choreography: Choreography = Choreography.FLOW,
) {
    val source =
        remember(startSeconds, choreography) {
            MorphingFrameSource(LiveFrameSource(AtmospherePresets.IDLE), choreography = choreography).also {
                it.advance(startSeconds)
            }
        }
    LumosOrb(
        source = source,
        config = config,
        modifier = Modifier.size(size),
        backgroundColor = FORM_PREVIEW_BACKGROUND,
    )
}

/**
 * A live orb that morphs through [cycle] on its own clock: holds each form for [holdSeconds], then
 * morphs to the next over [morphSeconds], and round again. Everything but the schedule is the
 * [inner] source's.
 */
class MorphingFrameSource(
    val inner: LiveFrameSource,
    private val cycle: List<LumosForm> =
        listOf(Forms.Fibonacci, Forms.Cube, Forms.Lattice(AtmospherePresets.IDLE.resolution)),
    private val holdSeconds: Float = DEFAULT_HOLD_SECONDS,
    private val morphSeconds: Float = VoxelFrameBuilder.DEFAULT_MORPH_SECONDS,
    private val choreography: Choreography = Choreography.FLOW,
) : LumosFrameSource {
    init {
        require(cycle.isNotEmpty()) { "cycle must name at least one form" }
    }

    private var clock = 0f
    private var nextMorphAt = holdSeconds
    private var nextForm = 0

    override fun next(dt: Float): VoxelFrame {
        clock += dt
        if (clock >= nextMorphAt) {
            inner.builder.morphTo(cycle[nextForm], choreography = choreography, durationSeconds = morphSeconds)
            nextForm = (nextForm + 1) % cycle.size
            nextMorphAt += holdSeconds + morphSeconds
        }
        return inner.next(dt)
    }

    override fun still(dt: Float): VoxelFrame = inner.still(dt)

    override val revision: Long get() = inner.revision

    override val lookKey: String? get() = inner.lookKey

    override fun align(lookTime: Double) = inner.align(lookTime)

    /** Run the cycle forward [seconds] in steps the morph can follow, without drawing. */
    fun advance(seconds: Float) {
        var remaining = seconds
        while (remaining > 0f) {
            val step = minOf(remaining, ADVANCE_STEP_SECONDS)
            next(step)
            remaining -= step
        }
    }

    companion object {
        /** How long each form rests before the next morph. */
        const val DEFAULT_HOLD_SECONDS: Float = 1.2f

        private const val ADVANCE_STEP_SECONDS: Float = 1f / 30f
    }
}

/** Orbs in the strip. */
const val MORPH_STRIP_ORBS: Int = 6

/** How much further into the cycle each orb in the strip starts than its neighbour. */
const val MORPH_STRIP_STEP_SECONDS: Float = 0.6f

/** The strip orb's side. */
val MORPH_PREVIEW_SIZE: Dp = 160.dp

internal val MORPH_PREVIEW_GAP: Dp = 12.dp
