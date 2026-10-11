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
import link.socket.phosphor.palette.AtmospherePresets

class TraceSizeBudgetTest {
    @Test
    fun `five second synthetic clip encodes within the PHO-35 v2 target`() {
        val fps = 30
        val seconds = 5
        val frameCount = fps * seconds
        val voxelCount = 1_500

        val frames = synthesizeFrames(frameCount, voxelCount)
        val trace =
            VoxelTrace.fromFrames(
                frames = frames,
                fps = fps,
                phosphorVersion = "0.8.0",
                createdAtEpochMs = 0L,
                seed = 42L,
                paramSnapshot = mapOf("pulseFrequency" to "0.5", "noise" to "0.2"),
            )
        val v1Trace = TraceTestData.withFormatVersion(trace, formatVersion = 1)

        val v1Bytes = TraceCodec.encode(v1Trace)
        val v2Bytes = TraceCodec.encode(trace)
        val voxelFrames = frameCount.toLong() * voxelCount

        println(
            "[PHO-42] synthetic ${seconds}s @ ${fps}fps × $voxelCount voxels: " +
                "v1 ${v1Bytes.size} B (${perVoxelFrame(v1Bytes.size, voxelFrames)} B/vf), " +
                "v2 ${v2Bytes.size} B (${perVoxelFrame(v2Bytes.size, voxelFrames)} B/vf)",
        )

        // Both versions round-trip at this scale: v1 exactly, v2 within tolerance.
        assertEquals(frames, TraceCodec.decode(v1Bytes).getOrThrow().toFrames())
        TraceTestData.assertV2Equivalent(trace, TraceCodec.decode(v2Bytes).getOrThrow())

        // PHO-35 set ≤ 2 MB per 5 s clip as the v2 target. Measured ~0.68 MB on JVM.
        assertTrue(v2Bytes.size <= TWO_MEBIBYTES, "v2 synthetic clip ${v2Bytes.size} B exceeded the 2 MB target")
        assertTrue(
            v2Bytes.size * 3 < v1Bytes.size,
            "v2 ${v2Bytes.size} B should be well under a third of v1 ${v1Bytes.size} B",
        )
    }

    @Test
    fun `real 4801-voxel loops fit the SCKT-392 loops-only budget`() {
        // Socket's orb: resolution 10 (4,801 voxels) stepped at 20 fps. One 4 s loop
        // per preset is the loops-only asset set SCKT-392 budgets at 10 MB.
        var totalV2 = 0L
        for ((name, preset) in LOOPS) {
            val trace =
                Oscilloscope
                    .capture(
                        CaptureConfig(
                            params = CaptureParams(atmosphere = preset),
                            seed = SEED,
                            durationMs = LOOP_MS,
                            fps = SOCKET_FPS,
                        ),
                    ).getOrThrow()
            val voxelFrames = trace.cellCounts.sumOf { it.toLong() }
            assertEquals(LUMOS_VOXELS * trace.frameCount.toLong(), voxelFrames, "$name capture is not a full lattice")

            val v1Size = TraceCodec.encode(TraceTestData.withFormatVersion(trace, formatVersion = 1)).size
            val v2Size = TraceCodec.encode(trace).size
            totalV2 += v2Size

            println(
                "[PHO-42] $name loop ${LOOP_MS / 1_000}s @ ${SOCKET_FPS}fps × $LUMOS_VOXELS voxels: " +
                    "v1 $v1Size B (${perVoxelFrame(v1Size, voxelFrames)} B/vf), " +
                    "v2 $v2Size B (${perVoxelFrame(v2Size, voxelFrames)} B/vf)",
            )

            assertTrue(
                v2Size <= LOOPS_ONLY_TARGET_BYTES_PER_VOXEL_FRAME * voxelFrames,
                "$name loop is ${perVoxelFrame(v2Size, voxelFrames)} B/vf; the loops-only target is " +
                    "$LOOPS_ONLY_TARGET_BYTES_PER_VOXEL_FRAME",
            )
        }

        println("[PHO-42] five loops total v2 $totalV2 B (SCKT-392 budget $SCKT_392_BUDGET_BYTES B)")
        assertTrue(totalV2 <= SCKT_392_BUDGET_BYTES, "Five loops total $totalV2 B exceeded the 10 MB budget")
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

    private fun perVoxelFrame(
        bytes: Int,
        voxelFrames: Long,
    ): String {
        val hundredths = (bytes * 100.0 / voxelFrames + 0.5).toLong()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    private companion object {
        const val SEED = 0x10_5E55L
        const val SOCKET_FPS = 20
        const val LOOP_MS = 4_000L
        const val LUMOS_VOXELS = 4_801L

        // SCKT-392: 10 MB across all bundled states. Spread over five 4 s loops of
        // 4,801 voxels at 20 fps, that is 5.2 B per voxel-frame. Measured 3.4–4.2 on JVM.
        const val LOOPS_ONLY_TARGET_BYTES_PER_VOXEL_FRAME = 5.2
        const val SCKT_392_BUDGET_BYTES = 10_000_000L
        const val TWO_MEBIBYTES = 2 * 1024 * 1024

        val LOOPS =
            listOf(
                "IDLE" to AtmospherePresets.IDLE,
                "LISTENING" to AtmospherePresets.LISTENING,
                "THINKING" to AtmospherePresets.THINKING,
                "UNCERTAIN" to AtmospherePresets.UNCERTAIN,
                "READY" to AtmospherePresets.READY,
            )
    }
}
