package link.socket.phosphor.lumos.dom

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The part of an SVG element the surface touches: attributes and children, nothing else.
 *
 * The browser's is [DomSvgNode]. The tests hand the surface a recording fake instead, so the
 * attribute set a frame emits, and how many writes it took, are asserted without a browser.
 */
internal interface SvgNode {
    fun setAttribute(
        name: String,
        value: String,
    )

    fun removeAttribute(name: String)

    fun appendChild(child: SvgNode)

    fun removeChild(child: SvgNode)
}

/** Makes SVG elements by tag name. */
internal fun interface SvgFactory {
    fun create(tag: String): SvgNode
}

/** Attribute writes made and writes skipped because the value had not changed, across one surface. */
internal class WriteCounter {
    var writes: Long = 0L
    var skips: Long = 0L
}

/**
 * An element whose attributes are written through a string cache, Thinking Orbs' way: a value
 * equal to the one last written is not written again, so an element that did not change costs no
 * DOM write. Each attribute the element's owner writes has a fixed [slot].
 *
 * A hidden element keeps its cache, so when it is shown again only what changed while it was
 * hidden is written.
 */
internal class CachedNode(
    val node: SvgNode,
    slots: Int,
    private val counter: WriteCounter,
) {
    private val values = arrayOfNulls<String>(slots)

    /** Whether the element carries `visibility="hidden"`. */
    var hidden: Boolean = false
        private set

    fun set(
        slot: Int,
        name: String,
        value: String,
    ) {
        if (values[slot] == value) {
            counter.skips++
            return
        }
        values[slot] = value
        node.setAttribute(name, value)
        counter.writes++
    }

    /** Write an attribute the element keeps for good, once, outside the cache. */
    fun setOnce(
        name: String,
        value: String,
    ) {
        node.setAttribute(name, value)
        counter.writes++
    }

    fun hide() {
        if (hidden) return
        node.setAttribute("visibility", "hidden")
        counter.writes++
        hidden = true
    }

    fun show() {
        if (!hidden) return
        node.setAttribute("visibility", "visible")
        counter.writes++
        hidden = false
    }
}

/**
 * [value] to two decimals with no exponent and no trailing zeros: `8`, `19.5`, `-1.8`, `0.05`.
 * Short strings, and two values a hundredth of a pixel apart are one cache hit. A value that is not
 * finite is `0`.
 */
internal fun svgNumber(value: Float): String {
    if (!value.isFinite()) return "0"
    val scaled = (value * 100f).roundToInt()
    if (scaled == 0) return "0"
    val sign = if (scaled < 0) "-" else ""
    val magnitude = abs(scaled)
    val whole = magnitude / 100
    val fraction = magnitude % 100
    return when {
        fraction == 0 -> "$sign$whole"
        fraction % 10 == 0 -> "$sign$whole.${fraction / 10}"
        fraction < 10 -> "$sign$whole.0$fraction"
        else -> "$sign$whole.$fraction"
    }
}

/** An sRGB colour, each channel 0..1, as `rgb(r,g,b)` in 0..255. */
internal fun svgRgb(
    red: Float,
    green: Float,
    blue: Float,
): String = "rgb(${channel(red)},${channel(green)},${channel(blue)})"

private fun channel(value: Float): Int = if (value.isFinite()) (value.coerceIn(0f, 1f) * 255f).roundToInt() else 0
