package link.socket.phosphor.renderer

import kotlinx.serialization.Serializable
import link.socket.phosphor.render.AsciiCell
import link.socket.phosphor.render.CellBuffer

/**
 * Serializable snapshot for one render tick.
 */
@Serializable
data class SimulationFrame(
    val tick: Long,
    val timestampEpochMillis: Long,
    val width: Int,
    val height: Int,
    val cells: List<FrameCell>,
    val metadata: Map<String, Float> = emptyMap(),
) {
    init {
        require(width >= 0) { "width must be >= 0, got $width" }
        require(height >= 0) { "height must be >= 0, got $height" }
        require(cells.size == width * height) {
            "cells size (${cells.size}) must equal width * height (${width * height})"
        }
    }

    fun cellAt(
        row: Int,
        col: Int,
    ): FrameCell {
        require(row in 0 until height) { "row out of bounds: $row" }
        require(col in 0 until width) { "col out of bounds: $col" }
        return cells[(row * width) + col]
    }

    fun toCellBuffer(): CellBuffer {
        val buffer = CellBuffer(width, height)

        for (row in 0 until height) {
            for (col in 0 until width) {
                buffer[row, col] = cellAt(row, col).toAsciiCell()
            }
        }

        return buffer
    }

    companion object {
        fun fromCellBuffer(
            tick: Long,
            timestampEpochMillis: Long,
            buffer: CellBuffer,
            metadata: Map<String, Float> = emptyMap(),
            luminance: FloatArray? = null,
            normalX: FloatArray? = null,
            normalY: FloatArray? = null,
        ): SimulationFrame {
            val expectedSize = buffer.width * buffer.height
            validateComponentSize("luminance", expectedSize, luminance)
            validateComponentSize("normalX", expectedSize, normalX)
            validateComponentSize("normalY", expectedSize, normalY)

            val cells = ArrayList<FrameCell>(expectedSize)

            for (row in 0 until buffer.height) {
                for (col in 0 until buffer.width) {
                    val index = (row * buffer.width) + col
                    val cell = buffer[row, col]
                    cells +=
                        FrameCell(
                            char = cell.char,
                            fgColor = cell.fgColor,
                            bgColor = cell.bgColor,
                            bold = cell.bold,
                            luminance = luminance?.get(index),
                            normalX = normalX?.get(index),
                            normalY = normalY?.get(index),
                        )
                }
            }

            return SimulationFrame(
                tick = tick,
                timestampEpochMillis = timestampEpochMillis,
                width = buffer.width,
                height = buffer.height,
                cells = cells,
                metadata = metadata,
            )
        }

        private fun validateComponentSize(
            componentName: String,
            expectedSize: Int,
            values: FloatArray?,
        ) {
            if (values == null) return
            require(values.size == expectedSize) {
                "$componentName size (${values.size}) must equal width * height ($expectedSize)"
            }
        }
    }
}

@Serializable
data class FrameCell(
    val char: Char = ' ',
    val fgColor: Int = 7,
    val bgColor: Int? = null,
    val bold: Boolean = false,
    val luminance: Float? = null,
    val normalX: Float? = null,
    val normalY: Float? = null,
) {
    fun toAsciiCell(): AsciiCell =
        AsciiCell(
            char = char,
            fgColor = fgColor,
            bgColor = bgColor,
            bold = bold,
        )
}
