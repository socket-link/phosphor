package link.socket.phosphor.lumos.cli.glyph

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.color.OklabColor
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.lumos.VoxelGlyphState
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame.TerminalCell

class CliGlyphTest {
    @Test
    fun `CharGrid centre-only mask validates correctly`() {
        val grid = CharGrid(3, 3, List(9) { it == 4 })
        assertEquals(9, grid.mask.size)
        assertTrue(grid.mask[4])
        assertEquals(8, grid.mask.count { !it })
    }

    @Test
    fun `CharGrid rejects mask size mismatch`() {
        assertFailsWith<IllegalArgumentException> {
            CharGrid(width = 3, height = 3, mask = List(8) { false })
        }
    }

    @Test
    fun `every canonical glyph constant has consistent dimensions`() {
        for (glyph in LumosGlyph.entries) {
            val grid = CliGlyph.gridFor(glyph)
            assertEquals(
                grid.width * grid.height,
                grid.mask.size,
                "${glyph.name} mask size",
            )
            assertEquals(
                grid.width * grid.height,
                grid.chars.size,
                "${glyph.name} chars size",
            )
            assertTrue(grid.mask.any { it }, "${glyph.name} must mark at least one cell")
        }
    }

    @Test
    fun `overlay returns same frame when glyph state is null`() {
        val frame = blankFrame(width = 10, height = 6, glyphState = null)
        val result = CliGlyph.overlay(frame)
        assertSame(frame, result)
    }

    @Test
    fun `overlay returns same frame when glyph name is unknown`() {
        val frame =
            blankFrame(
                width = 10,
                height = 6,
                glyphState = glyphState(name = "MYSTERY", progress = 0.5f),
            )
        val result = CliGlyph.overlay(frame)
        assertSame(frame, result)
    }

    @Test
    fun `intensity zero leaves the orb untouched`() {
        val frame =
            blankFrame(
                width = 10,
                height = 6,
                glyphState = glyphState(name = LumosGlyph.CHECK.name, progress = 0f),
            )
        val result = CliGlyph.overlay(frame)
        assertEquals(frame.cells, result.cells)
    }

    @Test
    fun `glyph fully present at hold-phase intensity`() {
        val frame =
            blankFrame(
                width = 11,
                height = 9,
                glyphState = glyphState(name = LumosGlyph.CHECK.name, progress = 0.5f),
            )

        val result = CliGlyph.overlay(frame)

        val grid = CliGlyph.CHECK
        val offsetX = (frame.width - grid.width) / 2
        val offsetY = (frame.height - grid.height) / 2
        val expectedAccent = expectedAccent(LumosGlyph.CHECK)

        for (gy in 0 until grid.height) {
            for (gx in 0 until grid.width) {
                val gridIndex = gy * grid.width + gx
                val cellIndex = (offsetY + gy) * frame.width + (offsetX + gx)
                val cell = result.cells[cellIndex]
                if (grid.mask[gridIndex]) {
                    assertEquals(grid.chars[gridIndex], cell.char)
                    val foreground = cell.foreground
                    assertNotNull(foreground)
                    assertOklabClose(expectedAccent, foreground)
                } else {
                    assertEquals(frame.cells[cellIndex], cell)
                }
            }
        }
    }

