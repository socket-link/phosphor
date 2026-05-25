package link.socket.phosphor.lumos.cli.frame

import kotlinx.serialization.Serializable
import link.socket.phosphor.color.OklabColor
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelGlyphState

/**
 * Projected terminal-space representation of a Lumos voxel frame.
 *
 * The cells are row-major and must fill the entire character grid. This DTO is
 * intentionally separate from Phosphor core's terminal frame shape because
 * Lumos projection carries voxel-specific ambient and glyph state.
 */
@Serializable
data class LumosTerminalFrame(
    val width: Int,
    val height: Int,
    val cells: List<TerminalCell>,
    val ambient: VoxelAmbient?,
    val glyphState: VoxelGlyphState?,
    val frameNumber: Long,
) {
    init {
        require(width >= 0) { "width must be >= 0" }
        require(height >= 0) { "height must be >= 0" }

        val expectedCellCount = width.toLong() * height.toLong()
        require(expectedCellCount <= Int.MAX_VALUE) {
            "width * height must be <= Int.MAX_VALUE"
        }
        require(cells.size == expectedCellCount.toInt()) {
            "cells size must equal width * height"
        }
    }

    /**
     * One terminal character cell in a projected Lumos frame.
     *
     * Null colors are transparent to the CLI renderer: no foreground or
     * background escape sequence should be emitted for that channel.
     */
    @Serializable
    data class TerminalCell(
        val char: Char = ' ',
        val foreground: OklabColor? = null,
        val background: OklabColor? = null,
        val bold: Boolean = false,
    )
}
