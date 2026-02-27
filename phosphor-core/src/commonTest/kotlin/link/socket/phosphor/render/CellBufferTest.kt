package link.socket.phosphor.render

import kotlin.test.Test
import kotlin.test.assertEquals

class CellBufferTest {
    @Test
    fun `construction creates empty buffer`() {
        val buffer = CellBuffer(10, 5)
        assertEquals(10, buffer.width)
        assertEquals(5, buffer.height)
        assertEquals(AsciiCell.EMPTY, buffer[0, 0])
    }

    @Test
    fun `get and set work correctly`() {
        val buffer = CellBuffer(3, 3)
        val cell = AsciiCell(char = '#', fgColor = 255)
        buffer[1, 2] = cell
        assertEquals(cell, buffer[1, 2])
        assertEquals(AsciiCell.EMPTY, buffer[0, 0])
    }

    @Test
    fun `clear resets all cells`() {
        val buffer = CellBuffer(2, 2)
        buffer[0, 0] = AsciiCell(char = 'X', fgColor = 1)
        buffer[1, 1] = AsciiCell(char = 'Y', fgColor = 2)
        buffer.clear()
        assertEquals(AsciiCell.EMPTY, buffer[0, 0])
        assertEquals(AsciiCell.EMPTY, buffer[1, 1])
    }

    @Test
    fun `from factory copies grid`() {
        val grid = Array(2) { r -> Array(3) { c -> AsciiCell(char = ('A' + r * 3 + c), fgColor = 0) } }
        val buffer = CellBuffer.from(grid)
        assertEquals(3, buffer.width)
        assertEquals(2, buffer.height)
        assertEquals('A', buffer[0, 0].char)
        assertEquals('F', buffer[1, 2].char)
    }
}
