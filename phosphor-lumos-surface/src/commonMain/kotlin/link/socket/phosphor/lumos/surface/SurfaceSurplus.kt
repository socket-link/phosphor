package link.socket.phosphor.lumos.surface

import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import link.socket.phosphor.lumos.PixelForm
import link.socket.phosphor.lumos.RectPx

/**
 * What an assembly does with the cells a control does not need: the orb's count against the
 * count that reads as cells on the control.
 *
 * The 160 px hero orb runs the lattice at resolution 10, 4,801 cells. Into a 128 × 40 button
 * that is a cell every 1.1 px, which reads as a fill rather than cells; 1,141 cells, a cell every
 * 2.3 px, still read as cells (PHO-68). The 20 px inline orb has 80, fewer than any control needs,
 * so neither rule changes it.
 */
sealed interface SurfaceSurplus {
    /** Every cell flies to the control, however densely that packs it. The default. */
    data object Keep : SurfaceSurplus

    /**
     * Keep the cells a fill at [spacingPx] needs, spread evenly through the orb, and fade the
     * rest out over [duration] while the burst throws them, before the morph starts and drops
     * them. Nothing is cut that is still visible. A control that needs every cell keeps them all.
     *
     * @property duration How long the surplus takes to fade; the morph starts when it has.
     * @property spacingPx The fill's spacing on the control, in pixels; see [PixelForm.roundedRect].
     */
    data class Fade(
        val duration: Duration = DEFAULT_DURATION,
        val spacingPx: Float = DEFAULT_SPACING_PX,
    ) : SurfaceSurplus {
        init {
            require(duration.isPositive() && duration.isFinite()) { "duration must be > 0 and finite, was $duration" }
            require(spacingPx > 0f && spacingPx.isFinite()) { "spacingPx must be > 0 and finite, was $spacingPx" }
        }

        /**
         * How many of [count] cells [rect] keeps: its fill at [spacingPx] over the share of cells
         * [perimeterFraction] leaves inside the outline, never more than [count] and never fewer than 1.
         */
        fun keep(
            rect: RectPx,
            count: Int,
            perimeterFraction: Float = PixelForm.PERIMETER_FRACTION,
        ): Int {
            val fill = rect.area / (spacingPx * spacingPx)
            val wanted = (fill / (1f - perimeterFraction).coerceAtLeast(MIN_FILL_SHARE)).roundToInt()
            return wanted.coerceIn(1, count)
        }

        companion object {
            /** The fade's default length: 200 ms. */
            val DEFAULT_DURATION: Duration = 200.milliseconds

            /** PHO-68's prototype spacing, which read as cells on a 128 × 40 button: 2.3 px. */
            const val DEFAULT_SPACING_PX: Float = 2.3f

            private const val MIN_FILL_SHARE: Float = 0.05f
        }
    }
}

/**
 * [keep] indices of `0 until count`, ascending, at an even stride, so the cells kept are spread
 * through the orb rather than one side of it: index `⌊i · count / keep⌋` for each `i`.
 */
internal fun evenSurvivors(
    count: Int,
    keep: Int,
): IntArray = IntArray(keep) { i -> (i.toLong() * count / keep).toInt() }
