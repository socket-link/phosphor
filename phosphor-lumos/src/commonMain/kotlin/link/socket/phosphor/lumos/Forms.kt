package link.socket.phosphor.lumos

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import link.socket.phosphor.field.VoxelSphere
import link.socket.phosphor.math.Vector3

/**
 * The catalogue of [LumosForm]s: the [Lattice] every preset renders on, and the
 * count-driven forms PHO-56 adds.
 *
 * Every catalogue form returns exactly the `count` it is asked for, with every
 * position inside the unit sphere and the same output for the same inputs, so
 * two forms sampled at one count pair cell for cell by index. Each caches the
 * sample it last built and returns it until the inputs change, so sampling
 * costs nothing per frame while nothing moves.
 */
object Forms {
    /**
     * Today's geometry: the sphere-shaped integer lattice [VoxelSphere] builds,
     * every grid point within `resolution + 0.45` of the origin, normalized by
     * `max(resolution, 1)` into unit space.
     *
     * The count is fixed by [resolution] — 1 at 0, 171 at 3, 1,141 at 6, 4,801
     * at 10 — and the `count`, look and time passed to [sample] are ignored. The
     * sample is built once and the same instance is returned on every call, so
     * the lattice costs nothing per frame.
     *
     * @property resolution Radius of the lattice in grid cells; `>= 0`.
     * @throws IllegalArgumentException when [resolution] is negative.
     */
    class Lattice(val resolution: Int) : LumosForm {
        /** The lattice itself, for callers that read voxels directly. */
        val sphere: VoxelSphere = VoxelSphere(resolution)

        /** Cells in the lattice; what [sample] returns regardless of the count asked for. */
        val count: Int get() = sphere.count

        private val resting: FormSample = FormSample.fromLattice(sphere)

        override fun sample(
            count: Int,
            look: LumosLook,
            time: Float,
        ): FormSample = resting

        override fun toString(): String = "Forms.Lattice(resolution=$resolution)"
    }

    /**
     * The Fibonacci sphere: `count` points spread evenly over the unit sphere's
     * surface, Thinking Orbs' one point set and **the inline orb's form**.
     *
     * Point `i` sits at `y = 1 − 2(i + 0.5) / count` on the golden-angle spiral
     * `θ = i · π(3 − √5)`, so the first index is at the top and the last at the
     * bottom whatever the count, and any count from 1 up works. Ignores look
     * and time.
     *
     * The 20 px inline orb runs on this form with [inlineOrbCount] cells.
     */
    val Fibonacci: FramedForm = FibonacciForm

    /**
     * A cube inscribed in the unit sphere, Thinking Orbs' geometry: `n` steps
     * per edge give `6n² + 2` grid points on the surface, scaled by `1/√3` so
     * the corners touch the sphere, and framed at [FramedForm.scale] 1.2 so the
     * faces reach it too.
     *
     * `n` is the smallest step count whose grid holds at least `count` points;
     * the surplus is dropped at an even stride through the grid so the sample is
     * exactly `count` long. At `count = 8` that is the eight corners. Ignores
     * look and time.
     */
    val Cube: FramedForm = CubeForm

    /**
     * Every catalogue form by name, for previews, the CLI demo and docs. The
     * lattice is not listed: it fixes its own count and takes a resolution.
     */
    val All: List<Pair<String, LumosForm>> =
        listOf(
            "fibonacci" to Fibonacci,
            "cube" to Cube,
        )

    /** The catalogue form called [name], case-insensitively, or null. */
    fun byName(name: String): LumosForm? = All.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.second

    /**
     * How many cells the inline orb runs: `size × 4 × density`, never fewer than
     * [MIN_INLINE_ORB_COUNT]. Thinking Orbs' tuning — 80 dots at 20 px — carried
     * over with today's square cells.
     *
     * @param sizePx The orb's side in pixels.
     * @param density `LumosConfig.density`, the cell-count multiplier.
     */
    fun inlineOrbCount(
        sizePx: Int,
        density: Float = 1f,
    ): Int = max(MIN_INLINE_ORB_COUNT, (sizePx * CELLS_PER_INLINE_PX * density).roundToInt())

    /** The fewest cells the inline orb is ever given. */
    const val MIN_INLINE_ORB_COUNT: Int = 8

    /**
     * How many lattice cells a unit of radius spans in a catalogue form's
     * [FormSample.distance]: the presets' resolution, so the PULSE pattern
     * crosses a catalogue form in the same number of wavelengths it crosses the
     * presets' lattice. A form does not know the atmosphere's resolution, and a
     * fixed scale keeps its pattern the same at every count.
     */
    internal const val DISTANCE_SCALE: Float = 10f

