package link.socket.phosphor.lumos.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelGlyphState

/**
 * PHO-85: the frame's serialized form, byte for byte, as it was before the frame moved out of
 * `phosphor-lumos-compose`. JVM only: Kotlin/JS writes a whole float without its `.0`.
 */
class LumosCanvasFrameWireFormatTest {
    @Test
    fun `a frame serializes exactly as it did in the Compose module`() {
        val frame =
            LumosCanvasFrame(
                width = 800,
                height = 600,
                voxels =
                    listOf(
                        LumosCanvasFrame.CanvasVoxel(
                            screenX = 400f,
                            screenY = 300f,
                            radiusPx = 5f,
                            red = 0.5f,
                            green = 0.5f,
                            blue = 1f,
                            alpha = 1f,
                            z = 0.5f,
                            dx = 0.5f,
                            dy = -0.25f,
                        ),
                        LumosCanvasFrame.CanvasVoxel(
                            screenX = 1f,
                            screenY = 2f,
                            radiusPx = 3f,
                            red = 0.25f,
                            green = 0.75f,
                            blue = 0f,
                            alpha = 1f,
                            z = -0.5f,
                        ),
                    ),
                ambient =
                    VoxelAmbient(
                        glowRed = 0.25f,
                        glowGreen = 0.5f,
                        glowBlue = 0.75f,
                        glowIntensity = 1f,
                        orbRotationX = 0f,
                        orbRotationY = 0.5f,
                        orbRotationZ = -0.5f,
                    ),
                glyph = VoxelGlyphState(glyphName = "spark", progress = 0.5f, red = 1f, green = 0.5f, blue = 0.25f),
                tick = 42L,
            )

        assertEquals(WIRE, Json.encodeToString(LumosCanvasFrame.serializer(), frame))
        assertEquals(frame, Json.decodeFromString(LumosCanvasFrame.serializer(), WIRE))
    }

    private companion object {
        const val WIRE =
            """{"width":800,"height":600,"voxels":[""" +
                """{"screenX":400.0,"screenY":300.0,"radiusPx":5.0,"red":0.5,"green":0.5,"blue":1.0,""" +
                """"alpha":1.0,"z":0.5,"dx":0.5,"dy":-0.25},""" +
                """{"screenX":1.0,"screenY":2.0,"radiusPx":3.0,"red":0.25,"green":0.75,"blue":0.0,""" +
                """"alpha":1.0,"z":-0.5}],""" +
                """"ambient":{"glowRed":0.25,"glowGreen":0.5,"glowBlue":0.75,"glowIntensity":1.0,""" +
                """"orbRotationX":0.0,"orbRotationY":0.5,"orbRotationZ":-0.5},""" +
                """"glyph":{"glyphName":"spark","progress":0.5,"red":1.0,"green":0.5,"blue":0.25},"tick":42}"""
    }
}
