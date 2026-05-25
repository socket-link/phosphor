package link.socket.phosphor.lumos.cli.glyph

import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.color.OklabColor
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame.TerminalCell

/**
 * Row-major rectangular bitmask describing a 2D glyph footprint.
 *
 * `mask[y * width + x]` is `true` for cells that belong to the glyph (and so
 * replace the underlying orb cell when the glyph is fully visible) and `false`
 * for cells that let the orb show through.
 */
data class CharGrid(
    val width: Int,
    val height: Int,
    val mask: List<Boolean>,
    val chars: List<Char> = List(width * height) { GLYPH_FILL_CHAR },
) {
    init {
        require(width >= 0) { "width must be >= 0" }
        require(height >= 0) { "height must be >= 0" }
        require(mask.size == width * height) { "mask size must equal width * height" }
        require(chars.size == width * height) { "chars size must equal width * height" }
    }

    companion object {
        internal const val GLYPH_FILL_CHAR: Char = '#'
    }
}

/**
 * 2D ASCII analog of the 3D voxel glyph carving from PHO-18.
 *
 * Replaces frame cells that fall under the active glyph's footprint with the
 * glyph's character and accent color. Cells outside the footprint, and cells
 * skipped by intensity stippling, are left untouched so the orb shows through.
 *
 * The overlay is a pure function: it returns a new [LumosTerminalFrame] and
 * never mutates its input.
 */
object CliGlyph {
    /**
     * CHECK — short `\` stroke meeting a longer `/` stroke at row 3 col 1.
     *
     * ```
     * . . . . /
     * . . . / .
     * \ . / . .
     * . \ . . .
     * . . . . .
     * ```
     */
    val CHECK: CharGrid =
        buildGrid(
            width = 5,
            height = 5,
            rows =
                listOf(
                    "    /",
                    "   / ",
                    "\\ /  ",
                    " \\   ",
                    "     ",
                ),
        )

    /**
     * EXCLAIM — vertical `|` bar with a `.` dot below.
     *
     * ```
     * . | .
     * . | .
     * . | .
     * . . .
     * . . .
     * ```
     */
    val EXCLAIM: CharGrid =
        buildGrid(
            width = 3,
            height = 5,
            rows =
                listOf(
                    " | ",
                    " | ",
                    " | ",
                    "   ",
                    " . ",
                ),
        )

    /**
     * QUESTION — top curve `_/`, right-side hook `\` then `|`, and a `.` dot
     * below.
     *
     * ```
     * _ / . . .
     * . . \ . .
     * . . | . .
     * . . . . .
     * . . . . .
     * ```
     */
    val QUESTION: CharGrid =
        buildGrid(
            width = 5,
            height = 5,
            rows =
                listOf(
                    "_/   ",
                    "  \\  ",
                    "  |  ",
                    "     ",
                    "  .  ",
                ),
        )

    /**
     * HEART — filled `*` heart with two lobes on top and a point at the
     * bottom.
     *
     * ```
     * . * . * .
     * * * * * *
     * * * * * *
     * . * * * .
     * . . * . .
     * ```
     */
    val HEART: CharGrid =
        buildGrid(
            width = 5,
            height = 5,
            rows =
                listOf(
                    " * * ",
                    "*****",
                    "*****",
                    " *** ",
                    "  *  ",
                ),
        )

    /**
     * STAR — five-point asterisk with diagonal `\` and `/` spokes and a `+`
     * at the center.
     *
     * ```
     * . . * . .
     * . \ | / .
     * * - + - *
     * . / | \ .
     * . . * . .
     * ```
     */
    val STAR: CharGrid =
        buildGrid(
            width = 5,
            height = 5,
            rows =
                listOf(
                    "  *  ",
                    " \\|/ ",
                    "*-+-*",
                    " /|\\ ",
                    "  *  ",
                ),
        )

    /**
     * LIGHTNING — zigzag bolt: `\` strokes descending right, then `/` strokes
     * descending left.
     *
     * ```
     * . \ . . .
     * . . \ . .
     * . . / . .
     * . / . . .
     * / . . . .
     * ```
     */
    val LIGHTNING: CharGrid =
        buildGrid(
            width = 5,
            height = 5,
            rows =
                listOf(
                    " \\   ",
                    "  \\  ",
                    "  /  ",
                    " /   ",
                    "/    ",
                ),
        )

