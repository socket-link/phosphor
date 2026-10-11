package link.socket.phosphor.trace

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.palette.AtmospherePresets

/**
 * A trace decoded from v2 keeps its cell channels quantized (PHO-45). These pin
 * down that playback never expands them, and that what it plays is bit for bit
 * what PHO-42's float decode of the same bytes produced.
 */
class TraceQuantizedResidencyTest {
    @Test
    fun `decoding v2 keeps the cell channels quantized at 11 bytes per voxel-frame`() {
        val decoded = roundTrip(realCapture())

        val cells = assertIs<CellChannels.Quantized>(decoded.cells)
        assertFalse(decoded.floatColumnsResident)
        // A Lumos capture carries no alpha, so the alpha channel is absent.
        assertTrue(cells.cellAlpha.isEmpty())
        assertEquals(11L * cells.size, TraceTestData.residentCellBytes(decoded))
    }

    @Test
    fun `a v1 trace still decodes to float columns`() {
        val v1 = TraceTestData.withFormatVersion(realCapture(), formatVersion = 1)

        val decoded = roundTrip(v1)

        assertIs<CellChannels.Floats>(decoded.cells)
        assertEquals(v1, decoded)
    }

    @Test
    fun `playback and seam measurement and re-encoding never expand the float columns of a decoded trace`() {
        val decoded = roundTrip(StateMachineTraces.threeState())

        val player = TracePlayer(decoded)
        repeat(decoded.frameCount) {
            player.seek(it)
            player.currentFrame
        }
        decoded.toFrames()
        decoded.seamErrors()
        TraceCodec.encode(decoded)
        val machine = TraceStateMachine(decoded, "idle", TransitionPolicy.Crossfade(durationMs = 500))
        machine.requestState("uncertain").getOrThrow()
        repeat(12) { machine.advance(100.milliseconds) }

        assertFalse(decoded.floatColumnsResident)
    }

    @Test
    fun `per-frame dequantization is bit-identical to the PHO-42 float decode`() {
        val random = Random(0x45)
        val traces = listOf(realCapture(), StateMachineTraces.threeState()) + List(20) { randomTrace(random) }

        for ((index, trace) in traces.withIndex()) {
            val bytes = TraceCodec.encode(trace)
            val expected = legacyFloatDecode(bytes)
            val decoded = TraceCodec.decode(bytes).getOrThrow()
            val player = TracePlayer(decoded)

            for (frameIndex in 0 until decoded.frameCount) {
                player.seek(frameIndex)
                assertBitIdentical(expected[frameIndex], player.currentFrame, "trace $index frame $frameIndex")
            }
            assertEquals(expected, decoded.toFrames(), "toFrames drifted on trace $index")
        }
    }

    @Test
    fun `a held frame over a quantized trace is the identical instance`() {
        val player = TracePlayer(roundTrip(realCapture()))

        val first = player.frameAt(0.milliseconds)

        assertSame(first, player.frameAt(player.frameDuration / 2))
    }

    @Test
    fun `the public float columns of a decoded trace hold the values playback dequantizes`() {
        val decoded = roundTrip(realCapture())
        val played = decoded.toFrames().flatMap(VoxelFrame::cells)

        val x = decoded.cellX

        assertTrue(decoded.floatColumnsResident)
        assertSame(x, decoded.cellX, "the expanded columns are held, not rebuilt per read")
        assertEquals(played.size, x.size)
        for (i in played.indices) {
            assertEquals(played[i].x.toBits(), x[i].toBits())
            assertEquals(played[i].scale.toBits(), decoded.cellScale[i].toBits())
            assertEquals(played[i].red.toBits(), decoded.cellRed[i].toBits())
            assertTrue(decoded.cellAlpha[i].isNaN())
        }
    }

    @Test
    fun `equality and hash do not depend on how the channels are stored`() {
        val bytes = TraceCodec.encode(realCapture())
        val quantized = TraceCodec.decode(bytes).getOrThrow()
        val floats = TraceTestData.withFormatVersion(TraceCodec.decode(bytes).getOrThrow(), formatVersion = 2)
        assertIs<CellChannels.Floats>(floats.cells)

        assertEquals(floats, quantized)
        assertEquals(quantized, floats)
        assertEquals(floats.hashCode(), quantized.hashCode())
        assertFalse(quantized.floatColumnsResident, "comparing must not expand the float columns")
    }

    @Test
    fun `requantizing the float columns of a decoded trace reproduces its bytes`() {
        // Re-encoding a quantized trace writes its codes back unchanged. This holds
        // the quantizer to the same idempotence on the values those codes decode to.
        val random = Random(0xBEEF)
        repeat(20) { iteration ->
            val encoded = TraceCodec.encode(randomTrace(random))
            val asFloats = TraceTestData.withFormatVersion(TraceCodec.decode(encoded).getOrThrow(), formatVersion = 2)

            assertContentEquals(encoded, TraceCodec.encode(asFloats), "requantization drifted on iteration $iteration")
        }
        val capture = TraceCodec.encode(realCapture())
        val captureFloats = TraceTestData.withFormatVersion(TraceCodec.decode(capture).getOrThrow(), formatVersion = 2)
        assertContentEquals(capture, TraceCodec.encode(captureFloats))
    }

