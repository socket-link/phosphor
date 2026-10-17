package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

@OptIn(ExperimentalSerializationApi::class)
class TraceFormatVersionTest {
    @Test
    fun `this build reads v1 through v3 and writes v3 for new traces`() {
        assertEquals(1..3, TraceCodec.SUPPORTED_FORMAT_VERSIONS)
        assertEquals(3, TraceCodec.SUPPORTED_FORMAT_VERSION)
        assertEquals(3, VoxelTrace.CURRENT_FORMAT_VERSION)
    }

    @Test
    fun `decode rejects an unknown formatVersion`() {
        // A payload from a future build: only the version field is guaranteed to parse.
        val future = gzipForTest(Cbor.encodeToByteArray(FormatVersionProbe.serializer(), FormatVersionProbe(999)))

        val result = TraceCodec.decode(future)

        assertTrue(result.isFailure)
        val error = assertIs<TraceDecodeError.UnsupportedFormatVersion>(result.exceptionOrNull())
        assertEquals(999, error.foundVersion)
        assertEquals(TraceCodec.SUPPORTED_FORMAT_VERSION, error.supportedVersion)
    }

    @Test
    fun `encode refuses a formatVersion it cannot write`() {
        assertFailsWith<IllegalArgumentException> { TraceCodec.encode(TraceTestData.sampleTrace(formatVersion = 999)) }
    }

    @Test
    fun `decode accepts v1`() {
        val v1 = TraceTestData.sampleTrace(formatVersion = 1)

        val result = TraceCodec.decode(encodeForTest(v1))

        assertTrue(result.isSuccess)
        assertEquals(v1, result.getOrThrow())
    }

    @Test
    fun `decode accepts v2`() {
        val v2 = TraceTestData.sampleTrace(formatVersion = 2)

        val result = TraceCodec.decode(encodeForTest(v2))

        assertTrue(result.isSuccess)
        TraceTestData.assertV2Equivalent(v2, result.getOrThrow())
    }

    @Test
    fun `decode accepts v3`() {
        val v3 = TraceTestData.sampleTrace(formatVersion = 3)

        val result = TraceCodec.decode(encodeForTest(v3))

        assertTrue(result.isSuccess)
        TraceTestData.assertV2Equivalent(v3, result.getOrThrow())
    }

    @Test
    fun `a v2 payload whose channels disagree with its cell counts is corrupt rather than misread`() {
        val wire = TraceQuantization.toWire(TraceTestData.sampleTrace(formatVersion = 2))
        val truncated =
            VoxelTraceWireV2(
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
                positionStep = wire.positionStep,
                cellX = wire.cellX.copyOf(1),
                cellY = wire.cellY,
                cellZ = wire.cellZ,
                cellScale = wire.cellScale,
                cellRed = wire.cellRed,
                cellGreen = wire.cellGreen,
                cellBlue = wire.cellBlue,
                cellAlpha = wire.cellAlpha,
                ambient = wire.ambient,
                glyphs = wire.glyphs,
            )
        val bytes = gzipForTest(Cbor.encodeToByteArray(VoxelTraceWireV2.serializer(), truncated))

        val result = TraceCodec.decode(bytes)

        assertIs<TraceDecodeError.Corrupt>(result.exceptionOrNull())
    }

    @Test
    fun `decode reports corrupt bytes as a typed failure`() {
        val result = TraceCodec.decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        assertTrue(result.isFailure)
        assertIs<TraceDecodeError.Corrupt>(result.exceptionOrNull())
    }

    @Test
    fun `decode reports empty input as a typed failure`() {
        val result = TraceCodec.decode(ByteArray(0))

        assertTrue(result.isFailure)
        assertIs<TraceDecodeError.Corrupt>(result.exceptionOrNull())
    }
}
