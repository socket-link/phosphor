package link.socket.phosphor.trace

// The web decodes only. The browser's CompressionStream / DecompressionStream are promise-based
// and the codec's seam is synchronous, so decode inflates in Kotlin (PureGzip) and encode refuses
// rather than write a stored, uncompressed stream several times the size of what the JVM writes.

internal actual fun gzipCompress(data: ByteArray): ByteArray =
    throw UnsupportedOperationException("phosphor-trace encodes on the JVM and Apple targets; the web decodes only")

internal actual fun gzipDecompress(data: ByteArray): ByteArray = PureGzip.gunzip(data)
