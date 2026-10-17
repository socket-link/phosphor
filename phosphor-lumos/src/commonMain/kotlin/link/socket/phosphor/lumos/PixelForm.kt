package link.socket.phosphor.lumos

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import link.socket.phosphor.lumos.look.ViewRotation
import link.socket.phosphor.math.Vector3

/**
 * A rectangle in root pixels, Compose orientation: Y down.
 *
 * [cornerRadius] is clamped to half the shorter side wherever it is used, so a
 * radius past that draws a pill, or a circle when the sides are equal.
 *
 * @throws IllegalArgumentException when a side is not positive, the radius is
 *  negative, or any field is not finite.
 */
data class RectPx(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val cornerRadius: Float,
) {
    init {
        require(left.isFinite() && top.isFinite()) { "the origin must be finite, was ($left, $top)" }
        require(width > 0f && width.isFinite()) { "width must be > 0 and finite, was $width" }
        require(height > 0f && height.isFinite()) { "height must be > 0 and finite, was $height" }
        require(cornerRadius >= 0f && cornerRadius.isFinite()) {
            "cornerRadius must be >= 0 and finite, was $cornerRadius"
        }
    }

    /** The right edge, `left + width`. */
    val right: Float get() = left + width

    /** The bottom edge, `top + height`. */
    val bottom: Float get() = top + height

    /** [cornerRadius] clamped to half the shorter side: the radius actually drawn. */
    val clampedCornerRadius: Float get() = min(cornerRadius, min(width, height) / 2f)

    /** The rounded rectangle's area in square pixels. */
    val area: Float
        get() {
            val r = clampedCornerRadius
            return width * height - (4f - PI.toFloat()) * r * r
        }

    /**
     * Signed distance in pixels from `(px, py)` to the rounded edge: negative
     * inside, zero on the edge, positive outside.
     */
    fun signedDistance(
        px: Float,
        py: Float,
    ): Float {
        val r = clampedCornerRadius
        val qx = abs(px - (left + width / 2f)) - (width / 2f - r)
        val qy = abs(py - (top + height / 2f)) - (height / 2f - r)
        val ox = max(qx, 0f)
        val oy = max(qy, 0f)
        return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - r
    }
}

/**
 * Forms laid out in root pixels: layout elements the orb's cells can become.
 *
 * Each is a [ViewForm], so it stays where the layout put it while the orb
 * turns, and [FramedForm.rigid], so the atmosphere's skin does not blur its
 * edge. Its positions are wherever the pixels map to through the
 * [OrbViewport], which may be outside the unit sphere: a button can be larger
 * than the orb, or beside it.
 */
object PixelForm {
    /** Dots' share of an element's cells on its outline: `Math.round(count * 0.18)`. */
    const val PERIMETER_FRACTION: Float = 0.18f

    /**
     * Dots' element target: [perimeterFraction] of the cells on the rounded
     * perimeter, the rest on a grid clipped to it.
     *
     * The spacing a count yields is [fillSpacingPx] inside and
     * [outlineSpacingPx] round the edge. On a 128 × 40 button with 8 px
     * corners at the default share: 4,801 cells, the hero orb's lattice at
     * resolution 10, fill it every 1.1 px, which reads as a solid fill; 1,141,
     * resolution 6, every 2.3 px, which reads as cells; and the inline orb's
     * 80 every 8.8 px, with 14 cells 23 px apart on the outline, which no
     * longer reads as an edge — [outlineFraction] gives a small count the
     * share that keeps it one.
     *
     * @param rect The element in root pixels; see [RoundedRect.rect].
     * @param viewport Where the orb sits in the same root.
     * @param perimeterFraction Share of the cells traced on the outline, `0..1`.
     * @throws IllegalArgumentException when [perimeterFraction] is outside `0..1`.
     */
    fun roundedRect(
        rect: RectPx,
        viewport: OrbViewport,
        perimeterFraction: Float = PERIMETER_FRACTION,
    ): RoundedRect = RoundedRect(rect, viewport, perimeterFraction)

