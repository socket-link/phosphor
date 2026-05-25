package link.socket.phosphor.lumos.cli.projection

import kotlin.math.roundToInt
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.color.OklabColor
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame.TerminalCell

/**
 * Orthographic projection of a [VoxelFrame] to a [LumosTerminalFrame].
 *
 * Each voxel's `(x, y)` lattice position maps to a character cell, ignoring
 * `z` for placement but using `z` for occlusion priority (closer voxels win
 * the cell). Zero-scale voxels (those thinned away by bipolar boundary
 * compression or glyph carving) are skipped, so empty cells remain empty
 * instead of being overpainted by ghost cubes.
 *
 * Orthographic, not perspective: at typical terminal resolutions (40x20 to
 * 60x30) foreshortening barely reads and the simpler projection halves the
 * code volume. Color quantization is deliberately not done here — the
 * winning voxel's sRGB triplet is forwarded as [OklabColor] for the renderer
 * (CliOrb / AnsiColorMap) to quantize.
 *
 * The projection is pure: it allocates one [LumosTerminalFrame] plus two
 * working buffers sized `width * height` per call, and is safe to share
 * across threads as long as each call's [project] is isolated.
 *
 * @param width Output grid width in character cells; must be > 0.
 * @param height Output grid height in character cells; must be > 0.
 * @param characterAspectRatio Ratio of character cell height to width.
 *  Defaults to 2.0 because most monospace fonts render characters at ~1:2
 *  width:height. Configurable for high-DPI terminals or custom fonts.
 */
class CliLattice(
    val width: Int,
    val height: Int,
    val characterAspectRatio: Float = 2.0f,
) {
    init {
        require(width > 0) { "width must be > 0, got $width" }
        require(height > 0) { "height must be > 0, got $height" }
        require(characterAspectRatio > 0f) {
            "characterAspectRatio must be > 0, got $characterAspectRatio"
        }
    }

    private val cellCount: Int = width * height

    /**
     * Project [frame] to a terminal-cell grid.
     *
     * Algorithm:
     * 1. For every voxel with nonzero scale, compute screen `(x, y)` via
     *    orthographic mapping of normalized lattice position.
     * 2. Per cell, keep the voxel with the largest `z` (closest to camera).
     * 3. Map each winning voxel's scale to a luminance-ramp character and
     *    pass its sRGB color through as [OklabColor].
     * 4. Empty cells become transparent blanks.
     *
     * Ambient and glyph state pass through unchanged.
     */
    fun project(frame: VoxelFrame): LumosTerminalFrame {
        val winningIndex = IntArray(cellCount) { -1 }
        val winningZ = FloatArray(cellCount)
        val cells = frame.cells
        val invAspect = 1f / characterAspectRatio

        for (i in cells.indices) {
            val cell = cells[i]
            if (cell.scale <= 0f) continue

            val screenXf = (cell.x + 1f) * 0.5f * width
            val screenYf = (1f - (cell.y + 1f) * 0.5f) * height * invAspect

            val sx = screenXf.toInt()
            val sy = screenYf.toInt()
            if (sx < 0 || sx >= width || sy < 0 || sy >= height) continue

            val idx = sy * width + sx
            if (winningIndex[idx] < 0 || cell.z > winningZ[idx]) {
                winningIndex[idx] = i
                winningZ[idx] = cell.z
            }
        }

        val out = ArrayList<TerminalCell>(cellCount)
        for (idx in 0 until cellCount) {
            val winner = winningIndex[idx]
            if (winner < 0) {
                out += EMPTY_CELL
            } else {
                val v = cells[winner]
                val char = luminanceChar(v.scale)
                val color = OklabColor.fromSrgb(NeutralColor.fromRgba(v.red, v.green, v.blue))
                out += TerminalCell(char = char, foreground = color)
            }
        }

        return LumosTerminalFrame(
            width = width,
            height = height,
            cells = out,
            ambient = frame.ambient,
            glyphState = frame.glyph,
            frameNumber = frame.tick,
        )
    }

    companion object {
        /**
         * Ten-step luminance ramp from densest to lightest. Index 0 (`@`)
         * is full-scale; index 9 (space) is zero-scale.
         */
        const val LUMINANCE_RAMP: String = "@%#*+=-:. "

        private val EMPTY_CELL = TerminalCell(char = ' ', foreground = null, background = null)

        /**
         * Map a voxel `scale` in 0..1 to a character on [LUMINANCE_RAMP].
         *
         * Scale clamps to 0..1 so out-of-range inputs still produce a valid
         * ramp character. `1.0` returns `@`, `0.0` returns space, midpoints
         * land near the middle of the ramp.
         */
        fun luminanceChar(scale: Float): Char {
            val clamped = scale.coerceIn(0f, 1f)
            val maxIndex = LUMINANCE_RAMP.length - 1
            val index = ((1f - clamped) * maxIndex).roundToInt().coerceIn(0, maxIndex)
            return LUMINANCE_RAMP[index]
        }
    }
}
