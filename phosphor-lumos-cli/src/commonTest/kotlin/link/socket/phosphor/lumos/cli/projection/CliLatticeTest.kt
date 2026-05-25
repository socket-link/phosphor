package link.socket.phosphor.lumos.cli.projection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.lumos.VoxelGlyphState
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame.TerminalCell
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase

class CliLatticeTest {
    @Test
    fun `constructor rejects non-positive dimensions and aspect ratio`() {
        assertFailsWith<IllegalArgumentException> { CliLattice(width = 0, height = 10) }
        assertFailsWith<IllegalArgumentException> { CliLattice(width = -1, height = 10) }
        assertFailsWith<IllegalArgumentException> { CliLattice(width = 10, height = 0) }
        assertFailsWith<IllegalArgumentException> { CliLattice(width = 10, height = -1) }
        assertFailsWith<IllegalArgumentException> {
            CliLattice(width = 10, height = 10, characterAspectRatio = 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            CliLattice(width = 10, height = 10, characterAspectRatio = -1f)
        }
    }

    @Test
    fun `frame of zero-scale voxels produces an all-blank terminal frame`() {
        val lattice = CliLattice(width = 10, height = 6)
        val frame =
            voxelFrame(
                cells =
                    List(8) { i ->
                        VoxelCell(
                            x = (i.toFloat() / 4f) - 1f,
                            y = (i.toFloat() / 4f) - 1f,
                            z = 0f,
                            scale = 0f,
                            red = 1f,
                            green = 0.5f,
                            blue = 0.25f,
                        )
                    },
            )

        val terminal = lattice.project(frame)

        assertEquals(10, terminal.width)
        assertEquals(6, terminal.height)
        assertEquals(60, terminal.cells.size)
        terminal.cells.forEach { cell ->
            assertEquals(' ', cell.char)
            assertNull(cell.foreground)
            assertNull(cell.background)
        }
    }

    @Test
    fun `single voxel at origin projects to the center cell`() {
        val lattice = CliLattice(width = 10, height = 6, characterAspectRatio = 1f)
        val frame =
            voxelFrame(
                cells =
                    listOf(
                        VoxelCell(
                            x = 0f,
                            y = 0f,
                            z = 0f,
                            scale = 1f,
                            red = 1f,
                            green = 1f,
                            blue = 1f,
                        ),
                    ),
            )

        val terminal = lattice.project(frame)

        // x = 0 -> screenX = (0 + 1) * 0.5 * 10 = 5
        // y = 0, aspectRatio = 1 -> screenY = (1 - 0.5) * 6 * 1 = 3
        val centerIndex = 3 * 10 + 5
        val centerCell = terminal.cells[centerIndex]
        assertEquals('@', centerCell.char, "scale 1.0 should produce '@'")
        assertNotNull(centerCell.foreground, "winning cell should carry a foreground color")

        terminal.cells.forEachIndexed { idx, cell ->
            if (idx != centerIndex) {
                assertEquals(' ', cell.char, "non-winning cells should be blank (idx=$idx)")
            }
        }
    }

    @Test
    fun `closer voxel wins the cell when two voxels project to the same cell`() {
        val lattice = CliLattice(width = 10, height = 6, characterAspectRatio = 1f)
        val far =
            VoxelCell(
                x = 0f,
                y = 0f,
                z = -0.5f,
                scale = 1f,
                red = 1f,
                green = 0f,
                blue = 0f,
            )
        val near =
            VoxelCell(
                x = 0f,
                y = 0f,
                z = 0.5f,
                scale = 0.5f,
                red = 0f,
                green = 1f,
                blue = 0f,
            )

        val terminal = lattice.project(voxelFrame(cells = listOf(far, near)))

        val center = terminal.cells[3 * 10 + 5]
        // near has scale 0.5 (mid-ramp character), far has scale 1.0 ('@').
        // near wins on z, so the cell should reflect near's scale, not far's.
        assertNotEquals('@', center.char, "the larger-z voxel should beat the higher-scale voxel")
        assertEquals(CliLattice.luminanceChar(0.5f), center.char)
    }

    @Test
    fun `two zero-scale voxels at the same cell leave the cell blank`() {
        val lattice = CliLattice(width = 10, height = 6, characterAspectRatio = 1f)
        val a =
            VoxelCell(
                x = 0f,
                y = 0f,
                z = -0.5f,
                scale = 0f,
                red = 1f,
                green = 0f,
                blue = 0f,
            )
        val b =
            VoxelCell(
                x = 0f,
                y = 0f,
                z = 0.5f,
                scale = 0f,
                red = 0f,
                green = 1f,
                blue = 0f,
            )

        val terminal = lattice.project(voxelFrame(cells = listOf(a, b)))

        terminal.cells.forEach { cell ->
            assertEquals(' ', cell.char)
            assertNull(cell.foreground)
        }
    }

    @Test
    fun `luminance char maps endpoints and midpoint correctly`() {
        assertEquals('@', CliLattice.luminanceChar(1f))
        assertEquals(' ', CliLattice.luminanceChar(0f))
        // Mid-scale lands in the middle of the ramp, not at the endpoints.
        val mid = CliLattice.luminanceChar(0.5f)
        assertNotEquals('@', mid)
        assertNotEquals(' ', mid)
        // Out-of-range inputs clamp.
        assertEquals('@', CliLattice.luminanceChar(2f))
        assertEquals(' ', CliLattice.luminanceChar(-1f))
    }

    @Test
    fun `ambient and glyph state pass through unchanged`() {
        val ambient =
            VoxelAmbient(
                glowRed = 0.1f,
                glowGreen = 0.2f,
                glowBlue = 0.3f,
                glowIntensity = 0.4f,
                orbRotationX = 0.5f,
                orbRotationY = 0.6f,
                orbRotationZ = 0.7f,
            )
        val glyph =
            VoxelGlyphState(
                glyphName = "CHECK",
                progress = 0.4f,
                red = 0.9f,
                green = 0.8f,
                blue = 0.7f,
            )
        val frame =
            voxelFrame(
                tick = 99L,
                cells = emptyList(),
                ambient = ambient,
                glyph = glyph,
            )

        val terminal = CliLattice(width = 4, height = 4).project(frame)

        assertEquals(ambient, terminal.ambient)
        assertEquals(glyph, terminal.glyphState)
        assertEquals(99L, terminal.frameNumber)
    }

    @Test
    fun `aspect ratio compensation squashes the projected y range`() {
        val tall =
            VoxelCell(
                x = 0f,
                y = 0.95f,
                z = 0f,
                scale = 1f,
                red = 1f,
                green = 1f,
                blue = 1f,
            )
        val short =
            VoxelCell(
                x = 0f,
                y = -0.95f,
                z = 0f,
                scale = 1f,
                red = 1f,
                green = 1f,
                blue = 1f,
            )
        val cells = listOf(tall, short)

        val withCompensation =
            CliLattice(width = 40, height = 20, characterAspectRatio = 2f)
                .project(voxelFrame(cells = cells))
        val withoutCompensation =
            CliLattice(width = 40, height = 20, characterAspectRatio = 1f)
                .project(voxelFrame(cells = cells))

        // With aspect ratio = 1, y = -1 should land in the bottom row (index 19).
        // With aspect ratio = 2, the same y compresses to row 10.
        val withCompRows = nonEmptyRows(withCompensation.cells, 40, 20)
        val withoutCompRows = nonEmptyRows(withoutCompensation.cells, 40, 20)
        assertTrue(
            withCompRows.max() < withoutCompRows.max(),
            "expected compensation to pull the bottom voxel up, " +
                "got max-row $withCompRows vs $withoutCompRows",
        )
    }

    @Test
    fun `projection is deterministic for identical inputs`() {
        val frame = settledFrame(AtmospherePresets.IDLE)
        val lattice = CliLattice(width = 40, height = 20)

        val a = lattice.project(frame)
        val b = lattice.project(frame)

        assertEquals(a.cells, b.cells)
        assertEquals(a.ambient, b.ambient)
    }

    @Test
    fun `IDLE atmosphere snapshot at 40x20 matches expected ascii grid`() {
        assertAsciiSnapshot(
            name = "IDLE",
            atmosphere = AtmospherePresets.IDLE,
            expected = IDLE_SNAPSHOT,
        )
    }

    @Test
    fun `LISTENING atmosphere snapshot at 40x20 matches expected ascii grid`() {
        assertAsciiSnapshot(
            name = "LISTENING",
            atmosphere = AtmospherePresets.LISTENING,
            expected = LISTENING_SNAPSHOT,
        )
    }

    @Test
    fun `THINKING atmosphere snapshot at 40x20 matches expected ascii grid`() {
        assertAsciiSnapshot(
            name = "THINKING",
            atmosphere = AtmospherePresets.THINKING,
            expected = THINKING_SNAPSHOT,
        )
    }

    @Test
    fun `UNCERTAIN atmosphere snapshot at 40x20 matches expected ascii grid`() {
        assertAsciiSnapshot(
            name = "UNCERTAIN",
            atmosphere = AtmospherePresets.UNCERTAIN,
            expected = UNCERTAIN_SNAPSHOT,
        )
    }

    @Test
    fun `READY atmosphere snapshot at 40x20 matches expected ascii grid`() {
        assertAsciiSnapshot(
            name = "READY",
            atmosphere = AtmospherePresets.READY,
            expected = READY_SNAPSHOT,
        )
    }

    private fun assertAsciiSnapshot(
        name: String,
        atmosphere: AtmosphereState,
        expected: String,
    ) {
        val frame = settledFrame(atmosphere)
        val lattice = CliLattice(width = 40, height = 20)
        val terminal = lattice.project(frame)
        val rendered = terminal.cells.toAsciiGrid(width = 40, height = 20)
        // Trailing whitespace within each row is visually meaningless and
        // brittle to capture in source. Compare row-by-row with trailing
        // spaces stripped.
        assertEquals(
            expected.lines().joinToString("\n") { it.trimEnd() },
            rendered.lines().joinToString("\n") { it.trimEnd() },
            "snapshot mismatch for $name atmosphere",
        )
    }

    private fun List<TerminalCell>.toAsciiGrid(
        width: Int,
        height: Int,
    ): String =
        buildString {
            for (row in 0 until height) {
                for (col in 0 until width) {
                    append(this@toAsciiGrid[row * width + col].char)
                }
                if (row < height - 1) append('\n')
            }
        }

    private fun nonEmptyRows(
        cells: List<TerminalCell>,
        width: Int,
        height: Int,
    ): List<Int> {
        val rows = mutableListOf<Int>()
        for (row in 0 until height) {
            for (col in 0 until width) {
                if (cells[row * width + col].char != ' ') {
                    rows += row
                    break
                }
            }
        }
        return rows
    }

    private fun voxelFrame(
        tick: Long = 0L,
        cells: List<VoxelCell>,
        ambient: VoxelAmbient = DEFAULT_AMBIENT,
        glyph: VoxelGlyphState? = null,
    ): VoxelFrame =
        VoxelFrame(
            tick = tick,
            timestampEpochMillis = 0L,
            resolution = 10,
            cells = cells,
            ambient = ambient,
            glyph = glyph,
        )

    private fun settledFrame(atmosphere: AtmosphereState): VoxelFrame {
        // The canonical presets use voxelGap = 0.05 for 3D renderers that draw
        // small cubes inside a larger lattice cell. ASCII projection has no
        // cube-size analog — it just picks a luminance character — so for
        // visual regression we lift voxelGap to 1.0 to exercise the full
        // luminance ramp. CliOrb's runtime configuration is expected to make
        // an equivalent adjustment so the orb is visible in a terminal.
        val cliAtmosphere = atmosphere.copy(voxelGap = 1f)
        val builder = VoxelFrameBuilder(initialResolution = cliAtmosphere.resolution)
        val snapshot =
            SceneSnapshot(
                frameIndex = 0L,
                elapsedTimeSeconds = 0f,
                coordinateSpace = CoordinateSpace.WORLD_CENTERED,
                agentStates = emptyList(),
                substrateState = SubstrateState.create(2, 2),
                particleStates = emptyList(),
                flowConnections = emptyList(),
                flowField = null,
                waveformHeightField = null,
                waveformGridWidth = null,
                waveformGridDepth = null,
                cameraTransform = null,
                emitterStates = emptyList(),
                choreographyPhase = CognitivePhase.NONE,
                atmosphere = cliAtmosphere,
                atmosphereTransition = null,
            )
        // Step the builder long enough to advance pulse and pattern phase past
        // their initial zero values, so the snapshot represents the atmosphere
        // mid-cycle rather than the moment of construction.
        var frame = builder.build(snapshot, dt = 0f)
        repeat(SETTLE_FRAMES) {
            frame = builder.build(snapshot, dt = SETTLE_DT)
        }
        return frame
    }

    companion object {
        private val DEFAULT_AMBIENT =
            VoxelAmbient(
                glowRed = 0f,
                glowGreen = 0f,
                glowBlue = 0f,
                glowIntensity = 0f,
                orbRotationX = 0f,
                orbRotationY = 0f,
                orbRotationZ = 0f,
            )

        private const val SETTLE_FRAMES: Int = 60
        private const val SETTLE_DT: Float = 0.05f

        // Expected ASCII grids capture the projection at 40x20 after 60 ticks
        // of 50 ms each, with voxelGap lifted to 1.0 (see [settledFrame] for
        // why). Trailing whitespace per row is normalized away in
        // [assertAsciiSnapshot], so rows are listed by their visible content.
        // Each snapshot is 20 rows; the orb sits in the top half because of
        // the spec's aspect ratio compensation.
        private fun snapshotOf(vararg rows: String): String {
            require(rows.size == 20) { "snapshot must be exactly 20 rows, got ${rows.size}" }
            return rows.joinToString("\n")
        }

        private val IDLE_SNAPSHOT: String =
            snapshotOf(
                "     @@@@@@@@@@@@@@@@@@@@@@@@@@@@@ @@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@  @",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@ @ @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "     @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@ @  @",
                "          @@@@@@@@@@ @@   @@ @ @ @ @",
                "           @",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
            )

        private val LISTENING_SNAPSHOT: String =
            snapshotOf(
                "   @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@   @",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@ @@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@ @   @@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "       @  @@@@@@@ @@@@@@@@@@@ @@",
                "                 @",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
            )

        private val THINKING_SNAPSHOT: String =
            snapshotOf(
                "@ @@ @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@ @@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                " @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "   @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@ @ @",
                "      @ @@ @ @@@@@ @@@@ @@@@@@   @",
                "                 @",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
            )

        private val UNCERTAIN_SNAPSHOT: String =
            snapshotOf(
                "   @ @+@@=@@ @@@@@@ @=@@@@@@@@@@@.   @",
                "=-:@@@@@@@@@@@@@@@@@@@@@@ @@=@@@@@@@@ @",
                "@@@@:@.@@@@@@@@@@@@@@@@@@@@@@@@=@@@-@@@@",
                "@ =@@=@@+@- #@@@@@@@-@@@@@@@@@@@ @@@@@@@",
                "@@@@@@@@@:@@@+@@ @@@@@@@#@-@@@@@@.@%@@-@",
                "#@@@@@@@@@@*@@ @.@@=+@@@@@@%#+@@@@@@@@@@",
                "@@@@#@@@%@@@@@@@-@ @@%@@@ @#=@..@@@@@@@@",
                "@@@ @@@@@@@@@@@@@@@@@@#@@ @@@  @ @ @@@@@",
                " @@@@@=@*#%@@-@@@@@@@@@@@@@@@@@@@*@@ @@@",
                "@ @  * @@@@@@@. @@@@@@@@@@@@@@@@@ -  @",
                "       @%. @@@#@@ @@@@@@@@@@@ @ @",
                "                 @",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
            )

        private val READY_SNAPSHOT: String =
            snapshotOf(
                "    @ @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "  @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@ @@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "@  @  @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@",
                "        @ @ @@@@@@@@@@@ @ @@@ @",
                "               @",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
            )
    }
}
