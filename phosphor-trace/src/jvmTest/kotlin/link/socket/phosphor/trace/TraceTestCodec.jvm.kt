package link.socket.phosphor.trace

internal actual fun encodeForTest(trace: VoxelTrace): ByteArray = TraceCodec.encode(trace)

internal actual fun gzipForTest(data: ByteArray): ByteArray = gzipCompress(data)
