package link.socket.phosphor.trace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import link.socket.phosphor.palette.AtmospherePresets

/**
 * The pure-Kotlin inflate the web targets decode with, held to `java.util.zip`; and the
 * stored-gzip writer the web tests encode with, read back by it.
 */
class PureGzipTest {
    private val sizes = listOf(0, 1, 7, 100, 65_535, 65_536, 300_000, 2_000_000)

    private fun jvmGzip(
        data: ByteArray,
        level: Int = Deflater.DEFAULT_COMPRESSION,
    ): ByteArray {
        val sink = ByteArrayOutputStream()
        object : GZIPOutputStream(sink) {
            init {
                def.setLevel(level)
            }
        }.use { it.write(data) }
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
    fun `inflates every deflate level, stored blocks included`() {
        val data = ByteArray(200_000) { ((it / 5) % 53 + (it % 3)).toByte() }
        for (level in Deflater.NO_COMPRESSION..Deflater.BEST_COMPRESSION) {
            assertContentEquals(data, PureGzip.gunzip(jvmGzip(data, level)), "level $level")
        }
    }

    @Test
    fun `a real trace payload inflates byte for byte`() {
        val trace =
            Oscilloscope
                .capture(
                    CaptureConfig(
                        params = CaptureParams(atmosphere = AtmospherePresets.THINKING),
                        durationMs = 1_000,
                        fps = 20,
                    ),
                )
                .getOrThrow()
        val bytes = TraceCodec.encode(trace)
        val expected = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        assertContentEquals(expected, PureGzip.gunzip(bytes))
    }

    @Test
    fun `crc32 matches the JVM`() {
        for (size in sizes) {
            val data = Random(size + 2).nextBytes(size)
            assertEquals(CRC32().apply { update(data) }.value.toInt(), PureGzip.crc32(data), "crc $size")
        }
    }

    @Test
    fun `a damaged stream is refused rather than misread`() {
        val good = jvmGzip(ByteArray(10_000) { (it % 97).toByte() })
        val badCrc = good.copyOf().also { it[it.size - 8] = (it[it.size - 8] + 1).toByte() }
        val badSize = good.copyOf().also { it[it.size - 4] = (it[it.size - 4] + 1).toByte() }
        val truncated = good.copyOf(good.size - 12)
        val notGzip = good.copyOf().also { it[0] = 0 }

        for ((name, bytes) in listOf(
            "crc" to badCrc,
            "size" to badSize,
            "truncated" to truncated,
            "magic" to notGzip,
        )) {
            assertFails(name) { PureGzip.gunzip(bytes) }
        }
    }

    @Test
    fun `stored gzip is read back by the JVM`() {
        for (size in sizes) {
            val data = Random(size + 1).nextBytes(size)
            val read = GZIPInputStream(ByteArrayInputStream(storedGzip(data))).use { it.readBytes() }
            assertContentEquals(data, read, "stored $size")
            assertContentEquals(data, PureGzip.gunzip(storedGzip(data)), "stored round trip $size")
        }
    }
}