    /**
     * The mean spacing in pixels of the grid [roundedRect] fills [rect] with
     * when [count] cells lay out on it: `√(area / fill)`, where the fill is
     * the cells not on the outline. The outline's spacing when every cell is
     * on it.
     */
    fun fillSpacingPx(
        rect: RectPx,
        count: Int,
        perimeterFraction: Float = PERIMETER_FRACTION,
    ): Float {
        require(count >= 1) { "count must be >= 1, was $count" }
        val outline = outlineCount(count, perimeterFraction)
        val fill = count - outline
        return if (fill >= 1) sqrt(rect.area / fill) else perimeterLength(rect) / outline
    }

    /**
     * The arc-length spacing in pixels of the cells [roundedRect] traces round
     * [rect]'s outline at [count]; infinite when no cell is on it.
     */
    fun outlineSpacingPx(
        rect: RectPx,
        count: Int,
        perimeterFraction: Float = PERIMETER_FRACTION,
    ): Float {
        require(count >= 1) { "count must be >= 1, was $count" }
        val outline = outlineCount(count, perimeterFraction)
        return if (outline >= 1) perimeterLength(rect) / outline else Float.POSITIVE_INFINITY
    }

    /**
     * The share of [count] cells to put on [rect]'s outline so that it reads
     * as an edge: [PERIMETER_FRACTION] at Dots' counts, and more as the count
     * falls, so the outline's cells stay [OUTLINE_DENSITY] times as close as
     * the fill's, about the ratio Dots' share gives 1,141 cells on a 128 × 40
     * button. Never below [PERIMETER_FRACTION]; 0.52 for the inline orb's 80
     * cells on that button.
     */
    fun outlineFraction(
        rect: RectPx,
        count: Int,
    ): Float {
        require(count >= 1) { "count must be >= 1, was $count" }
        // fill spacing = OUTLINE_DENSITY · outline spacing, solved for the share f:
        // area · count · f² + b · f − b = 0, with b = (OUTLINE_DENSITY · perimeter)².
        val a = rect.area.toDouble() * count
        val b = (OUTLINE_DENSITY.toDouble() * perimeterLength(rect)).let { it * it }
        val share = (-b + sqrt(b * b + 4.0 * a * b)) / (2.0 * a)
        return share.toFloat().coerceIn(PERIMETER_FRACTION, 1f)
    }

    /** How much closer [outlineFraction] keeps the outline's cells than the fill's. */
    const val OUTLINE_DENSITY: Float = 1.5f

    private fun outlineCount(
        count: Int,
        perimeterFraction: Float,
    ): Int = (count * perimeterFraction).roundToInt().coerceIn(0, count)

