package link.socket.phosphor.lumos.cli.projection

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.color.OklabColor
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame.TerminalCell
import link.socket.phosphor.lumos.probe.FramePhase
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.lumos.probe.measure

/**
 * Orthographic projection of a VoxelFrame to a [LumosTerminalFrame].
 *
 * Each voxel's lattice position is first rotated by the frame's orb rotation
 * ([VoxelAmbient.orbRotationX], [VoxelAmbient.orbRotationY],
 * [VoxelAmbient.orbRotationZ]; **X first, then Y, then Z**), exactly as
 * ComposeLattice does, so the two surfaces agree on what a frame looks like.
 * The rotated `(x, y)` maps to a character cell, ignoring `z` for placement
 * but using the rotated `z` for occlusion priority (closer voxels win the
 * cell). A frame whose rotation is zero projects its recorded positions
 * unchanged. Zero-scale voxels (those thinned away by bipolar boundary
 * compression or glyph carving) are skipped, so empty cells remain empty
 * instead of being overpainted by ghost cubes.
 *
 * Orthographic, not perspective: at typical terminal resolutions (40x20 to
 * 60x30) foreshortening barely reads and the simpler projection halves the
 * code volume. Color quantization is deliberately not done here — the
 * winning voxel's sRGB triplet is forwarded as OklabColor for the renderer
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
 * @param probe Per-phase timing sink. Defaults to FrameProbe.Disabled, which
 *  adds no per-frame cost; pass a RingBufferFrameProbe to capture PROJECT timings.
 */
class CliLattice(
    val width: Int,
    val height: Int,
    val characterAspectRatio: Float = 2.0f,
    private val probe: FrameProbe = FrameProbe.Disabled,
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
     * 1. For every voxel with nonzero scale, apply Euler XYZ rotation
     *    (X → Y → Z) using angles from [frame]'s ambient state, then compute
     *    screen `(x, y)` via orthographic mapping of the rotated position.
     * 2. Per cell, keep the voxel with the largest rotated `z` (closest to camera).
     * 3. Map each winning voxel's scale to a luminance-ramp character and
     *    pass its sRGB color through as OklabColor.
     * 4. Empty cells become transparent blanks.
     *
     * Ambient and glyph state pass through unchanged.
     */
    fun project(frame: VoxelFrame): LumosTerminalFrame = probe.measure(FramePhase.PROJECT) { projectFrame(frame) }

    private fun projectFrame(frame: VoxelFrame): LumosTerminalFrame {
        val winningIndex = IntArray(cellCount) { -1 }
        val winningZ = FloatArray(cellCount)
        val cells = frame.cells
        val ambient = frame.ambient
        val invAspect = 1f / characterAspectRatio

        val cosX = cos(ambient.orbRotationX.toDouble()).toFloat()
        val sinX = sin(ambient.orbRotationX.toDouble()).toFloat()
        val cosY = cos(ambient.orbRotationY.toDouble()).toFloat()
        val sinY = sin(ambient.orbRotationY.toDouble()).toFloat()
        val cosZ = cos(ambient.orbRotationZ.toDouble()).toFloat()
        val sinZ = sin(ambient.orbRotationZ.toDouble()).toFloat()

        for (i in cells.indices) {
            val cell = cells[i]
            if (cell.scale <= 0f) continue

            // Rotate around X axis
            val x1 = cell.x
            val y1 = cell.y * cosX - cell.z * sinX
            val z1 = cell.y * sinX + cell.z * cosX

            // Rotate around Y axis
            val x2 = x1 * cosY + z1 * sinY
            val y2 = y1
            val z2 = -x1 * sinY + z1 * cosY

            // Rotate around Z axis
            val rx = x2 * cosZ - y2 * sinZ
            val ry = x2 * sinZ + y2 * cosZ
            val rz = z2

            val screenXf = (rx + 1f) * 0.5f * width
            val screenYf = (1f - (ry + 1f) * 0.5f) * height * invAspect

            val sx = screenXf.toInt()
            val sy = screenYf.toInt()
            if (sx < 0 || sx >= width || sy < 0 || sy >= height) continue

            val idx = sy * width + sx
            if (winningIndex[idx] < 0 || rz > winningZ[idx]) {
                winningIndex[idx] = i
                winningZ[idx] = rz
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
