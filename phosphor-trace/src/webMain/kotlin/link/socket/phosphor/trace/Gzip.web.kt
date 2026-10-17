package link.socket.phosphor.trace

// The browser's CompressionStream / DecompressionStream are promise-based; the codec's seam is
// synchronous, so the web targets inflate in Kotlin and write stored blocks when asked to encode.
internal actual fun gzipCompress(data: ByteArray): ByteArray = PureGzip.gzipStored(data)

internal actual fun gzipDecompress(data: ByteArray): ByteArray = PureGzip.gunzip(data)