    private const val CELLS_PER_INLINE_PX: Float = 4f

    /** The golden angle, `π(3 − √5)`, in radians. */
    private val GOLDEN_ANGLE: Float = (PI * (3.0 - sqrt(5.0))).toFloat()

    private val INV_SQRT_3: Float = (1.0 / sqrt(3.0)).toFloat()

    /**
     * A form that builds its positions once per count and holds the sample.
     * Look and time are ignored: for a still form they change nothing.
     */
    private abstract class StillForm : FramedForm {
        // One immutable holder swapped as a whole, so a stale read costs a rebuild, never a torn sample.
        private var cached: Cached? = null

        abstract fun positions(count: Int): List<Vector3>

        override fun sample(
            count: Int,
            look: LumosLook,
            time: Float,
        ): FormSample {
            require(count >= 1) { "count must be >= 1, was $count" }
            cached?.let { if (it.count == count) return it.sample }
            val sample = FormSample.derive(positions(count), radiusScale = DISTANCE_SCALE)
            cached = Cached(count, sample)
            return sample
        }

        private class Cached(val count: Int, val sample: FormSample)
    }

    private object FibonacciForm : StillForm() {
        override fun positions(count: Int): List<Vector3> {
            val positions = ArrayList<Vector3>(count)
            for (i in 0 until count) {
                val y = 1f - 2f * (i + 0.5f) / count
                val r = sqrt((1f - y * y).coerceAtLeast(0f))
                val theta = i * GOLDEN_ANGLE
                positions += Vector3(cos(theta) * r, y, sin(theta) * r)
            }
            return positions
        }

        override fun toString(): String = "Forms.Fibonacci"
    }

    private object CubeForm : StillForm() {
        override val scale: Float get() = SOLID_SCALE

        override fun cellRadius(count: Int): Float = INV_SQRT_3 / steps(count)

        /** The fewest steps per edge whose `6n² + 2` surface points reach [count]. */
        fun steps(count: Int): Int = max(1, ceil(sqrt((count - 2) / 6.0)).toInt())

        override fun positions(count: Int): List<Vector3> {
            val n = steps(count)
            val total = 6 * n * n + 2
            val grid = ArrayList<Vector3>(total)
            for (i in 0..n) {
                for (j in 0..n) {
                    for (k in 0..n) {
                        val onSurface = i == 0 || i == n || j == 0 || j == n || k == 0 || k == n
                        if (!onSurface) continue
                        grid +=
                            Vector3(
                                (2f * i / n - 1f) * INV_SQRT_3,
                                (2f * j / n - 1f) * INV_SQRT_3,
                                (2f * k / n - 1f) * INV_SQRT_3,
                            )
                    }
                }
            }
            return thinned(grid, count)
        }

        override fun toString(): String = "Forms.Cube"
    }

    /** Thinking Orbs draws its solids at this scale: inscribed in the sphere, framed past it. */
    private const val SOLID_SCALE: Float = 1.2f

    /**
     * Exactly [count] of [points], in order, the surplus dropped at an even
     * stride so the holes spread over the whole shape instead of bunching at
     * its end. [points] must hold at least [count].
     */
    private fun thinned(
        points: List<Vector3>,
        count: Int,
    ): List<Vector3> {
        val total = points.size
        require(total >= count) { "need $count points, have $total" }
        if (total == count) return points
        val kept = ArrayList<Vector3>(count)
        for (i in 0 until count) {
            kept += points[(i.toLong() * total / count).toInt()]
        }
        return kept
    }
}

/**
 * This form at a fixed [count], whatever count the builder has budget for: the
 * way a host runs an 80-cell inline orb on a builder whose atmosphere is at
 * resolution 10. Look, time and the form's framing pass through.
 *
 * @throws IllegalArgumentException when [count] is not positive.
 */
fun LumosForm.counted(count: Int): FramedForm = CountedForm(this, count)

private class CountedForm(
    private val inner: LumosForm,
    private val fixedCount: Int,
) : FramedForm {
    init {
        require(fixedCount >= 1) { "count must be >= 1, was $fixedCount" }
    }

    private val framed: FramedForm? = inner as? FramedForm

    override val scale: Float get() = framed?.scale ?: 1f

    override val tipDegrees: Float get() = framed?.tipDegrees ?: 0f

    override fun cellRadius(count: Int): Float = framed?.cellRadius(count) ?: super.cellRadius(count)

    override fun sample(
        count: Int,
        look: LumosLook,
        time: Float,
    ): FormSample = inner.sample(fixedCount, look, time)

    override fun toString(): String = "$inner.counted($fixedCount)"
}
