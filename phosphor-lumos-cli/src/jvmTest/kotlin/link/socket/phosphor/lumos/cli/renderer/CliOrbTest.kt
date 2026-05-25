package link.socket.phosphor.lumos.cli.renderer

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import link.socket.phosphor.color.AnsiColorMode
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.color.OklabColor
import link.socket.phosphor.lumos.LumosRenderTarget
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame.TerminalCell

class CliOrbTest {
    @Test
    fun `target and preferredFps expose terminal-render metadata`() {
        val orb = CliOrb(out = PrintStream(ByteArrayOutputStream()), targetFps = 24)
        assertEquals(LumosRenderTarget.VOXEL_TERMINAL, orb.target)
        assertEquals(24, orb.preferredFps)
    }

    @Test
    fun `constructor rejects non-positive fps`() {
        assertFails {
            CliOrb(out = PrintStream(ByteArrayOutputStream()), targetFps = 0)
        }
    }

    @Test
    fun `constructor rejects negative resize poll interval`() {
        assertFails {
            CliOrb(out = PrintStream(ByteArrayOutputStream()), resizePollFrames = -1)
        }
    }

    @Test
    fun `FULL render of a 5x3 frame with one red cell emits cursor hide, clear, and the colored character`() {
        val buffer = ByteArrayOutputStream()
        val orb =
            CliOrb(
                out = PrintStream(buffer),
                colorMode = AnsiColorMode.TRUECOLOR,
                clearMode = ClearMode.FULL,
                terminalSize = TerminalSize.fixed(columns = 20, rows = 10),
                resizePollFrames = 0,
            )
        val cells = MutableList(5 * 3) { TerminalCell() }
        cells[1 * 5 + 2] = TerminalCell(char = 'R', foreground = RED)
        val frame = frameOf(width = 5, height = 3, cells = cells)

        orb.render(frame)

        val out = buffer.toString(Charsets.UTF_8)
        assertTrue(out.startsWith(CURSOR_HIDE + CLEAR_SCREEN), "must start with hide+clear: $out")
        // Row 2 cursor parking is emitted before the red cell character; the
        // cursor walks the row left-to-right and prints R at column 3.
        assertTrue(out.contains("$ESC[2;1H"), "must position cursor at start of red cell row: $out")
        assertTrue(out.contains("$ESC[38;2;255;0;0m"), "must emit truecolor fg for red: $out")
        assertTrue(out.contains('R'), "must emit the red cell's character: $out")
    }

    @Test
    fun `run-length optimization emits exactly one fg escape for an all-red frame`() {
        val buffer = ByteArrayOutputStream()
        val orb =
            CliOrb(
                out = PrintStream(buffer),
                colorMode = AnsiColorMode.TRUECOLOR,
                clearMode = ClearMode.FULL,
                terminalSize = TerminalSize.fixed(columns = 20, rows = 10),
                resizePollFrames = 0,
            )
        val cells = List(4 * 2) { TerminalCell(char = '#', foreground = RED) }
        val frame = frameOf(width = 4, height = 2, cells = cells)

        orb.render(frame)

        val out = buffer.toString(Charsets.UTF_8)
        val fgCount = countOccurrences(out, "$ESC[38;2;")
        assertEquals(1, fgCount, "expected exactly one fg escape, got $fgCount in: $out")
    }

    @Test
    fun `DIFFERENTIAL mode produces no cell writes when frame is unchanged`() {
        val buffer = ByteArrayOutputStream()
        val orb =
            CliOrb(
                out = PrintStream(buffer),
                colorMode = AnsiColorMode.TRUECOLOR,
                clearMode = ClearMode.DIFFERENTIAL,
                terminalSize = TerminalSize.fixed(columns = 20, rows = 10),
                resizePollFrames = 0,
            )
        val cells = List(3 * 2) { TerminalCell(char = '*', foreground = RED) }
        val frame = frameOf(width = 3, height = 2, cells = cells)

        orb.render(frame)
        buffer.reset()
        orb.render(frame)

        val out = buffer.toString(Charsets.UTF_8)
        // Second render must not emit the cell character or any fg escape.
        assertFalse(out.contains('*'), "unchanged frame should not rewrite cells: $out")
        assertFalse(out.contains("$ESC[38;2;"), "unchanged frame should not re-emit color: $out")
        // Cursor parking is allowed.
        assertTrue(out.contains("$ESC["), "cursor parking escape should still be emitted: $out")
    }

    @Test
    fun `DIFFERENTIAL mode emits only the changed cells on the second frame`() {
        val buffer = ByteArrayOutputStream()
        val orb =
            CliOrb(
                out = PrintStream(buffer),
                colorMode = AnsiColorMode.TRUECOLOR,
                clearMode = ClearMode.DIFFERENTIAL,
                terminalSize = TerminalSize.fixed(columns = 20, rows = 10),
                resizePollFrames = 0,
            )
        val base = List(4 * 2) { TerminalCell(char = '.', foreground = RED) }
        orb.render(frameOf(width = 4, height = 2, cells = base))
        buffer.reset()

        val updated = base.toMutableList()
        updated[1 * 4 + 2] = TerminalCell(char = 'X', foreground = RED)
        orb.render(frameOf(width = 4, height = 2, cells = updated, frameNumber = 1))

        val out = buffer.toString(Charsets.UTF_8)
        assertTrue(out.contains("$ESC[2;3H"), "should position cursor at the changed cell: $out")
        assertTrue(out.contains('X'), "should emit the changed cell character: $out")
        assertFalse(out.contains('.'), "unchanged cells should not re-emit their character: $out")
    }

