package link.socket.phosphor.lumos.dom

import kotlin.math.abs
import kotlin.math.roundToInt
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.dom.frame.LumosCanvasFrame

/**
 * Draws a [LumosCanvasFrame] into an `<svg>`: one element per cell, updated by attribute, in
 * Thinking Orbs' `OrbRender` shape. Dots are `<circle>`, Squares are `<rect>`.
 *
 * - Elements are created once and kept; a frame with fewer cells hides the surplus with
 *   `visibility="hidden"` and remembers it ([hidden]), so a hidden cell costs no attribute
 *   write on later frames — Thinking Orbs' `hidden[]` trick.
 * - Every attribute write goes through a per-element string cache; an unchanged value is not
 *   written, so a still orb costs zero DOM writes a frame.
 * - Radii are held to [SurfaceStyle.MIN_RADIUS_PX], 0.45 px, the Thinking Orbs floor.
 * - [Tint.CURRENT_COLOR] fills with `currentColor` and carries the cell's luminance in
 *   `fill-opacity`, so CSS `color` tints the orb; [Tint.PER_CELL] writes each cell's own sRGB.
 *
 * The `viewBox` is set in CSS pixels, so the browser scales the vectors; under pinch zoom the
 * marks stay sharp without a redraw.
 */
class SvgOrbSurface(
    private val svg: WebElement,
    val style: SurfaceStyle,
    val tint: Tint,
) {
    enum class Tint { PER_CELL, CURRENT_COLOR }

    private val nodes = ArrayList<WebElement>()
    private val hidden = ArrayList<Boolean>()
    private val cache = ArrayList<Array<String?>>()
    private var viewBox: String? = null

    var attributeWrites: Long = 0L
        private set
    var attributeSkips: Long = 0L
        private set
    var elements: Int = 0
        private set

    init {
        require(style == SurfaceStyle.Dots || style == SurfaceStyle.Squares) { "spike draws Dots and Squares only" }
    }

    fun render(frame: LumosCanvasFrame) {
        val box = "0 0 ${frame.width} ${frame.height}"
        if (box != viewBox) {
            svg.setAttribute("viewBox", box)
            viewBox = box
        }
        val voxels = frame.voxels
        val n = voxels.size
        while (nodes.size < n) {
            val node = createSvgElement(if (style == SurfaceStyle.Dots) "circle" else "rect")
            if (tint == Tint.CURRENT_COLOR) node.setAttribute("fill", "currentColor")
            svg.appendChild(node)
            nodes += node
            hidden += false
            cache += arrayOfNulls(SLOTS)
            elements = nodes.size
        }
        for (i in 0 until n) {
            val v = voxels[i]
            val r = if (v.radiusPx < SurfaceStyle.MIN_RADIUS_PX) SurfaceStyle.MIN_RADIUS_PX else v.radiusPx
            if (hidden[i]) {
                nodes[i].setAttribute("visibility", "visible")
                attributeWrites++
                hidden[i] = false
            }
            if (style == SurfaceStyle.Dots) {
                set(i, 0, "cx", fmt(v.screenX))
                set(i, 1, "cy", fmt(v.screenY))
                set(i, 2, "r", fmt(r))
            } else {
                set(i, 0, "x", fmt(v.screenX - r))
                set(i, 1, "y", fmt(v.screenY - r))
                val side = fmt(r * 2f)
                set(i, 2, "width", side)
                set(i, 3, "height", side)
            }
            when (tint) {
                Tint.PER_CELL -> {
                    set(i, 4, "fill", "rgb(${channel(v.red)},${channel(v.green)},${channel(v.blue)})")
                    set(i, 5, "fill-opacity", fmt(v.alpha))
                }
                Tint.CURRENT_COLOR -> {
                    val luminance = 0.2126f * v.red + 0.7152f * v.green + 0.0722f * v.blue
                    set(i, 5, "fill-opacity", fmt(luminance * v.alpha))
                }
            }
        }
        for (i in n until nodes.size) {
            if (!hidden[i]) {
                nodes[i].setAttribute("visibility", "hidden")
                attributeWrites++
                hidden[i] = true
            }
        }
    }

    private fun set(
        index: Int,
        slot: Int,
        name: String,
        value: String,
    ) {
        val slots = cache[index]
        if (slots[slot] == value) {
            attributeSkips++
            return
        }
        slots[slot] = value
        nodes[index].setAttribute(name, value)
        attributeWrites++
    }

    private companion object {
        const val SLOTS = 6

        fun channel(c: Float): Int = (c.coerceIn(0f, 1f) * 255f).roundToInt()

        // Two decimals, no exponent form: short attribute strings and more cache hits.
        fun fmt(v: Float): String {
            val scaled = (v * 100f).roundToInt()
            val whole = scaled / 100
            val frac = abs(scaled % 100)
            val sign = if (scaled < 0 && whole == 0) "-" else ""
            return if (frac == 0) "$sign$whole" else "$sign$whole.${if (frac < 10) "0$frac" else "$frac"}"
        }
    }
}
