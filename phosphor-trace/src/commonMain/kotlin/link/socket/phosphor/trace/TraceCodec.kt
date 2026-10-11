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
     *
     * @property foundVersion The `formatVersion` the payload declares.
     * @property supportedVersion The newest version this build reads. Every
     *  version from 1 up to it is readable; see [TraceCodec.SUPPORTED_FORMAT_VERSIONS].
     */
    class UnsupportedFormatVersion(
        val foundVersion: Int,
        val supportedVersion: Int,
    ) : TraceDecodeError(
            "Unsupported trace formatVersion=$foundVersion (this build reads v1–v$supportedVersion)",
        )
}

/**
 * Round-trip serialization for [VoxelTrace]: CBOR for a compact binary encoding,
 * gzip for the payload, a versioned header, and Result-typed decoding.
 *
 * Two formats exist and both decode:
 *
 * - **v1** — every cell channel at full `Float` precision. Lossless.
 * - **v2** — cell channels quantized to narrow integers (see [VoxelTrace]).
 *   Colour and scale decode to exactly what `ComposeLattice` and `CliLattice`
 *   would draw from the originals; positions carry an error of at most `2⁻¹³`
 *   lattice units for a Lumos orb. About 3.5× smaller than v1 on real captures.
 *
 * [encode] writes the format named by the trace's own [VoxelTrace.formatVersion],
 * so everything [VoxelTrace.fromFrames] and `Oscilloscope` produce is written as
 * v2, while a decoded v1 trace re-encodes losslessly as v1 rather than being
 * quantized behind its owner's back. Encoding v2 is idempotent: a decoded v2
 * trace re-encodes to identical bytes.
 *
 * Encoded traces use the `.vxt` file extension ([FILE_EXTENSION]).
 */
@OptIn(ExperimentalSerializationApi::class)
object TraceCodec {
    /** File extension for an encoded trace payload. */
    const val FILE_EXTENSION: String = "vxt"

    /** The newest [VoxelTrace.formatVersion] this build decodes, and the one it writes for new traces. */
    const val SUPPORTED_FORMAT_VERSION: Int = VoxelTrace.CURRENT_FORMAT_VERSION

    /** Every [VoxelTrace.formatVersion] this build decodes. */
    val SUPPORTED_FORMAT_VERSIONS: IntRange = FORMAT_V1..SUPPORTED_FORMAT_VERSION

    private val cbor = Cbor { ignoreUnknownKeys = true }

    /**
     * Encode [trace] to a gzip-compressed CBOR `.vxt` payload in the format its
     * [VoxelTrace.formatVersion] names.
     *
     * @throws IllegalArgumentException when the trace declares a version this
     *  build cannot write, or — for v2 — a cell channel is not finite or a scale
     *  exceeds the encodable range.
     */
    fun encode(trace: VoxelTrace): ByteArray {
        val payload =
            when (trace.formatVersion) {
                FORMAT_V1 -> cbor.encodeToByteArray(VoxelTrace.serializer(), trace)
                FORMAT_V2 -> cbor.encodeToByteArray(VoxelTraceWireV2.serializer(), TraceQuantization.toWire(trace))
                else -> throw IllegalArgumentException(
                    "Cannot encode formatVersion=${trace.formatVersion}; this build writes " +
                        "v$FORMAT_V1–v$SUPPORTED_FORMAT_VERSION",
                )
            }
        return gzipCompress(payload)
    }

    /**
     * Decode a `.vxt` payload produced by [encode], in any supported format.
     *
     * @return [Result.success] with the trace, or [Result.failure] carrying a
     *  [TraceDecodeError]: [TraceDecodeError.UnsupportedFormatVersion] when the
     *  header declares an unknown version, or [TraceDecodeError.Corrupt] when the
     *  bytes cannot be un-gzipped or parsed.
     */
    fun decode(bytes: ByteArray): Result<VoxelTrace> {
        val payload: ByteArray
        val version: Int
        try {
            payload = gzipDecompress(bytes)
            version = cbor.decodeFromByteArray(FormatVersionProbe.serializer(), payload).formatVersion
        } catch (error: Throwable) {
            return Result.failure(TraceDecodeError.Corrupt("Failed to decode VoxelTrace payload", error))
        }

        if (version !in SUPPORTED_FORMAT_VERSIONS) {
            return Result.failure(TraceDecodeError.UnsupportedFormatVersion(version, SUPPORTED_FORMAT_VERSION))
        }

        return try {
            Result.success(
                when (version) {
                    FORMAT_V1 -> cbor.decodeFromByteArray(VoxelTrace.serializer(), payload)
                    else -> TraceQuantization.fromWire(cbor.decodeFromByteArray(VoxelTraceWireV2.serializer(), payload))
                },
            )
        } catch (error: Throwable) {
            Result.failure(TraceDecodeError.Corrupt("Failed to decode VoxelTrace v$version payload", error))
        }
    }

    private const val FORMAT_V1: Int = 1
    private const val FORMAT_V2: Int = 2
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