    @Test
    fun `cells outside the glyph footprint are unchanged at full intensity`() {
        val frame =
            blankFrame(
                width = 12,
                height = 8,
                glyphState = glyphState(name = LumosGlyph.HEART.name, progress = 0.5f),
            )

        val result = CliGlyph.overlay(frame)
        val grid = CliGlyph.HEART
        val offsetX = (frame.width - grid.width) / 2
        val offsetY = (frame.height - grid.height) / 2

        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val insideX = x in offsetX until (offsetX + grid.width)
                val insideY = y in offsetY until (offsetY + grid.height)
                if (insideX && insideY) continue
                val index = y * frame.width + x
                assertEquals(frame.cells[index], result.cells[index])
            }
        }
    }

    @Test
    fun `overlay is deterministic for the same input`() {
        val frame =
            blankFrame(
                width = 12,
                height = 8,
                glyphState = glyphState(name = LumosGlyph.STAR.name, progress = 0.10f),
                frameNumber = 42L,
            )
        val a = CliGlyph.overlay(frame)
        val b = CliGlyph.overlay(frame)
        assertEquals(a.cells, b.cells)
    }

    @Test
    fun `partial intensity stipples a fraction of cells in the footprint`() {
        val width = 12
        val height = 8
        val frame =
            blankFrame(
                width = width,
                height = height,
                glyphState = glyphState(name = LumosGlyph.HEART.name, progress = 0.10f),
                frameNumber = 7L,
            )
        val result = CliGlyph.overlay(frame)

        val grid = CliGlyph.HEART
        val offsetX = (width - grid.width) / 2
        val offsetY = (height - grid.height) / 2
        var filled = 0
        var unchanged = 0
        var footprint = 0
        for (gy in 0 until grid.height) {
            for (gx in 0 until grid.width) {
                if (!grid.mask[gy * grid.width + gx]) continue
                footprint++
                val cellIndex = (offsetY + gy) * width + (offsetX + gx)
                if (result.cells[cellIndex] == frame.cells[cellIndex]) {
                    unchanged++
                } else {
                    filled++
                }
            }
        }
        assertTrue(filled in 1 until footprint, "expected partial fill, got $filled of $footprint")
        assertTrue(unchanged in 1 until footprint, "expected some untouched cells, got $unchanged")
    }

    @Test
    fun `overlay does not mutate the input frame`() {
        val frame =
            blankFrame(
                width = 11,
                height = 9,
                glyphState = glyphState(name = LumosGlyph.EXCLAIM.name, progress = 0.5f),
            )
        val before = frame.cells.toList()
        val result = CliGlyph.overlay(frame)
        assertEquals(before, frame.cells)
        assertNotSame(frame.cells, result.cells)
    }

    @Test
    fun `overlay returns same frame when grid does not fit`() {
        val frame =
            blankFrame(
                width = 2,
                height = 2,
                glyphState = glyphState(name = LumosGlyph.HEART.name, progress = 0.5f),
            )
        val result = CliGlyph.overlay(frame)
        assertSame(frame, result)
    }

    @Test
    fun `visibility matches GlyphLifecycle envelope at key progress values`() {
        assertEquals(0f, CliGlyph.visibilityFromProgress(0f))
        assertEquals(1f, CliGlyph.visibilityFromProgress(0.20f))
        assertEquals(1f, CliGlyph.visibilityFromProgress(0.50f))
        assertEquals(1f, CliGlyph.visibilityFromProgress(0.80f))
        assertEquals(0f, CliGlyph.visibilityFromProgress(1f))
    }

    @Test
    fun `stipple threshold is stable and well-bounded`() {
        for (seed in 0 until 100) {
            val t = CliGlyph.stippleThreshold(seed % 17, seed % 11, seed.toLong())
            assertTrue(t in 0f..1f, "threshold out of range for seed=$seed: $t")
        }
        assertEquals(
            CliGlyph.stippleThreshold(3, 4, 5L),
            CliGlyph.stippleThreshold(3, 4, 5L),
        )
    }

    @Test
    fun `snapshot — CHECK at hold intensity`() {
        assertSnapshot(
            glyph = LumosGlyph.CHECK,
            expected =
                """
                |.........
                |.........
                |....../..
                |...../...
                |..\./....
                |...\.....
                |.........
                |.........
                |.........
                """.trimMargin(),
        )
    }

    @Test
    fun `snapshot — EXCLAIM at hold intensity`() {
        assertSnapshot(
            glyph = LumosGlyph.EXCLAIM,
            expected =
                """
                |.........
                |.........
                |....|....
                |....|....
                |....|....
                |.........
                |.........
                |.........
                |.........
                """.trimMargin(),
            // EXCLAIM is 3x5; column-7 cell (`.`) at row 6 col 4 is the dot.
            extraAssert = { result ->
                assertEquals('.', result.cells[6 * 9 + 4].char)
            },
        )
    }

    @Test
    fun `snapshot — QUESTION at hold intensity`() {
        assertSnapshot(
            glyph = LumosGlyph.QUESTION,
            expected =
                """
                |.........
                |.........
                |.._/.....
                |....\....
                |....|....
                |.........
                |.........
                |.........
                |.........
                """.trimMargin(),
            extraAssert = { result ->
                assertEquals('.', result.cells[6 * 9 + 4].char)
            },
        )
    }

    @Test
    fun `snapshot — HEART at hold intensity`() {
        assertSnapshot(
            glyph = LumosGlyph.HEART,
            expected =
                """
                |.........
                |.........
                |...*.*...
                |..*****..
                |..*****..
                |...***...
                |....*....
                |.........
                |.........
                """.trimMargin(),
        )
    }

    @Test
    fun `snapshot — STAR at hold intensity`() {
        assertSnapshot(
            glyph = LumosGlyph.STAR,
            expected =
                """
                |.........
                |.........
                |....*....
                |...\|/...
                |..*-+-*..
                |.../|\...
                |....*....
                |.........
                |.........
                """.trimMargin(),
        )
    }

    @Test
    fun `snapshot — LIGHTNING at hold intensity`() {
        assertSnapshot(
            glyph = LumosGlyph.LIGHTNING,
            expected =
                """
                |.........
                |.........
                |...\.....
                |....\....
                |..../....
                |.../.....
                |../......
                |.........
                |.........
                """.trimMargin(),
        )
    }

    private fun assertSnapshot(
        glyph: LumosGlyph,
        expected: String,
        extraAssert: ((LumosTerminalFrame) -> Unit)? = null,
    ) {
        val width = 9
        val height = 9
        val frame =
            blankFrame(
                width = width,
                height = height,
                glyphState = glyphState(name = glyph.name, progress = 0.5f),
            )
        val result = CliGlyph.overlay(frame)
        val actual =
            buildString {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val ch = result.cells[y * width + x].char
                        append(if (ch == ' ') '.' else ch)
                    }
                    if (y != height - 1) append('\n')
                }
            }
        val normalizedExpected = expected.lines().joinToString("\n") { it }
        assertEquals(normalizedExpected, actual, "snapshot for $glyph")
        extraAssert?.invoke(result)
    }

    private fun blankFrame(
        width: Int,
        height: Int,
        glyphState: VoxelGlyphState?,
        frameNumber: Long = 0L,
    ): LumosTerminalFrame =
        LumosTerminalFrame(
            width = width,
            height = height,
            cells = List(width * height) { TerminalCell(char = '.') },
            ambient = null,
            glyphState = glyphState,
            frameNumber = frameNumber,
        )

    private fun glyphState(
        name: String,
        progress: Float,
    ): VoxelGlyphState =
        VoxelGlyphState(
            glyphName = name,
            progress = progress,
            red = 0.40f,
            green = 0.75f,
            blue = 0.55f,
        )

    private fun expectedAccent(glyph: LumosGlyph): OklabColor {
        val state = glyphState(glyph.name, 0.5f)
        return OklabColor.fromSrgb(
            NeutralColor.fromRgba(state.red, state.green, state.blue),
        )
    }

    private fun assertOklabClose(
        expected: OklabColor,
        actual: OklabColor,
        tolerance: Float = 1e-4f,
    ) {
        assertTrue(
            abs(expected.lightness - actual.lightness) < tolerance &&
                abs(expected.a - actual.a) < tolerance &&
                abs(expected.b - actual.b) < tolerance,
            "expected $expected to be ≈ $actual",
        )
    }
}
