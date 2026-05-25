package link.socket.phosphor.lumos

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2D shape predicate that classifies points in [-1, 1]^2 screen-space as
 * inside-the-glyph or outside.
 *
 * The screen-space coordinate is the voxel's unit direction, rotated into
 * camera space, projected to its X/Y components.
 */
fun interface GlyphShape {
    fun contains(
        screenX: Float,
        screenY: Float,
    ): Boolean

    companion object {
        fun forGlyph(glyph: LumosGlyph): GlyphShape =
            when (glyph) {
                LumosGlyph.CHECK -> CheckShape
                LumosGlyph.EXCLAIM -> ExclaimShape
                LumosGlyph.QUESTION -> QuestionShape
                LumosGlyph.HEART -> HeartShape
                LumosGlyph.STAR -> StarShape
                LumosGlyph.LIGHTNING -> LightningShape
            }
    }
}

internal object CheckShape : GlyphShape {
    override fun contains(
        screenX: Float,
        screenY: Float,
    ): Boolean =
        distanceToSegment(screenX, screenY, -0.50f, 0.00f, -0.10f, -0.40f) < 0.105f ||
            distanceToSegment(screenX, screenY, -0.10f, -0.40f, 0.52f, 0.42f) < 0.105f
}

internal object ExclaimShape : GlyphShape {
    override fun contains(
        screenX: Float,
        screenY: Float,
    ): Boolean {
        val stem = abs(screenX) < 0.08f && screenY in -0.35f..0.35f
        val dot = distance(screenX, screenY, 0f, -0.55f) < 0.12f
        return stem || dot
    }
}

internal object QuestionShape : GlyphShape {
    override fun contains(
        screenX: Float,
        screenY: Float,
    ): Boolean {
        val dx = screenX - 0.02f
        val dy = screenY - 0.20f
        val radius = sqrt(dx * dx + dy * dy)
        val angle = atan2(dy, dx)
        val arc = abs(radius - 0.32f) < 0.085f && angle in -0.15f..2.95f
        val hook = distanceToSegment(screenX, screenY, 0.25f, 0.06f, 0.02f, -0.24f) < 0.085f
        val dot = distance(screenX, screenY, 0f, -0.55f) < 0.12f
        return arc || hook || dot
    }
}

internal object HeartShape : GlyphShape {
    override fun contains(
        screenX: Float,
        screenY: Float,
    ): Boolean {
        val x = screenX * 1.18f
        val y = screenY * 1.18f + 0.10f
        val field = x * x + y * y - 0.60f
        return field * field * field - x * x * y * y * y < 0f
    }
}

internal object StarShape : GlyphShape {
    private val vertices: List<Pair<Float, Float>> =
        buildList {
            repeat(STAR_VERTEX_COUNT) { index ->
                val radius = if (index % 2 == 0) OUTER_RADIUS else INNER_RADIUS
                val angle = (PI / 2.0 + index * PI / 5.0).toFloat()
                add(cos(angle) * radius to sin(angle) * radius)
            }
        }

    override fun contains(
        screenX: Float,
        screenY: Float,
    ): Boolean = pointInPolygon(screenX, screenY, vertices)

    private const val STAR_VERTEX_COUNT = 10
    private const val OUTER_RADIUS = 0.58f
    private const val INNER_RADIUS = 0.25f
}

internal object LightningShape : GlyphShape {
    override fun contains(
        screenX: Float,
        screenY: Float,
    ): Boolean =
        distanceToSegment(screenX, screenY, 0.24f, 0.52f, -0.08f, 0.03f) < 0.10f ||
            distanceToSegment(screenX, screenY, -0.08f, 0.03f, 0.14f, 0.03f) < 0.10f ||
            distanceToSegment(screenX, screenY, 0.14f, 0.03f, -0.24f, -0.52f) < 0.10f
}

private fun distanceToSegment(
    x: Float,
    y: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
): Float {
    val vx = bx - ax
    val vy = by - ay
    val lengthSquared = vx * vx + vy * vy
    if (lengthSquared == 0f) return distance(x, y, ax, ay)

    val t = (((x - ax) * vx + (y - ay) * vy) / lengthSquared).coerceIn(0f, 1f)
    return distance(x, y, ax + vx * t, ay + vy * t)
}

private fun distance(
    x: Float,
    y: Float,
    cx: Float,
    cy: Float,
): Float {
    val dx = x - cx
    val dy = y - cy
    return sqrt(dx * dx + dy * dy)
}

private fun pointInPolygon(
    x: Float,
    y: Float,
    vertices: List<Pair<Float, Float>>,
): Boolean {
    var inside = false
    var previousIndex = vertices.lastIndex

    for (index in vertices.indices) {
        val (xi, yi) = vertices[index]
        val (xj, yj) = vertices[previousIndex]
        val intersects = (yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi
        if (intersects) {
            inside = !inside
        }
        previousIndex = index
    }

    return inside
}
