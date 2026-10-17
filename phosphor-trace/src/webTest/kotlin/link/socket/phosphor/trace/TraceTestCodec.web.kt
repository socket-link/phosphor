package link.socket.phosphor.trace

internal actual fun encodeForTest(trace: VoxelTrace): ByteArray = storedGzip(TraceCodec.encodePayload(trace))

internal actual fun gzipForTest(data: ByteArray): ByteArray = storedGzip(data)
