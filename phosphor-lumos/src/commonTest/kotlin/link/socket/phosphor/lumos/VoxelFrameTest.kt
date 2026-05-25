package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class VoxelFrameTest {
    @Test
    fun `VoxelFrame round-trips via kotlinx-serialization`() {
        val frame =
            VoxelFrame(
                tick = 42L,
                timestampEpochMillis = 99_999L,
                resolution = 8,
                cells =
                    listOf(
                        VoxelCell(
                            x = 0.1f,
                            y = 0.2f,
                            z = -0.3f,
                            scale = 0.85f,
                            red = 0.5f,
                            green = 0.6f,
                            blue = 0.7f,
                        ),
                        VoxelCell(
                            x = 1.5f,
                            y = -0.25f,
                            z = 0.0f,
                            scale = 1.0f,
                            red = 0.1f,
                            green = 0.2f,
                            blue = 0.3f,
                            alpha = 0.75f,
                        ),
                    ),
                ambient =
                    VoxelAmbient(
                        glowRed = 0.4f,
                        glowGreen = 0.5f,
                        glowBlue = 0.6f,
                        glowIntensity = 0.9f,
                        orbRotationX = 0.1f,
                        orbRotationY = 0.2f,
                        orbRotationZ = 0.3f,
                    ),
                glyph =
                    VoxelGlyphState(
                        glyphName = "SPARK",
                        progress = 0.65f,
                        red = 0.95f,
                        green = 0.85f,
                        blue = 0.75f,
                    ),
            )

        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(VoxelFrame.serializer(), frame)
        val decoded = json.decodeFromString(VoxelFrame.serializer(), encoded)

        assertEquals(frame, decoded)
    }

    @Test
    fun `VoxelFrame round-trips with null glyph and null alpha`() {
        val frame =
            VoxelFrame(
                tick = 0L,
                timestampEpochMillis = 0L,
                resolution = 4,
                cells =
                    listOf(
                        VoxelCell(
                            x = 0f,
                            y = 0f,
                            z = 0f,
                            scale = 1f,
                            red = 0f,
                            green = 0f,
                            blue = 0f,
                        ),
                    ),
                ambient =
                    VoxelAmbient(
                        glowRed = 0f,
                        glowGreen = 0f,
                        glowBlue = 0f,
                        glowIntensity = 0f,
                        orbRotationX = 0f,
                        orbRotationY = 0f,
                        orbRotationZ = 0f,
                    ),
            )

        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(VoxelFrame.serializer(), frame)
        val decoded = json.decodeFromString(VoxelFrame.serializer(), encoded)

        assertEquals(frame, decoded)
        assertNull(decoded.glyph)
        assertNull(decoded.cells.single().alpha)
    }

    @Test
    fun `empty cells list is a valid degenerate frame`() {
        val frame =
            VoxelFrame(
                tick = 1L,
                timestampEpochMillis = 2L,
                resolution = 0,
                cells = emptyList(),
                ambient =
                    VoxelAmbient(
                        glowRed = 0f,
                        glowGreen = 0f,
                        glowBlue = 0f,
                        glowIntensity = 0f,
                        orbRotationX = 0f,
                        orbRotationY = 0f,
                        orbRotationZ = 0f,
                    ),
            )

        assertEquals(0, frame.cells.size)

        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(VoxelFrame.serializer(), frame)
        val decoded = json.decodeFromString(VoxelFrame.serializer(), encoded)

        assertEquals(frame, decoded)
    }

    @Test
    fun `VoxelFrame equality respects every field`() {
        val baseAmbient =
            VoxelAmbient(
                glowRed = 0.4f,
                glowGreen = 0.5f,
                glowBlue = 0.6f,
                glowIntensity = 0.9f,
                orbRotationX = 0.1f,
                orbRotationY = 0.2f,
                orbRotationZ = 0.3f,
            )
        val baseCell =
            VoxelCell(
                x = 0.1f,
                y = 0.2f,
                z = 0.3f,
                scale = 0.9f,
                red = 0.4f,
                green = 0.5f,
                blue = 0.6f,
                alpha = 0.7f,
            )
        val baseGlyph =
            VoxelGlyphState(
                glyphName = "SPARK",
                progress = 0.5f,
                red = 0.1f,
                green = 0.2f,
                blue = 0.3f,
            )
        val base =
            VoxelFrame(
                tick = 1L,
                timestampEpochMillis = 2L,
                resolution = 8,
                cells = listOf(baseCell),
                ambient = baseAmbient,
                glyph = baseGlyph,
            )

        assertEquals(base, base.copy())
        assertNotEquals(base, base.copy(tick = 2L))
        assertNotEquals(base, base.copy(timestampEpochMillis = 3L))
        assertNotEquals(base, base.copy(resolution = 9))
        assertNotEquals(base, base.copy(cells = emptyList()))
        assertNotEquals(base, base.copy(cells = listOf(baseCell.copy(x = 9f))))
        assertNotEquals(base, base.copy(ambient = baseAmbient.copy(glowIntensity = 0.1f)))
        assertNotEquals(base, base.copy(glyph = null))
        assertNotEquals(base, base.copy(glyph = baseGlyph.copy(progress = 0.99f)))
    }

    @Test
    fun `VoxelCell equality respects every field including optional alpha`() {
        val cell =
            VoxelCell(
                x = 0.1f,
                y = 0.2f,
                z = 0.3f,
                scale = 0.9f,
                red = 0.4f,
                green = 0.5f,
                blue = 0.6f,
                alpha = 0.7f,
            )

        assertEquals(cell, cell.copy())
        assertNotEquals(cell, cell.copy(x = 1f))
        assertNotEquals(cell, cell.copy(y = 1f))
        assertNotEquals(cell, cell.copy(z = 1f))
        assertNotEquals(cell, cell.copy(scale = 1f))
        assertNotEquals(cell, cell.copy(red = 1f))
        assertNotEquals(cell, cell.copy(green = 1f))
        assertNotEquals(cell, cell.copy(blue = 1f))
        assertNotEquals(cell, cell.copy(alpha = null))
    }
}