    @Test
    fun `a blend between two decoded heads stays aligned`() {
        val decoded = roundTrip(realCapture())
        val player = TracePlayer(decoded)

        val first = player.frameAt(0.milliseconds)
        player.seek(decoded.frameCount - 1)
        val blend = FrameBlend(first, player.currentFrame, weight = 0.5f)

        assertTrue(blend.isAligned)
    }

    private fun assertBitIdentical(
        expected: VoxelFrame,
        actual: VoxelFrame,
        where: String,
    ) {
        assertEquals(expected.cells.size, actual.cells.size, "cell count at $where")
        expected.cells.zip(actual.cells).forEachIndexed { i, (e, a) ->
            assertEquals(bitsOf(e), bitsOf(a), "cell $i at $where")
        }
        assertEquals(expected.copy(cells = emptyList()), actual.copy(cells = emptyList()), "frame metadata at $where")
    }

    private fun bitsOf(cell: VoxelCell): List<Int?> =
        listOf(
            cell.x.toBits(),
            cell.y.toBits(),
            cell.z.toBits(),
            cell.scale.toBits(),
            cell.red.toBits(),
            cell.green.toBits(),
            cell.blue.toBits(),
            cell.alpha?.toBits(),
        )

    /**
     * PHO-42's v2 decoder, verbatim from before PHO-45: every channel expanded to
     * a `FloatArray` up front. Kept here as the reference the quantized path must
     * reproduce bit for bit.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun legacyFloatDecode(bytes: ByteArray): List<VoxelFrame> {
        val wire =
            Cbor { ignoreUnknownKeys = true }
                .decodeFromByteArray(VoxelTraceWireV2.serializer(), gzipDecompress(bytes))
        val n = wire.cellCounts.sum()
        val step = wire.positionStep
        val alpha = wire.cellAlpha
        val legacy =
            VoxelTrace(
                formatVersion = wire.formatVersion,
                fps = wire.fps,
                frameCount = wire.frameCount,
                phosphorVersion = wire.phosphorVersion,
                seed = wire.seed,
                paramSnapshot = wire.paramSnapshot,
                staticLattice = wire.staticLattice,
                segments = wire.segments,
                createdAtEpochMs = wire.createdAtEpochMs,
                ticks = wire.ticks,
                timestampsEpochMillis = wire.timestampsEpochMillis,
                cellCounts = wire.cellCounts,
                cellX = FloatArray(n) { signed16(wire.cellX, n, it) * step },
                cellY = FloatArray(n) { signed16(wire.cellY, n, it) * step },
                cellZ = FloatArray(n) { signed16(wire.cellZ, n, it) * step },
                cellScale = FloatArray(n) { unsigned16(wire.cellScale, n, it).toFloat() / 6561 },
                cellRed = FloatArray(n) { (wire.cellRed[it].toInt() and 0xFF) / 255f },
                cellGreen = FloatArray(n) { (wire.cellGreen[it].toInt() and 0xFF) / 255f },
                cellBlue = FloatArray(n) { (wire.cellBlue[it].toInt() and 0xFF) / 255f },
                cellAlpha =
                    FloatArray(n) {
                        if (alpha.isEmpty()) {
                            Float.NaN
                        } else {
                            val code = unsigned16(alpha, n, it)
                            if (code == 0xFFFF) Float.NaN else code / 255f
                        }
                    },
                ambient = wire.ambient,
                glyphs = wire.glyphs,
            )
        return legacy.toFrames()
    }

    private fun signed16(
        planes: ByteArray,
        n: Int,
        i: Int,
    ): Int = ((planes[i].toInt() shl 8) or (planes[n + i].toInt() and 0xFF)).toShort().toInt()

    private fun unsigned16(
        planes: ByteArray,
        n: Int,
        i: Int,
    ): Int = ((planes[i].toInt() and 0xFF) shl 8) or (planes[n + i].toInt() and 0xFF)

    private fun randomTrace(random: Random): VoxelTrace {
        val frames = TraceTestData.randomFrames(random)
        return VoxelTrace.fromFrames(
            frames = frames,
            fps = 30,
            phosphorVersion = "0.8.0",
            createdAtEpochMs = 0L,
            segments = TraceTestData.randomSegments(frames.size, random),
        )
    }

    private fun roundTrip(trace: VoxelTrace): VoxelTrace = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()

    private fun realCapture(): VoxelTrace =
        Oscilloscope
            .capture(
                CaptureConfig(
                    params = CaptureParams(atmosphere = AtmospherePresets.IDLE),
                    seed = 0x10_5E55L,
                    durationMs = 500L,
                    fps = 20,
                ),
            ).getOrThrow()
}