    @Test
    fun `start registers a shutdown hook that emits cursor show on stop`() {
        val buffer = ByteArrayOutputStream()
        val orb =
            CliOrb(
                out = PrintStream(buffer),
                terminalSize = TerminalSize.fixed(columns = 20, rows = 10),
                resizePollFrames = 0,
            )
        val frame = frameOf(width = 2, height = 2, cells = List(4) { TerminalCell() })

        orb.start()
        orb.render(frame)
        buffer.reset()
        // Simulate the shutdown hook firing.
        orb.stop()

        val out = buffer.toString(Charsets.UTF_8)
        assertTrue(out.contains(CURSOR_SHOW), "stop must emit cursor-show: $out")
        assertTrue(out.contains(CLEAR_SCREEN), "stop must clear the orb region: $out")
    }

    @Test
    fun `render is a no-op after stop`() {
        val buffer = ByteArrayOutputStream()
        val orb =
            CliOrb(
                out = PrintStream(buffer),
                terminalSize = TerminalSize.fixed(columns = 20, rows = 10),
                resizePollFrames = 0,
            )
        val frame = frameOf(width = 2, height = 2, cells = List(4) { TerminalCell(char = 'A') })
        orb.render(frame)
        orb.stop()
        buffer.reset()

        orb.render(frame)

        val out = buffer.toString(Charsets.UTF_8)
        assertEquals("", out, "render must be a no-op after stop, got: $out")
    }

    @Test
    fun `resize polling forces a full clear and redraw on next frame`() {
        val buffer = ByteArrayOutputStream()
        val sizes = mutableListOf(TerminalSize.Dimensions(20, 10), TerminalSize.Dimensions(40, 12))
        val provider =
            object : TerminalSize {
                private var index = 0

                override fun current(): TerminalSize.Dimensions {
                    val dim = sizes[index.coerceAtMost(sizes.lastIndex)]
                    if (index < sizes.lastIndex) index++
                    return dim
                }
            }
        val orb =
            CliOrb(
                out = PrintStream(buffer),
                colorMode = AnsiColorMode.TRUECOLOR,
                clearMode = ClearMode.DIFFERENTIAL,
                terminalSize = provider,
                resizePollFrames = 1,
            )
        val cells = List(3 * 2) { TerminalCell(char = '.', foreground = RED) }
        val frame = frameOf(width = 3, height = 2, cells = cells)

        orb.render(frame)
        buffer.reset()
        // Next render polls and sees a new size; must clear and full-redraw.
        orb.render(frame)

        val out = buffer.toString(Charsets.UTF_8)
        assertTrue(out.contains(CLEAR_SCREEN), "resize must trigger a clear: $out")
        assertEquals(
            cells.size,
            countOccurrences(out, "."),
            "every cell must be re-emitted after a resize: $out",
        )
    }

    @Test
    fun `render of VoxelFrame without a lattice fails fast`() {
        val orb = CliOrb(out = PrintStream(ByteArrayOutputStream()))
        assertFails { orb.render(MINIMAL_VOXEL_FRAME) }
    }

    private fun frameOf(
        width: Int,
        height: Int,
        cells: List<TerminalCell>,
        frameNumber: Long = 0L,
    ): LumosTerminalFrame =
        LumosTerminalFrame(
            width = width,
            height = height,
            cells = cells,
            ambient = null,
            glyphState = null,
            frameNumber = frameNumber,
        )

    private fun countOccurrences(
        haystack: String,
        needle: String,
    ): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    companion object {
        private const val ESC: String = ""
        private const val CURSOR_HIDE: String = "$ESC[?25l"
        private const val CURSOR_SHOW: String = "$ESC[?25h"
        private const val CLEAR_SCREEN: String = "$ESC[2J"

        private val RED: OklabColor = OklabColor.fromSrgb(NeutralColor.fromRgba(1f, 0f, 0f))

        private val MINIMAL_VOXEL_FRAME: link.socket.phosphor.lumos.VoxelFrame =
            link.socket.phosphor.lumos.VoxelFrame(
                tick = 0L,
                timestampEpochMillis = 0L,
                resolution = 4,
                cells = emptyList(),
                ambient =
                    link.socket.phosphor.lumos.VoxelAmbient(
                        glowRed = 0f,
                        glowGreen = 0f,
                        glowBlue = 0f,
                        glowIntensity = 0f,
                        orbRotationX = 0f,
                        orbRotationY = 0f,
                        orbRotationZ = 0f,
                    ),
            )
    }
}
