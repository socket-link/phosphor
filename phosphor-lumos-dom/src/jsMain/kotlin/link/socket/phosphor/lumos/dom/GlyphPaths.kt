package link.socket.phosphor.lumos.dom

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A glyph's overlay as SVG: the path data, whether it is filled or stroked, the stroke width, and
 * the dot below it for `!` and `?`. Every number is already an attribute string.
 */
internal class GlyphGeometry(
    val d: String,
    val filled: Boolean,
    val strokeWidth: String,
    val dot: GlyphDot?,
)

/** The filled dot under a glyph's stem: centre and radius, as attribute strings. */
internal class GlyphDot(
    val cx: String,
    val cy: String,
    val r: String,
)

/**
 * The six glyphs `LumosCanvas` overlays, in the same geometry: centred on the canvas, `0.30 ×` the
 * shorter side per glyph unit (so a glyph spans about 60% of it), stroked `0.18` units wide with
 * round caps and joins. Glyph space is Y up and the canvas Y down, so every glyph-space `y` is
 * subtracted from the centre. Keep the two in step: a change to a glyph in `LumosCanvas.kt` belongs
 * here too.
 */
internal object GlyphPaths {
    private var cachedKey: String? = null
    private var cached: GlyphGeometry? = null

    /** The geometry of [name] on a [width] × [height] canvas, or null for a glyph it does not know. */
    fun of(
        name: String,
        width: Int,
        height: Int,
    ): GlyphGeometry? {
        // A glyph holds for a second or more at one size; build its strings once.
        val key = "$name $width $height"
        if (key != cachedKey) {
            cachedKey = key
            cached = build(name, width, height)
        }
        return cached
    }

    private fun build(
        name: String,
        width: Int,
        height: Int,
    ): GlyphGeometry? {
        val cx = width / 2f
        val cy = height / 2f
        val s = min(width, height) * SCALE
        val stroke = s * STROKE
        val strokeWidth = svgNumber(stroke)
        val dot = GlyphDot(svgNumber(cx), svgNumber(cy + 0.55f * s), svgNumber(stroke / 2f))
        val path = PathData()
        return when (name) {
            // Glyph space (Y up): (-0.50, 0.00) → (-0.10, -0.40) → (0.52, 0.42).
            "CHECK" -> {
                path
                    .move(cx - 0.50f * s, cy)
                    .line(cx - 0.10f * s, cy + 0.40f * s)
                    .line(cx + 0.52f * s, cy - 0.42f * s)
                GlyphGeometry(path.toString(), filled = false, strokeWidth, dot = null)
            }
            // A stem from y = 0.35 to y = -0.35, and the dot below it.
            "EXCLAIM" -> {
                path.move(cx, cy - 0.35f * s).line(cx, cy + 0.35f * s)
                GlyphGeometry(path.toString(), filled = false, strokeWidth, dot)
            }
            // A 170° arc about glyph (0.02, 0.20), radius 0.32, from 8.6° canvas-clockwise, then a
            // short hook down toward the centre, and the dot.
            "QUESTION" -> {
                val arcCx = cx + 0.02f * s
                val arcCy = cy - 0.20f * s
                val arcR = 0.32f * s
                val start = QUESTION_START_DEGREES * DEGREES
                val end = (QUESTION_START_DEGREES + QUESTION_SWEEP_DEGREES) * DEGREES
                path
                    .move(arcCx + arcR * cos(start).toFloat(), arcCy + arcR * sin(start).toFloat())
                    .arc(arcR, arcCx + arcR * cos(end).toFloat(), arcCy + arcR * sin(end).toFloat())
                    .line(cx + 0.02f * s, cy + 0.24f * s)
                GlyphGeometry(path.toString(), filled = false, strokeWidth, dot)
            }
            // Two cubic lobes from the tip at (0, -0.52) to the notch at (0, -0.18) and back, filled.
            "HEART" -> {
                path
                    .move(cx, cy + 0.52f * s)
                    .cubic(cx - 0.62f * s, cy + 0.18f * s, cx - 0.62f * s, cy - 0.30f * s, cx, cy - 0.18f * s)
                    .cubic(cx + 0.62f * s, cy - 0.30f * s, cx + 0.62f * s, cy + 0.18f * s, cx, cy + 0.52f * s)
                    .close()
                GlyphGeometry(path.toString(), filled = true, strokeWidth, dot = null)
            }
            // Ten vertices alternating radius 0.58 and 0.25, the first straight up, filled.
            "STAR" -> {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 0.58f * s else 0.25f * s
                    val angle = PI / 2.0 + i * PI / 5.0
                    val x = cx + cos(angle).toFloat() * r
                    val y = cy - sin(angle).toFloat() * r
                    if (i == 0) path.move(x, y) else path.line(x, y)
                }
                path.close()
                GlyphGeometry(path.toString(), filled = true, strokeWidth, dot = null)
            }
            // Glyph space: (0.24, 0.52) → (-0.08, 0.03) → (0.14, 0.03) → (-0.24, -0.52).
            "LIGHTNING" -> {
                path
                    .move(cx + 0.24f * s, cy - 0.52f * s)
                    .line(cx - 0.08f * s, cy - 0.03f * s)
                    .line(cx + 0.14f * s, cy - 0.03f * s)
                    .line(cx - 0.24f * s, cy + 0.52f * s)
                GlyphGeometry(path.toString(), filled = false, strokeWidth, dot = null)
            }
            else -> null
        }
    }

    private const val SCALE = 0.30f
    private const val STROKE = 0.18f
    private const val QUESTION_START_DEGREES = 8.6
    private const val QUESTION_SWEEP_DEGREES = 170.0
    private const val DEGREES = PI / 180.0
}

/** SVG path data, built a command at a time: `M14 20L18.8 24.8`. */
internal class PathData {
    private val out = StringBuilder()

    fun move(
        x: Float,
        y: Float,
    ): PathData = command('M', x, y)

    fun line(
        x: Float,
        y: Float,
    ): PathData = command('L', x, y)

    fun cubic(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x: Float,
        y: Float,
    ): PathData {
        command('C', x1, y1)
        out.append(' ').append(svgNumber(x2)).append(' ').append(svgNumber(y2))
        out.append(' ').append(svgNumber(x)).append(' ').append(svgNumber(y))
        return this
    }

    /** A circular arc of [radius] under 180°, clockwise on the canvas, to `(x, y)`. */
    fun arc(
        radius: Float,
        x: Float,
        y: Float,
    ): PathData {
        val r = svgNumber(radius)
        out.append('A').append(r).append(' ').append(r).append(" 0 0 1 ")
        out.append(svgNumber(x)).append(' ').append(svgNumber(y))
        return this
    }

    fun close(): PathData {
        out.append('Z')
        return this
    }

    private fun command(
        letter: Char,
        x: Float,
        y: Float,
    ): PathData {
        out.append(letter).append(svgNumber(x)).append(' ').append(svgNumber(y))
        return this
    }

    override fun toString(): String = out.toString()
}
