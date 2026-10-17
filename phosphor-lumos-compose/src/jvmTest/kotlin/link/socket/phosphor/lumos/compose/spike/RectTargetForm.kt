package link.socket.phosphor.lumos.compose.spike

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import link.socket.phosphor.lumos.FormSample
import link.socket.phosphor.lumos.FramedForm
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.math.Vector3

/**
 * PHO-68 throwaway: a layout element as a [link.socket.phosphor.lumos.LumosForm].
 *
 * Dots' `element` target, read from `swarm-surface.js`: 18 % of the cells trace the rounded
 * rectangle's perimeter and the rest fill a grid clipped to it. The rectangle is given in **root
 * pixels**; this form converts to the orb's unit space through [OrbViewport] — the pixel-to-unit
 * transform the spike expected to sit at the builder — and then **counter-rotates** every target by
 * the frame's orb rotation, because a form samples in body space and the projection spins the body,
 * while a button does not spin. Without the counter-rotation the assembled rectangle turns with the
 * orb. That is the one thing a `LumosForm` cannot do today: `sample(count, look, time)` has no view
 * rotation, so this prototype reads it from the builder through [rotation].
 *
 * Allocates a sample every frame while the rotation moves. A real `PixelForm` would reuse arrays.
 */
class RectTargetForm(
    private val viewport: OrbViewport,
    private val rotation: () -> Pair<Float, Float>,
    private val perimeterFraction: Float = PERIMETER_FRACTION,
) : FramedForm {
    /** The target rectangle in root pixels; null draws nothing useful (the form falls back to the origin). */
    var rectPx: RectPx? = null

    private var lastKey: Any? = null
    private var lastSample: FormSample? = null

    // The flat targets in view space, before counter-rotation; rebuilt when the rect or count changes.
    private var viewTargets: List<Vector3> = emptyList()
    private var viewKey: Any? = null

    override fun cellRadius(count: Int): Float {
        val rect = rectPx ?: return super.cellRadius(count)
        // Half the grid spacing in unit space, so cells touch but do not overlap on the fill.
        val fill = count - (count * perimeterFraction).roundToInt()
        val cols = max(1, sqrt(fill * rect.width / rect.height).roundToInt())
        return 0.5f * (rect.width / cols) / viewport.radiusPx * CELL_FILL
    }

    override fun sample(
        count: Int,
        look: LumosLook,
        time: Float,
    ): FormSample {
        val rect = rectPx
        val (rx, ry) = rotation()
        val key = Triple(rect, count, rx to ry)
        lastSample?.let { if (key == lastKey) return it }

        val vKey = rect to count
        if (vKey != viewKey || viewTargets.size != count) {
            viewTargets = if (rect == null) List(count) { Vector3.ZERO } else layout(rect, count)
            viewKey = vKey
        }
        // Body = Ry⁻¹ · Rx⁻¹ · view, the inverse of ComposeLattice's X-then-Y rotation.
        val cosX = cos(rx)
        val sinX = sin(rx)
        val cosY = cos(ry)
        val sinY = sin(ry)
        val body = ArrayList<Vector3>(count)
        for (v in viewTargets) {
            // Undo Y: x1 = x·cosY − z·sinY ; z1 = x·sinY + z·cosY
            val x1 = v.x * cosY - v.z * sinY
            val z1 = v.x * sinY + v.z * cosY
            // Undo X: y0 = y·cosX + z1·sinX ; z0 = −y·sinX + z1·cosX
            val y0 = v.y * cosX + z1 * sinX
            val z0 = -v.y * sinX + z1 * cosX
            body += Vector3(x1, y0, z0)
        }
        return FormSample.derive(body, radiusScale = DISTANCE_SCALE).also {
            lastSample = it
            lastKey = key
        }
    }

    /** Dots' element layout: [perimeterFraction] of the cells on the rounded perimeter, the rest on a clipped grid. */
    private fun layout(
        rect: RectPx,
        count: Int,
    ): List<Vector3> {
        val perimeterCount = (count * perimeterFraction).roundToInt().coerceIn(0, count)
        val fillCount = count - perimeterCount
        val out = ArrayList<Vector3>(count)
        perimeter(rect, perimeterCount, out)
        fill(rect, fillCount, out)
        return out
    }

    private fun perimeter(
        rect: RectPx,
        n: Int,
        out: MutableList<Vector3>,
    ) {
        if (n == 0) return
        val r = rect.cornerRadius.coerceAtMost(minOf(rect.width, rect.height) / 2f)
        val straight = 2f * (rect.width - 2f * r) + 2f * (rect.height - 2f * r)
        val arcs = (2.0 * PI * r).toFloat()
        val total = straight + arcs
        for (i in 0 until n) {
            val s = total * i / n
            val (px, py) = pointAt(rect, r, s)
            out += viewport.toUnit(px, py)
        }
    }

    // Walks the rounded rectangle clockwise from the top-left corner's end of the top edge.
    private fun pointAt(
        rect: RectPx,
        r: Float,
        distance: Float,
    ): Pair<Float, Float> {
        val w = rect.width - 2f * r
        val h = rect.height - 2f * r
        val quarter = (PI / 2 * r).toFloat()
        var s = distance
        val left = rect.left
        val top = rect.top
        val right = rect.left + rect.width
        val bottom = rect.top + rect.height
        if (s < w) return (left + r + s) to top
        s -= w
        if (s < quarter) return arc(right - r, top + r, r, -PI.toFloat() / 2f, s / r)
        s -= quarter
        if (s < h) return right to (top + r + s)
        s -= h
        if (s < quarter) return arc(right - r, bottom - r, r, 0f, s / r)
        s -= quarter
        if (s < w) return (right - r - s) to bottom
        s -= w
        if (s < quarter) return arc(left + r, bottom - r, r, PI.toFloat() / 2f, s / r)
        s -= quarter
        if (s < h) return left to (bottom - r - s)
        s -= h
        return arc(left + r, top + r, r, PI.toFloat(), s / r)
    }

    private fun arc(
        cx: Float,
        cy: Float,
        r: Float,
        start: Float,
        sweep: Float,
    ): Pair<Float, Float> = (cx + r * cos(start + sweep)) to (cy + r * sin(start + sweep))

    private fun fill(
        rect: RectPx,
        n: Int,
        out: MutableList<Vector3>,
    ) {
        if (n == 0) return
        val r = rect.cornerRadius.coerceAtMost(minOf(rect.width, rect.height) / 2f)
        // Over-provision the grid so clipping the corners still leaves at least n points.
        var cols = max(1, sqrt(n * rect.width / rect.height).roundToInt())
        var rows = max(1, (n + cols - 1) / cols)
        val candidates = ArrayList<Pair<Float, Float>>()
        while (true) {
            candidates.clear()
            val dx = rect.width / cols
            val dy = rect.height / rows
            for (j in 0 until rows) {
                for (i in 0 until cols) {
                    val px = rect.left + dx * (i + 0.5f)
                    val py = rect.top + dy * (j + 0.5f)
                    if (insideRounded(rect, r, px, py)) candidates += px to py
                }
            }
            if (candidates.size >= n) break
            cols++
            rows = max(1, (n + cols - 1) / cols)
        }
        // Thin at an even stride, as Forms.thinned does, so the holes spread.
        val total = candidates.size
        for (i in 0 until n) {
            val (px, py) = candidates[(i.toLong() * total / n).toInt()]
            out += viewport.toUnit(px, py)
        }
    }

    private fun insideRounded(
        rect: RectPx,
        r: Float,
        px: Float,
        py: Float,
    ): Boolean = roundedRectDistance(rect, r, px, py) <= 0f

    override fun toString(): String = "RectTargetForm($rectPx)"

    companion object {
        /** Dots: `const perimeterCount = Math.round(count * 0.18)`. */
        const val PERIMETER_FRACTION: Float = 0.18f

        /** Fill a little past the grid spacing so the assembled rect reads solid before hand-off. */
        private const val CELL_FILL: Float = 0.9f

        /** The presets' resolution, as `Forms.DISTANCE_SCALE`: the PULSE pattern's wavelength scale. */
        private const val DISTANCE_SCALE: Float = 10f
    }
}

