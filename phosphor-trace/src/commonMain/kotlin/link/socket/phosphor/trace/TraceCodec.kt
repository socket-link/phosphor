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
 * Three formats exist and all decode:
 *
 * - **v1** — every cell channel at full `Float` precision. Lossless.
 * - **v2** — cell channels quantized to narrow integers (see [VoxelTrace]).
 *   Colour and scale decode to exactly what `ComposeLattice` and `CliLattice`
 *   would draw from the originals; positions carry an error of at most `2⁻¹³`
 *   lattice units for a Lumos orb. About 3.5× smaller than v1 on real captures.
 * - **v3** — v2's quantization, with each frame's position codes stored as the
 *   difference from the previous frame's. Decodes to exactly the columns v2
 *   would, so it carries the same error, and is smaller again on real captures.
 *
 * [encode] writes the format named by the trace's own [VoxelTrace.formatVersion],
 * so everything [VoxelTrace.fromFrames] and `Oscilloscope` produce is written as
 * v3, while a decoded v1 or v2 trace re-encodes in its own format rather than
 * being converted behind its owner's back. Encoding v2 or v3 is idempotent: a
 * decoded trace re-encodes to identical bytes.
 *
 * Encoded traces use the `.vxt` file extension ([FILE_EXTENSION]).
 *
 * ## Platforms
 *
 * The JVM and Apple targets encode and decode, through zlib. The `js` and
 * `wasmJs` targets decode only: [encode] throws `UnsupportedOperationException`
 * there, so traces are recorded on the JVM or an Apple device and shipped to
 * the browser as `.vxt` assets.
 *
 * The browser's own gzip (`DecompressionStream`) is promise-based and [decode]
 * is synchronous, so the web inflates in Kotlin instead, and pays for it in
 * time: a 20 s, resolution-10 v3 trace (3.8 MB) decodes in roughly
 * 250–300 ms in Chrome and Safari against about 60 ms on the JVM, and nearly
 * all of the difference is the inflate. A `suspend` decode over
 * `DecompressionStream` would bring that down to tens of milliseconds; it is
 * not offered yet. Playback after decoding is about 0.2 ms a frame in a browser.
 *
 * A web consumer should depend on the JS artifact rather than the Wasm one when
 * it can. A Kotlin/JS `ByteArray` is an `Int8Array`, so fetched bytes reach
 * [decode] without a copy; Kotlin/Wasm has to copy them in one interop call per
 * byte, about 40 ms for the same 3.8 MB trace.
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
     *  build cannot write, or — for v2 and v3 — a cell channel is not finite or a scale
     *  exceeds the encodable range.
     * @throws UnsupportedOperationException on the `js` and `wasmJs` targets, which
     *  decode only. See the platform notes on [TraceCodec].
     */
    fun encode(trace: VoxelTrace): ByteArray = gzipCompress(encodePayload(trace))

    /**
     * The CBOR payload [encode] gzips, on every target. The web tests gzip it themselves
     * so the decode paths run there over the payloads the JVM and Apple targets write.
     */
    internal fun encodePayload(trace: VoxelTrace): ByteArray =
        when (trace.formatVersion) {
            FORMAT_V1 -> cbor.encodeToByteArray(VoxelTrace.serializer(), trace)
            FORMAT_V2, FORMAT_V3 ->
                cbor.encodeToByteArray(VoxelTraceWireV2.serializer(), TraceQuantization.toWire(trace))
            else -> throw IllegalArgumentException(
                "Cannot encode formatVersion=${trace.formatVersion}; this build writes " +
                    "v$FORMAT_V1–v$SUPPORTED_FORMAT_VERSION",
            )
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
                    // v2 and v3 share a wire shape; fromWire reads the version to undo v3's deltas.
                    else -> TraceQuantization.fromWire(cbor.decodeFromByteArray(VoxelTraceWireV2.serializer(), payload))
                },
            )
        } catch (error: Throwable) {
            Result.failure(TraceDecodeError.Corrupt("Failed to decode VoxelTrace v$version payload", error))
        }
    }

    private const val FORMAT_V1: Int = 1
    private const val FORMAT_V2: Int = 2
    private const val FORMAT_V3: Int = TraceQuantization.TEMPORAL_FORMAT_VERSION
}

/**
 * Compress [data] to a standard gzip (RFC 1952) stream.
 *
 * Platform actuals must emit interchangeable gzip framing so a trace encoded on
 * one platform decodes on another (bundled assets cross JVM, Apple and web
 * targets). The web actual throws: the web decodes only.
 */
internal expect fun gzipCompress(data: ByteArray): ByteArray

/** Inflate a standard gzip (RFC 1952) stream produced by [gzipCompress]. */
internal expect fun gzipDecompress(data: ByteArray): ByteArray
