@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("ktlint:standard:no-wildcard-imports")

package link.socket.phosphor.trace

import kotlinx.cinterop.*
import platform.posix.memset
import platform.zlib.*

// Apple targets carry no JVM gzip; the bundled `platform.zlib` interop drives
// standard gzip (RFC 1952) framing so a trace encoded here decodes on the JVM
// and vice versa. windowBits of MAX_WBITS + 16 selects gzip on deflate; + 32
// auto-detects the gzip wrapper on inflate.

private const val ZLIB_CHUNK = 32 * 1024
private const val DEFLATE_GZIP_WINDOW_BITS = MAX_WBITS + 16
private const val INFLATE_GZIP_WINDOW_BITS = MAX_WBITS + 32
private const val DEFAULT_MEM_LEVEL = 8

internal actual fun gzipCompress(data: ByteArray): ByteArray =
    memScoped {
        val stream = alloc<z_stream>()
        memset(stream.ptr, 0, sizeOf<z_stream>().convert())
        val initCode =
            deflateInit2_(
                stream.ptr,
                Z_DEFAULT_COMPRESSION,
                Z_DEFLATED,
                DEFLATE_GZIP_WINDOW_BITS,
                DEFAULT_MEM_LEVEL,
                Z_DEFAULT_STRATEGY,
                ZLIB_VERSION,
                sizeOf<z_stream>().convert(),
            )
        check(initCode == Z_OK) { "zlib deflateInit2 failed: $initCode" }
        try {
            drive(stream, data, Z_FINISH) { deflate(stream.ptr, it) }
        } finally {
            deflateEnd(stream.ptr)
        }
    }

internal actual fun gzipDecompress(data: ByteArray): ByteArray =
    memScoped {
        val stream = alloc<z_stream>()
        memset(stream.ptr, 0, sizeOf<z_stream>().convert())
        val initCode =
            inflateInit2_(
                stream.ptr,
                INFLATE_GZIP_WINDOW_BITS,
                ZLIB_VERSION,
                sizeOf<z_stream>().convert(),
            )
        check(initCode == Z_OK) { "zlib inflateInit2 failed: $initCode" }
        try {
            drive(stream, data, Z_NO_FLUSH) { inflate(stream.ptr, it) }
        } finally {
            inflateEnd(stream.ptr)
        }
    }

/**
 * Feed [input] through an initialized zlib [stream] and collect the output.
 *
 * [flush] is `Z_FINISH` for deflate (all input is available up front) and
 * `Z_NO_FLUSH` for inflate; [step] invokes `deflate`/`inflate` with the flush.
 * The loop drains the output buffer each pass and stops at `Z_STREAM_END`, or at
 * `Z_BUF_ERROR` once no further progress is possible.
 */
private fun MemScope.drive(
    stream: z_stream,
    input: ByteArray,
    flush: Int,
    step: (Int) -> Int,
): ByteArray {
    val outBuffer = allocArray<ByteVar>(ZLIB_CHUNK)
    val chunks = mutableListOf<ByteArray>()

    fun pump(): ByteArray {
        while (true) {
            stream.next_out = outBuffer.reinterpret()
            stream.avail_out = ZLIB_CHUNK.convert()
            val code = step(flush)
            val produced = ZLIB_CHUNK - stream.avail_out.toInt()
            if (produced > 0) chunks += outBuffer.readBytes(produced)
            when (code) {
                Z_STREAM_END -> break
                Z_OK -> continue
                Z_BUF_ERROR -> break
                else -> error("zlib step failed: $code")
            }
        }
        return concatenate(chunks)
    }

    if (input.isEmpty()) {
        stream.next_in = null
        stream.avail_in = 0u
        return pump()
    }
    return input.usePinned { pinned ->
        stream.next_in = pinned.addressOf(0).reinterpret()
        stream.avail_in = input.size.convert()
        pump()
    }
}

private fun concatenate(chunks: List<ByteArray>): ByteArray {
    if (chunks.isEmpty()) return ByteArray(0)
    if (chunks.size == 1) return chunks[0]
    val total = chunks.sumOf { it.size }
    val result = ByteArray(total)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}
