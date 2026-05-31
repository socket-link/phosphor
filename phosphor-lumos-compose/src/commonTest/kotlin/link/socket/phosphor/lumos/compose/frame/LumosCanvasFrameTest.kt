package link.socket.phosphor.lumos.compose.frame

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import link.socket.phosphor.lumos.VoxelAmbient

class LumosCanvasFrameTest {
    @Test
    fun testLumosCanvasFrameSerializationRoundTrip() {
        val voxel =
            LumosCanvasFrame.CanvasVoxel(
                screenX = 400f,
                screenY = 300f,
                radiusPx = 5f,
                red = 0.5f,
                green = 0.5f,
                blue = 1f,
                alpha = 1f,
                z = 0.5f,
            )

        val ambient =
            VoxelAmbient(
                glowRed = 0.2f,
                glowGreen = 0.3f,
                glowBlue = 0.4f,
                glowIntensity = 0.8f,
                orbRotationX = 0.1f,
                orbRotationY = 0.2f,
                orbRotationZ = 0.3f,
            )

        val frame =
            LumosCanvasFrame(
                width = 800,
                height = 600,
                voxels = listOf(voxel),
                ambient = ambient,
                glyph = null,
                tick = 0L,
            )

        val json = Json
        val serialized = json.encodeToString(LumosCanvasFrame.serializer(), frame)
        val deserialized = json.decodeFromString(LumosCanvasFrame.serializer(), serialized)

        assertEquals(frame, deserialized)
    }
}