    /**
     * A rounded rectangle in root pixels as a form; see [roundedRect].
     *
     * Cells `0` to `round(count · perimeterFraction) − 1` walk the outline
     * clockwise from the top edge's left end, evenly spaced by arc length. The
     * rest fill a grid clipped to the outline in reading order, thinned at an
     * even stride so the holes spread. The same rect and count always lay out
     * the same cells in the same order.
     *
     * The layout is built when the rect or the count changes, and a new
     * [FormSample] comes with it. Between those, a frame that turns the orb
     * rewrites the same sample's positions in place, and a frame that does not
     * returns it untouched. Every cell faces the viewer: its unit direction is
     * view-space `+z`, carried back by the same rotation.
     */
    class RoundedRect internal constructor(
        rect: RectPx,
        val viewport: OrbViewport,
        val perimeterFraction: Float,
    ) : ViewForm {
        init {
            require(perimeterFraction in 0f..1f) { "perimeterFraction must be in 0..1, was $perimeterFraction" }
        }

        /**
         * The element in root pixels. A `var` so a surface can follow a moving
         * target without a new form: a morph re-reads its targets every step,
         * and the next sample lays out the new rect.
         */
        var rect: RectPx = rect

        // One holder swapped as a whole, so a rect set mid-frame costs a rebuild, never a torn layout.
        private var layout: Layout? = null

        override val rigid: Boolean get() = true

        /**
         * Half the fill's mean spacing, in unit space: cells on the grid touch
         * and do not overlap. Half the outline's spacing when every cell is on it.
         */
        override fun cellRadius(count: Int): Float {
            val rect = rect
            val outline = perimeterCount(count.coerceAtLeast(1))
            val fill = count - outline
            val spacingPx = if (fill >= 1) sqrt(rect.area / fill) else perimeterLength(rect) / outline
            return 0.5f * spacingPx / viewport.radiusPx
        }

        override fun sample(
            count: Int,
            look: LumosLook,
            time: Float,
            view: ViewRotation,
        ): FormSample {
            require(count >= 1) { "count must be >= 1, was $count" }
            val rect = rect
            val current =
                layout?.takeIf { it.rect == rect && it.count == count }
                    ?: lay(rect, count).also { layout = it }
            current.orient(view)
            return current.sample
        }

        override fun toString(): String = "PixelForm.roundedRect($rect)"

        private fun perimeterCount(count: Int): Int = (count * perimeterFraction).roundToInt().coerceIn(0, count)

        private fun lay(
            rect: RectPx,
            count: Int,
        ): Layout {
            val xs = FloatArray(count)
            val ys = FloatArray(count)
            val outline = perimeterCount(count)
            tracePerimeter(rect, outline, xs, ys)
            fillInside(rect, outline, count - outline, xs, ys)
            for (i in 0 until count) {
                xs[i] = viewport.unitX(xs[i])
                ys[i] = viewport.unitY(ys[i])
            }
            return Layout(rect, count, xs, ys)
        }
    }

    /**
     * One rect's cells at one count: where they sit in view space, and the
     * sample whose positions carry them into body space for the last rotation
     * seen.
     */
    private class Layout(
        val rect: RectPx,
        val count: Int,
        private val viewX: FloatArray,
        private val viewY: FloatArray,
    ) {
        private val positions = ArrayList<Vector3>(count)
        private val directions = ArrayList<Vector3>(count)
        val sample: FormSample

        // The rotation the positions were last carried by; identity as laid out.
        private var cosX = 1f
        private var sinX = 0f
        private var cosY = 1f
        private var sinY = 0f

        init {
            for (i in 0 until count) {
                positions += Vector3(viewX[i], viewY[i], 0f)
                directions += FACING
            }
            // Theta, phi, distance and jitter read the layout as laid out, so patterns sit still on it.
            val derived = FormSample.derive(positions, radiusScale = Forms.DISTANCE_SCALE)
            sample = FormSample(positions, directions, derived.theta, derived.phi, derived.distance, derived.jitter)
        }

        /** Carry every cell back by [view]'s rotation, in place; nothing to do when it has not moved. */
        fun orient(view: ViewRotation) {
            if (view.cosX == cosX && view.sinX == sinX && view.cosY == cosY && view.sinY == sinY) return
            cosX = view.cosX
            sinX = view.sinX
            cosY = view.cosY
            sinY = view.sinY
            // The layout is flat at view z = 0, which is exactly what the transpose's push helpers undo.
            for (i in 0 until count) {
                val x = viewX[i]
                val y = viewY[i]
                positions[i] = Vector3(view.unrotateX(x, y), view.unrotateY(x, y), view.unrotateZ(x, y))
            }
            // View +z carried back: the transpose's third column.
            val facing = Vector3(-sinY, cosY * sinX, cosY * cosX)
            for (i in 0 until count) directions[i] = facing
        }
    }

    private val FACING = Vector3(0f, 0f, 1f)

    private const val HALF_PI: Float = (PI / 2.0).toFloat()

    // How much denser each try at the clipped grid is than the last.
    private const val GRID_GROWTH: Float = 1.1f

