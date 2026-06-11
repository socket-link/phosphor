package link.socket.phosphor.trace

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame

class TraceSizeBudgetTest {
    @Test
    fun `five second clip encodes within the v1 size ceiling`() {
        val fps = 30
        val seconds = 5
        val frameCount = fps * seconds
        val voxelCount = 1_500

        val frames = synthesizeFrames(frameCount, voxelCount)
        val trace =
            VoxelTrace.fromFrames(
                frames = frames,
                fps = fps,
                phosphorVersion = "0.7.0",
                createdAtEpochMs = 0L,
                seed = 42L,
                paramSnapshot = mapOf("pulseFrequency" to "0.5", "noise" to "0.2"),
            )

        val encoded = TraceCodec.encode(trace)
        val sizeBytes = encoded.size
        val sizeMb = sizeBytes.toDouble() / (1024.0 * 1024.0)
        val rawFloatChannelBytes = frameCount.toLong() * voxelCount * CELL_FLOAT_CHANNELS * Float.SIZE_BYTES

        println(
            "[PHO-35] VoxelTrace v1 encoded size: $sizeBytes bytes " +
                "(${formatMb(sizeMb)} MB) for ${seconds}s @ ${fps}fps × $voxelCount voxels " +
                "(raw cell-channel floats = $rawFloatChannelBytes bytes; v2 quantization target ≤ 2 MB)",
        )

        // Round-trips at this scale.
        val decoded = TraceCodec.decode(encoded).getOrThrow()
        assertEquals(frameCount, decoded.frameCount)
        assertEquals(frames, decoded.toFrames())

        // Columnar CBOR + gzip must beat the raw per-frame float footprint.
        assertTrue(
            sizeBytes < rawFloatChannelBytes,
            "Encoded size $sizeBytes should be smaller than raw float channels $rawFloatChannelBytes",
        )

        // v1 documented ceiling for full-precision traces. ≤ 2 MB is the v2
        // (quantization) target tracked on PHO-35; v1 reports the real number.
        assertTrue(
            sizeBytes <= V1_SIZE_CEILING_BYTES,
            "Encoded size $sizeBytes exceeded the v1 ceiling $V1_SIZE_CEILING_BYTES",
        )
    }

    private fun synthesizeFrames(
        frameCount: Int,
        voxelCount: Int,
    ): List<VoxelFrame> {
        val twoPi = (2.0 * PI).toFloat()
        val directions =
            Array(voxelCount) { index ->
                val k = index + 0.5
                val polar = acos(1.0 - 2.0 * k / voxelCount)
                val azimuth = PI * (1.0 + sqrt(5.0)) * k
                Triple(
                    (sin(polar) * cos(azimuth)).toFloat(),
                    cos(polar).toFloat(),
                    (sin(polar) * sin(azimuth)).toFloat(),
                )
            }
        // Stable per-voxel radial offset, mirroring the construction-time jitter
        // of a real lattice (constant across frames, so it compresses well).
        val jitter = FloatArray(voxelCount) { ((it * 2654435761u.toInt()) and 0xFF) / 255f - 0.5f }

        return List(frameCount) { frameIndex ->
            val t = frameIndex.toFloat() / 30f
            val pulse = 1f + 0.05f * sin(t * twoPi * 0.5f)
            val rotation = t * 0.3f
            val cosR = cos(rotation)
            val sinR = sin(rotation)
            val cells = ArrayList<VoxelCell>(voxelCount)
            for (index in 0 until voxelCount) {
                val (dx, dy, dz) = directions[index]
                val rotatedX = dx * cosR + dz * sinR
                val rotatedZ = -dx * sinR + dz * cosR
                val radius = (1.2f + 0.02f * jitter[index]) * pulse
                val hue = (index.toFloat() / voxelCount + t * 0.1f) % 1f
                cells +=
                    VoxelCell(
                        x = rotatedX * radius,
                        y = dy * radius,
                        z = rotatedZ * radius,
                        scale = 0.9f * pulse,
                        red = 0.5f + 0.5f * sin(hue * twoPi),
                        green = 0.5f + 0.5f * sin(hue * twoPi + 2f),
                        blue = 0.5f + 0.5f * sin(hue * twoPi + 4f),
                    )
            }
            VoxelFrame(
                tick = frameIndex.toLong(),
                timestampEpochMillis = (t * 1000f).toLong(),
                resolution = 7,
                cells = cells,
                ambient = VoxelAmbient(0.3f, 0.4f, 0.5f, 0.8f, rotation, 0f, 0f),
            )
        }
    }

    private fun formatMb(value: Double): String {
        val hundredths = (value * 100.0 + 0.5).toLong()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    private companion object {
        const val CELL_FLOAT_CHANNELS = 7

        // Measured ~2.70 MB on JVM for this synthetic clip. The ceiling guards
        // against regressions with headroom for cross-platform gzip variance;
        // ≤ 2 MB remains the v2 (quantization) target, not a v1 gate.
        const val V1_SIZE_CEILING_BYTES = 4 * 1024 * 1024
    }
}
