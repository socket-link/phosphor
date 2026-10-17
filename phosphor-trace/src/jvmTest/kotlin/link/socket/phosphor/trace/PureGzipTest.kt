package link.socket.phosphor.trace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import link.socket.phosphor.palette.AtmospherePresets

// PHO-69 spike: the pure-Kotlin inflate the web targets use, checked against java.util.zip.
class PureGzipTest {
    private val sizes = listOf(0, 1, 7, 100, 65_535, 65_536, 300_000, 2_000_000)

    private fun jvmGzip(data: ByteArray): ByteArray {
        val sink = ByteArrayOutputStream()
        GZIPOutputStream(sink).use { it.write(data) }
        return sink.toByteArray()
    }

    @Test
    fun `inflates what the JVM deflates, random and compressible`() {
        for (size in sizes) {
            val random = Random(size).nextBytes(size)
            assertContentEquals(random, PureGzip.gunzip(jvmGzip(random)), "random $size")
            val compressible = ByteArray(size) { ((it / 7) % 37).toByte() }
            assertContentEquals(compressible, PureGzip.gunzip(jvmGzip(compressible)), "compressible $size")
        }
    }

    @Test
    fun `stored output is read back by the JVM`() {
        for (size in sizes) {
            val data = Random(size + 1).nextBytes(size)
            val read = GZIPInputStream(ByteArrayInputStream(PureGzip.gzipStored(data))).use { it.readBytes() }
            assertContentEquals(data, read, "stored $size")
            assertContentEquals(data, PureGzip.gunzip(PureGzip.gzipStored(data)), "stored round trip $size")
        }
    }

    @Test
    fun `a real trace payload inflates byte for byte`() {
        val trace =
            Oscilloscope
                .capture(CaptureConfig(params = CaptureParams(atmosphere = AtmospherePresets.THINKING), durationMs = 1_000, fps = 20))
                .getOrThrow()
        val bytes = TraceCodec.encode(trace)
        val expected = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        assertContentEquals(expected, PureGzip.gunzip(bytes))
    }
}