    /**
     * Project [frame]'s active glyph (if any) onto the cell grid and return a
     * new frame with the glyph drawn over the orb.
     *
     * Returns [frame] unchanged when there is no glyph, when the glyph name is
     * unknown, when the glyph footprint would not fit on the grid, or when the
     * glyph's fade envelope is fully transparent (intensity 0).
     *
     * At intermediate intensities, cells flip to the glyph based on a
     * deterministic hash of `(x, y, frameNumber)` so animation is stable
     * frame-to-frame and reproducible in tests.
     */
    fun overlay(frame: LumosTerminalFrame): LumosTerminalFrame {
        val glyphState = frame.glyphState ?: return frame
        val glyph = LumosGlyph.entries.firstOrNull { it.name == glyphState.glyphName } ?: return frame
        val intensity = visibilityFromProgress(glyphState.progress)
        if (intensity <= 0f) return frame
        if (frame.width == 0 || frame.height == 0) return frame

        val grid = gridFor(glyph)
        if (grid.width > frame.width || grid.height > frame.height) return frame

        val accent =
            OklabColor.fromSrgb(
                NeutralColor.fromRgba(
                    red = glyphState.red,
                    green = glyphState.green,
                    blue = glyphState.blue,
                ),
            )

        val offsetX = (frame.width - grid.width) / 2
        val offsetY = (frame.height - grid.height) / 2

        val newCells = frame.cells.toMutableList()
        for (gy in 0 until grid.height) {
            for (gx in 0 until grid.width) {
                val gridIndex = gy * grid.width + gx
                if (!grid.mask[gridIndex]) continue

                val fx = offsetX + gx
                val fy = offsetY + gy
                val threshold = stippleThreshold(fx, fy, frame.frameNumber)
                if (intensity < threshold) continue

                val cellIndex = fy * frame.width + fx
                val previous = newCells[cellIndex]
                newCells[cellIndex] =
                    TerminalCell(
                        char = grid.chars[gridIndex],
                        foreground = accent,
                        background = previous.background,
                        bold = previous.bold,
                    )
            }
        }
        return frame.copy(cells = newCells)
    }

    /** Returns the [CharGrid] footprint for [glyph]. */
    fun gridFor(glyph: LumosGlyph): CharGrid =
        when (glyph) {
            LumosGlyph.CHECK -> CHECK
            LumosGlyph.EXCLAIM -> EXCLAIM
            LumosGlyph.QUESTION -> QUESTION
            LumosGlyph.HEART -> HEART
            LumosGlyph.STAR -> STAR
            LumosGlyph.LIGHTNING -> LIGHTNING
        }

    /**
     * Mirrors the fade-in/hold/fade-out envelope from `GlyphLifecycle.visibility`
     * so the overlay's stipple intensity matches the 3D pipeline's voxel-color
     * lerp factor at the same progress value.
     */
    internal fun visibilityFromProgress(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return when {
            p < FADE_IN -> smoothstep(0f, FADE_IN, p)
            p > FADE_OUT -> 1f - smoothstep(FADE_OUT, 1f, p)
            else -> 1f
        }
    }

    /**
     * Deterministic hash of `(x, y, frameNumber)` mapped to `[0, 1]`. Used as
     * the per-cell stipple threshold: at intensity `i`, a cell flips to the
     * glyph when `threshold <= i`. Stable for identical inputs across calls
     * and processes (no RNG state).
     */
    internal fun stippleThreshold(
        x: Int,
        y: Int,
        frameNumber: Long,
    ): Float {
        var h = x * PRIME_X
        h = h xor (y * PRIME_Y)
        h = h xor (frameNumber.toInt() * PRIME_T)
        h = h xor (h ushr 16)
        h *= MURMUR_K1
        h = h xor (h ushr 13)
        h *= MURMUR_K2
        h = h xor (h ushr 16)
        val nonNegative = h and Int.MAX_VALUE
        return nonNegative.toFloat() / Int.MAX_VALUE.toFloat()
    }

    private fun smoothstep(
        edge0: Float,
        edge1: Float,
        x: Float,
    ): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun buildGrid(
        width: Int,
        height: Int,
        rows: List<String>,
    ): CharGrid {
        require(rows.size == height) { "rows.size must equal height" }
        val mask = ArrayList<Boolean>(width * height)
        val chars = ArrayList<Char>(width * height)
        for (row in rows) {
            require(row.length == width) { "each row must be exactly width characters" }
            for (ch in row) {
                mask += ch != ' '
                chars += ch
            }
        }
        return CharGrid(width, height, mask, chars)
    }

    private const val FADE_IN: Float = 0.20f
    private const val FADE_OUT: Float = 0.80f
    private const val PRIME_X: Int = 73856093
    private const val PRIME_Y: Int = 19349663
    private const val PRIME_T: Int = 83492791
    private const val MURMUR_K1: Int = -1640531535
    private const val MURMUR_K2: Int = -1028477387
}
