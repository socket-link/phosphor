package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TraceFormatVersionTest {
    @Test
    fun `decode rejects an unknown formatVersion`() {
        val future = TraceTestData.sampleTrace(formatVersion = 999)

        val result = TraceCodec.decode(TraceCodec.encode(future))

        assertTrue(result.isFailure)
        val error = assertIs<TraceDecodeError.UnsupportedFormatVersion>(result.exceptionOrNull())
        assertEquals(999, error.foundVersion)
        assertEquals(TraceCodec.SUPPORTED_FORMAT_VERSION, error.supportedVersion)
    }

    @Test
    fun `decode accepts the supported formatVersion`() {
        val current = TraceTestData.sampleTrace(formatVersion = TraceCodec.SUPPORTED_FORMAT_VERSION)

        val result = TraceCodec.decode(TraceCodec.encode(current))

        assertTrue(result.isSuccess)
        assertEquals(current, result.getOrThrow())
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
