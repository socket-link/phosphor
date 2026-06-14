package link.socket.phosphor.trace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal actual fun gzipCompress(data: ByteArray): ByteArray {
    val sink = ByteArrayOutputStream()
    GZIPOutputStream(sink).use { it.write(data) }
    return sink.toByteArray()
}

internal actual fun gzipDecompress(data: ByteArray): ByteArray =
    GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
