package link.socket.phosphor.renderer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import link.socket.phosphor.render.AsciiCell
import link.socket.phosphor.render.CellBuffer

class SimulationFrameTest {
    @Test
    fun `fromCellBuffer and toCellBuffer round-trip`() {
        val buffer = CellBuffer(2, 2)
        buffer[0, 0] = AsciiCell(char = 'A', fgColor = 196, bold = true)
        buffer[0, 1] = AsciiCell(char = 'B', fgColor = 46, bgColor = 232)
        buffer[1, 0] = AsciiCell(char = '.', fgColor = 250)

        val frame =
            SimulationFrame.fromCellBuffer(
                tick = 7L,
                timestampEpochMillis = 123_456L,
                buffer = buffer,
                metadata = mapOf("deltaTime" to 0.016f),
            )

        val roundTripped = frame.toCellBuffer()

        assertEquals(2, frame.width)
        assertEquals(2, frame.height)
        assertEquals(4, frame.cells.size)
        assertEquals(AsciiCell(char = 'A', fgColor = 196, bold = true), roundTripped[0, 0])
        assertEquals(AsciiCell(char = 'B', fgColor = 46, bgColor = 232), roundTripped[0, 1])
        assertEquals(AsciiCell(char = '.', fgColor = 250), roundTripped[1, 0])
        assertEquals(AsciiCell.EMPTY, roundTripped[1, 1])
    }

    @Test
    fun `fromCellBuffer attaches optional surface components`() {
        val buffer = CellBuffer(1, 2)
        buffer[0, 0] = AsciiCell(char = 'x', fgColor = 10)
        buffer[1, 0] = AsciiCell(char = 'y', fgColor = 11)

        val frame =
            SimulationFrame.fromCellBuffer(
                tick = 1L,
                timestampEpochMillis = 2L,
                buffer = buffer,
                luminance = floatArrayOf(0.2f, 0.8f),
                normalX = floatArrayOf(-0.5f, 0.5f),
                normalY = floatArrayOf(1f, 1f),
            )

        assertEquals(0.2f, frame.cellAt(0, 0).luminance)
        assertEquals(0.8f, frame.cellAt(1, 0).luminance)
        assertEquals(-0.5f, frame.cellAt(0, 0).normalX)
        assertEquals(1f, frame.cellAt(1, 0).normalY)
    }

    @Test
    fun `fromCellBuffer rejects mismatched component lengths`() {
        val buffer = CellBuffer(2, 2)

        val error =
            assertFailsWith<IllegalArgumentException> {
                SimulationFrame.fromCellBuffer(
                    tick = 1L,
                    timestampEpochMillis = 2L,
                    buffer = buffer,
                    luminance = floatArrayOf(0.1f),
                )
            }

        assertNotNull(error.message)
    }

    @Test
    fun `SimulationFrame supports JSON serialization`() {
        val frame =
            SimulationFrame(
                tick = 42L,
                timestampEpochMillis = 99_999L,
                width = 1,
                height = 2,
                cells =
                    listOf(
                        FrameCell(char = 'H', fgColor = 45, luminance = 0.5f),
                        FrameCell(char = 'i', fgColor = 46, bold = true, normalX = 0.2f, normalY = 0.8f),
                    ),
                metadata = mapOf("fps" to 60f),
            )

        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(SimulationFrame.serializer(), frame)
        val decoded = json.decodeFromString(SimulationFrame.serializer(), encoded)

        assertEquals(frame, decoded)
    }
}
