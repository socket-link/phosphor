package link.socket.phosphor.render

/**
 * A 2D buffer of [AsciiCell]s — the universal interchange format between
 * rendering logic and surface adapters.
 *
 * Replaces raw `Array<Array<AsciiCell>>` with a type-safe container that
 * carries its own dimensions and provides indexed access.
 */
class CellBuffer(val width: Int, val height: Int) {
    private val cells: Array<Array<AsciiCell>> = Array(height) { Array(width) { AsciiCell.EMPTY } }

    operator fun get(
        row: Int,
        col: Int,
    ): AsciiCell = cells[row][col]

    operator fun set(
        row: Int,
        col: Int,
        cell: AsciiCell,
    ) {
        cells[row][col] = cell
    }

    fun row(index: Int): Array<AsciiCell> = cells[index]

    fun rows(): Array<Array<AsciiCell>> = cells

    fun clear() {
        for (r in cells) r.fill(AsciiCell.EMPTY)
    }

    companion object {
        fun from(grid: Array<Array<AsciiCell>>): CellBuffer {
            val h = grid.size
            val w = if (h > 0) grid[0].size else 0
            val buffer = CellBuffer(w, h)
            for (r in 0 until h) {
                for (c in 0 until w) {
                    buffer[r, c] = grid[r][c]
                }
            }
            return buffer
        }
    }
}