/**
 * Signed distance from `(px, py)` to the edge of [rect] with corner radius [r], in pixels:
 * negative inside, zero on the edge, positive outside.
 */
fun roundedRectDistance(
    rect: RectPx,
    r: Float,
    px: Float,
    py: Float,
): Float {
    val cx = rect.left + rect.width / 2f
    val cy = rect.top + rect.height / 2f
    val qx = abs(px - cx) - (rect.width / 2f - r)
    val qy = abs(py - cy) - (rect.height / 2f - r)
    val ox = max(qx, 0f)
    val oy = max(qy, 0f)
    return sqrt(ox * ox + oy * oy) + minOf(max(qx, qy), 0f) - r
}

/** A rectangle in root pixels, Compose orientation: Y down. */
data class RectPx(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val cornerRadius: Float,
)

/**
 * Where the orb's unit sphere sits in root pixels: the pixel-to-unit transform. `[-1, 1]` maps to
 * `centre ± radiusPx`, Y flipped as `ComposeLattice` flips it.
 */
data class OrbViewport(
    val centerXPx: Float,
    val centerYPx: Float,
    val radiusPx: Float,
) {
    fun toUnit(
        px: Float,
        py: Float,
    ): Vector3 = Vector3((px - centerXPx) / radiusPx, -(py - centerYPx) / radiusPx, 0f)

    fun toPxX(ux: Float): Float = centerXPx + ux * radiusPx

    fun toPxY(uy: Float): Float = centerYPx - uy * radiusPx
}
