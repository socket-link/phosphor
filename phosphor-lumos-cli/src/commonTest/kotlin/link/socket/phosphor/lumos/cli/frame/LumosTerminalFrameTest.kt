package link.socket.phosphor.lumos.cli.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import link.socket.phosphor.color.OklabColor
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelGlyphState
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame.TerminalCell

class LumosTerminalFrameTest {
    @Test
    fun `LumosTerminalFrame round-trips transparent blank frame via kotlinx-serialization`() {
        val frame =
            LumosTerminalFrame(
                width = 10,
                height = 10,
                cells = List(100) { TerminalCell(' ', null, null) },
                ambient = null,
                glyphState = null,
                frameNumber = 0L,
            )

        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(LumosTerminalFrame.serializer(), frame)
        val decoded = json.decodeFromString(LumosTerminalFrame.serializer(), encoded)

        assertEquals(frame, decoded)
    }

    @Test
    fun `LumosTerminalFrame round-trips colors and voxel metadata`() {
        val frame =
            LumosTerminalFrame(
                width = 2,
                height = 2,
                cells =
                    List(4) { index ->
                        if (index == 0) {
                            TerminalCell(
                                char = '*',
                                foreground = OklabColor(lightness = 0.7f, a = 0.1f, b = -0.1f),
                                background = OklabColor(lightness = 0.2f, a = -0.05f, b = 0.05f),
                                bold = true,
                            )
                        } else {
                            TerminalCell(' ', null, null)
                        }
                    },
                ambient =
                    VoxelAmbient(
                        glowRed = 0.1f,
                        glowGreen = 0.2f,
                        glowBlue = 0.3f,
                        glowIntensity = 0.4f,
                        orbRotationX = 0.5f,
                        orbRotationY = 0.6f,
                        orbRotationZ = 0.7f,
                    ),
                glyphState =
                    VoxelGlyphState(
                        glyphName = "SPARK",
                        progress = 0.8f,
                        red = 0.9f,
                        green = 0.85f,
                        blue = 0.75f,
                    ),
                frameNumber = 0L,
            )

        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(LumosTerminalFrame.serializer(), frame)
        val decoded = json.decodeFromString(LumosTerminalFrame.serializer(), encoded)

        assertEquals(frame, decoded)
    }

    @Test
    fun `cells must match declared grid size`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                LumosTerminalFrame(
                    width = 2,
                    height = 2,
                    cells = List(3) { TerminalCell() },
                    ambient = null,
                    glyphState = null,
                    frameNumber = 0L,
                )
            }

        assertEquals("cells size must equal width * height", failure.message)
    }

    @Test
    fun `terminal cell defaults to transparent blank`() {
        assertEquals(
            TerminalCell(
                char = ' ',
                foreground = null,
                background = null,
                bold = false,
            ),
            TerminalCell(),
        )
    }
}