    private fun perimeterLength(rect: RectPx): Float {
        val r = rect.clampedCornerRadius
        return 2f * (rect.width - 2f * r) + 2f * (rect.height - 2f * r) + 4f * HALF_PI * r
    }

    /**
     * Write [n] points evenly spaced by arc length along [rect]'s outline into
     * [xs] and [ys] from index 0, clockwise on screen from the top edge's left
     * end: top edge, top-right corner, right edge, and so on round.
     */
    private fun tracePerimeter(
        rect: RectPx,
        n: Int,
        xs: FloatArray,
        ys: FloatArray,
    ) {
        if (n == 0) return
        val r = rect.clampedCornerRadius
        val across = rect.width - 2f * r
        val down = rect.height - 2f * r
        val quarter = HALF_PI * r
        val total = perimeterLength(rect)
        for (i in 0 until n) {
            var s = total * i / n
            // Even segments are edges, odd ones corners; the last corner takes any rounding left over.
            var segment = 0
            while (segment < 7) {
                val length =
                    when {
                        segment % 2 == 1 -> quarter
                        segment % 4 == 0 -> across
                        else -> down
                    }
                if (s < length) break
                s -= length
                segment++
            }
            when (segment) {
                0 -> {
                    xs[i] = rect.left + r + s
                    ys[i] = rect.top
                }
                2 -> {
                    xs[i] = rect.right
                    ys[i] = rect.top + r + s
                }
                4 -> {
                    xs[i] = rect.right - r - s
                    ys[i] = rect.bottom
                }
                6 -> {
                    xs[i] = rect.left
                    ys[i] = rect.bottom - r - s
                }
                else -> {
                    val cx = if (segment == 1 || segment == 3) rect.right - r else rect.left + r
                    val cy = if (segment == 1 || segment == 7) rect.top + r else rect.bottom - r
                    // Screen angles turn clockwise with Y down: -90° is the top of the top-right corner.
                    val angle = (segment / 2 - 1) * HALF_PI + if (r > 0f) s / r else 0f
                    xs[i] = cx + r * cos(angle)
                    ys[i] = cy + r * sin(angle)
                }
            }
        }
    }

    /**
     * Write [n] points of a grid clipped to [rect]'s outline into [xs] and [ys]
     * from index [from]: the grid's cell centres inside the outline in reading
     * order, thinned at an even stride. The grid starts at about [n] cells
     * shaped like the rect and grows until enough centres land inside.
     */
    private fun fillInside(
        rect: RectPx,
        from: Int,
        n: Int,
        xs: FloatArray,
        ys: FloatArray,
    ) {
        if (n == 0) return
        val aspect = rect.width / rect.height
        var density = 1f
        while (true) {
            val cols = max(1, (sqrt(n * aspect) * density).roundToInt())
            val rows = max(1, (sqrt(n / aspect) * density).roundToInt())
            val dx = rect.width / cols
            val dy = rect.height / rows
            var inside = 0
            for (j in 0 until rows) {
                for (i in 0 until cols) {
                    if (rect.signedDistance(rect.left + dx * (i + 0.5f), rect.top + dy * (j + 0.5f)) <= 0f) inside++
                }
            }
            if (inside >= n) {
                // Output t takes candidate ⌊t · inside / n⌋: strictly increasing, since inside >= n.
                var candidate = 0
                var taken = 0
                for (j in 0 until rows) {
                    for (i in 0 until cols) {
                        val px = rect.left + dx * (i + 0.5f)
                        val py = rect.top + dy * (j + 0.5f)
                        if (rect.signedDistance(px, py) > 0f) continue
                        if (taken < n && taken.toLong() * inside / n == candidate.toLong()) {
                            xs[from + taken] = px
                            ys[from + taken] = py
                            taken++
                        }
                        candidate++
                    }
                }
                return
            }
            density *= GRID_GROWTH
        }
    }
}
