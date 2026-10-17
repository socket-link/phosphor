package link.socket.phosphor.trace

/**
 * Encode [trace] as this target can, so the decode paths run on every target.
 *
 * On the JVM and Apple targets this is [TraceCodec.encode]. The web decodes only,
 * so there it is [TraceCodec.encodePayload] — the same CBOR — in [storedGzip]
 * framing. Tests that measure encoded size need real deflate and live in `jvmTest`.
 */
internal expect fun encodeForTest(trace: VoxelTrace): ByteArray

/** gzip [data] as this target can: [gzipCompress] on the JVM and Apple targets, [storedGzip] on the web. */
internal expect fun gzipForTest(data: ByteArray): ByteArray

/**
 * Valid gzip (RFC 1952) around stored, uncompressed deflate blocks — what the web
 * tests gzip with, since the web's [gzipCompress] refuses. `PureGzipTest` checks
 * that `java.util.zip` reads it back.
 */
internal fun storedGzip(data: ByteArray): ByteArray {
    val blocks = maxOf(1, (data.size + MAX_STORED_BLOCK - 1) / MAX_STORED_BLOCK)
    val out = ByteArray(10 + data.size + blocks * 5 + 8)
    out[0] = 0x1f.toByte()
    out[1] = 0x8b.toByte()
    out[2] = 8
    out[9] = 0xff.toByte()
    var pos = 10
    var offset = 0
    for (block in 0 until blocks) {
        val length = minOf(MAX_STORED_BLOCK, data.size - offset)
        out[pos++] = if (block == blocks - 1) 1 else 0
        out[pos++] = (length and 0xff).toByte()
        out[pos++] = ((length ushr 8) and 0xff).toByte()
        out[pos++] = (length.inv() and 0xff).toByte()
        out[pos++] = ((length.inv() ushr 8) and 0xff).toByte()
        data.copyInto(out, pos, offset, offset + length)
        pos += length
        offset += length
    }
    writeIntLe(out, pos, PureGzip.crc32(data))
    writeIntLe(out, pos + 4, data.size)
    return out
}

private fun writeIntLe(
    out: ByteArray,
    at: Int,
    value: Int,
) {
    for (i in 0 until 4) out[at + i] = ((value ushr (8 * i)) and 0xff).toByte()
}

private const val MAX_STORED_BLOCK = 65_535
