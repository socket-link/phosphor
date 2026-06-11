package link.socket.phosphor.trace

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * Typed failure modes returned by [TraceCodec.decode].
 *
 * Decoding follows the house Result pattern: [TraceCodec.decode] never throws,
 * it returns a [Result] whose failure cause is one of these.
 */
sealed class TraceDecodeError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The payload could not be un-gzipped or parsed as a [VoxelTrace] — it is
     * truncated, not a `.vxt` payload, or otherwise corrupt.
     */
    class Corrupt(
        message: String,
        cause: Throwable?,
    ) : TraceDecodeError(message, cause)

    /**
     * The payload parsed but declares a [foundVersion] this build does not
     * support. The version field exists precisely so newer formats are rejected
     * cleanly rather than silently misread.
     */
    class UnsupportedFormatVersion(
        val foundVersion: Int,
        val supportedVersion: Int,
    ) : TraceDecodeError(
            "Unsupported trace formatVersion=$foundVersion (this build reads v$supportedVersion)",
        )
}

/**
 * Round-trip serialization for [VoxelTrace]: CBOR for a compact binary encoding,
 * gzip for the payload, a versioned header, and Result-typed decoding.
 *
 * Encoded traces use the `.vxt` file extension ([FILE_EXTENSION]).
 */
@OptIn(ExperimentalSerializationApi::class)
object TraceCodec {
    /** File extension for an encoded trace payload. */
    const val FILE_EXTENSION: String = "vxt"

    /** The single [VoxelTrace.formatVersion] this build can decode. */
    const val SUPPORTED_FORMAT_VERSION: Int = VoxelTrace.CURRENT_FORMAT_VERSION

    private val cbor = Cbor { ignoreUnknownKeys = true }

    /** Encode [trace] to a gzip-compressed CBOR `.vxt` payload. */
    fun encode(trace: VoxelTrace): ByteArray {
        val payload = cbor.encodeToByteArray(VoxelTrace.serializer(), trace)
        return gzipCompress(payload)
    }

    /**
     * Decode a `.vxt` payload produced by [encode].
     *
     * @return [Result.success] with the trace, or [Result.failure] carrying a
     *  [TraceDecodeError]: [TraceDecodeError.UnsupportedFormatVersion] when the
     *  header declares an unknown version, or [TraceDecodeError.Corrupt] when the
     *  bytes cannot be un-gzipped or parsed.
     */
    fun decode(bytes: ByteArray): Result<VoxelTrace> {
        val trace =
            try {
                val payload = gzipDecompress(bytes)
                cbor.decodeFromByteArray(VoxelTrace.serializer(), payload)
            } catch (error: Throwable) {
                return Result.failure(
                    TraceDecodeError.Corrupt("Failed to decode VoxelTrace payload", error),
                )
            }

        if (trace.formatVersion != SUPPORTED_FORMAT_VERSION) {
            return Result.failure(
                TraceDecodeError.UnsupportedFormatVersion(trace.formatVersion, SUPPORTED_FORMAT_VERSION),
            )
        }
        return Result.success(trace)
    }
}

/**
 * Compress [data] to a standard gzip (RFC 1952) stream.
 *
 * Platform actuals must emit interchangeable gzip framing so a trace encoded on
 * one platform decodes on another (bundled assets cross JVM and Apple targets).
 */
internal expect fun gzipCompress(data: ByteArray): ByteArray

/** Inflate a standard gzip (RFC 1952) stream produced by [gzipCompress]. */
internal expect fun gzipDecompress(data: ByteArray): ByteArray
