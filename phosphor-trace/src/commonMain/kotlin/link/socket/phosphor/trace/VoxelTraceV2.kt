package link.socket.phosphor.trace

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelGlyphState

/**
 * Reads nothing but [formatVersion], so [TraceCodec.decode] can pick the wire
 * shape before committing to one. Every format carries the field under this
 * name; the codec's CBOR configuration ignores the rest of the payload.
 */
@Serializable
internal class FormatVersionProbe(
    val formatVersion: Int,
)

/**
 * The format-v2 wire shape of a [VoxelTrace]: the v1 header and per-frame
 * metadata unchanged, with the per-cell channels quantized to narrow integers.
 *
 * Every cell channel is one CBOR byte string spanning all frames. 16-bit
 * channels are stored as two **byte planes** — every high byte, then every low
 * byte — because gzip finds the slowly varying high bytes far cheaper than the
 * interleaved pairs. That layout alone is worth more than any choice of
 * reference lattice (PHO-42 recon: 5.20 → 3.14 B per voxel-frame for positions).
 *
 * Quantization is chosen so the parts of the output that are discrete come back
 * exactly, and only continuous position carries a bounded error:
 *
 * - **Position:** signed 16-bit multiples of [positionStep], a power of two in
 *   lattice units. Error is at most `positionStep / 2`.
 * - **Scale:** unsigned 16-bit multiples of `1 / 6561`. 6561 is `9 × 3⁶`, which
 *   puts every threshold of `CliLattice`'s ten-step luminance ramp on a cell
 *   edge, so a quantized scale never changes the ramp character. A positive
 *   scale never quantizes to zero, so no voxel appears or disappears.
 * - **Colour:** the byte Compose packs an sRGB float into,
 *   `(v * 255f + 0.5f).toInt()`, so the drawn colour is unchanged.
 * - **Alpha:** the same byte, or [TraceQuantization.ALPHA_NULL] for a null alpha.
 *   An empty [cellAlpha] means every alpha in the trace is null.
 *
 * Built and read only through [TraceQuantization]; the public type stays
 * [VoxelTrace] with float columns, so playback never sees this shape.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class VoxelTraceWireV2(
    val formatVersion: Int,
    val fps: Int,
    val frameCount: Int,
    val phosphorVersion: String,
    val seed: Long,
    val paramSnapshot: Map<String, String>,
    val staticLattice: StaticLattice,
    val segments: List<TraceSegment>,
    val createdAtEpochMs: Long,
    val ticks: LongArray,
    val timestampsEpochMillis: LongArray,
    val cellCounts: IntArray,
    val positionStep: Float,
    @ByteString val cellX: ByteArray,
    @ByteString val cellY: ByteArray,
    @ByteString val cellZ: ByteArray,
    @ByteString val cellScale: ByteArray,
    @ByteString val cellRed: ByteArray,
    @ByteString val cellGreen: ByteArray,
    @ByteString val cellBlue: ByteArray,
    @ByteString val cellAlpha: ByteArray,
    val ambient: List<VoxelAmbient>,
    val glyphs: List<VoxelGlyphState?>,
)

/** Converts between a float-column [VoxelTrace] and its [VoxelTraceWireV2] encoding. */
internal object TraceQuantization {
    /** Finest position step a writer uses, in lattice units: `2⁻¹²`. */
    const val DEFAULT_POSITION_STEP: Float = 1f / 4096f

    /** Scale grid: `9 × 3⁶`, so every luminance-ramp threshold sits on a cell edge. */
    const val SCALE_GRID: Int = 6561

    /** Largest encodable scale, `65535 / 6561 ≈ 9.99`. */
    const val MAX_SCALE: Float = 65535f / 6561f

    /** Alpha code for a null alpha. Real alphas occupy `0..255`. */
    const val ALPHA_NULL: Int = 0xFFFF

    private const val CHANNEL_MAX: Int = 255
    private const val UINT16_MAX: Int = 0xFFFF

    /**
     * Quantize [trace]'s float columns into the v2 wire shape.
     *
     * @throws IllegalArgumentException when a cell channel is not finite (a `NaN`
     *  alpha is the null sentinel and is allowed), or a scale exceeds [MAX_SCALE].
     */
    fun toWire(trace: VoxelTrace): VoxelTraceWireV2 {
        val n = trace.cellX.size
        var maxAbsPosition = 0f
        for (i in 0 until n) {
            val x = trace.cellX[i]
            val y = trace.cellY[i]
            val z = trace.cellZ[i]
            require(x.isFinite() && y.isFinite() && z.isFinite()) {
                "VoxelTrace v2 cannot encode a non-finite position at cell $i"
            }
            require(trace.cellScale[i].isFinite() && trace.cellScale[i] <= MAX_SCALE) {
                "VoxelTrace v2 encodes scales in 0..$MAX_SCALE; cell $i has ${trace.cellScale[i]}"
            }
            require(trace.cellRed[i].isFinite() && trace.cellGreen[i].isFinite() && trace.cellBlue[i].isFinite()) {
                "VoxelTrace v2 cannot encode a non-finite colour at cell $i"
            }
            require(!trace.cellAlpha[i].isInfinite()) {
                "VoxelTrace v2 cannot encode an infinite alpha at cell $i"
            }
            maxAbsPosition = maxOf(maxAbsPosition, abs(x), abs(y), abs(z))
        }

        val step = positionStepFor(maxAbsPosition)
        val anyAlpha = trace.cellAlpha.any { !it.isNaN() }

        return VoxelTraceWireV2(
            formatVersion = trace.formatVersion,
            fps = trace.fps,
            frameCount = trace.frameCount,
            phosphorVersion = trace.phosphorVersion,
            seed = trace.seed,
            paramSnapshot = trace.paramSnapshot,
            staticLattice = trace.staticLattice,
            segments = trace.segments,
            createdAtEpochMs = trace.createdAtEpochMs,
            ticks = trace.ticks,
            timestampsEpochMillis = trace.timestampsEpochMillis,
            cellCounts = trace.cellCounts,
            positionStep = step,
            cellX = planes16(n) { quantizePosition(trace.cellX[it], step) },
            cellY = planes16(n) { quantizePosition(trace.cellY[it], step) },
            cellZ = planes16(n) { quantizePosition(trace.cellZ[it], step) },
            cellScale = planes16(n) { quantizeScale(trace.cellScale[it]) },
            cellRed = ByteArray(n) { quantizeChannel(trace.cellRed[it]).toByte() },
            cellGreen = ByteArray(n) { quantizeChannel(trace.cellGreen[it]).toByte() },
            cellBlue = ByteArray(n) { quantizeChannel(trace.cellBlue[it]).toByte() },
            cellAlpha =
                if (anyAlpha) {
                    planes16(n) { quantizeAlpha(trace.cellAlpha[it]) }
                } else {
                    ByteArray(0)
                },
            ambient = trace.ambient,
            glyphs = trace.glyphs,
        )
    }

    /**
     * Expand a v2 wire payload back into float columns.
     *
     * @throws IllegalArgumentException when the payload is internally inconsistent
     *  (channel lengths that disagree with the cell counts, a non-positive step).
     */
    fun fromWire(wire: VoxelTraceWireV2): VoxelTrace {
        require(wire.frameCount >= 0 && wire.cellCounts.size == wire.frameCount) {
            "cellCounts has ${wire.cellCounts.size} entries for ${wire.frameCount} frames"
        }
        require(wire.cellCounts.all { it >= 0 }) { "cellCounts holds a negative count" }
        require(
            wire.ticks.size == wire.frameCount &&
                wire.timestampsEpochMillis.size == wire.frameCount &&
                wire.ambient.size == wire.frameCount &&
                wire.glyphs.size == wire.frameCount,
        ) { "Per-frame metadata does not match frameCount=${wire.frameCount}" }
        val total = wire.cellCounts.fold(0L) { sum, count -> sum + count }
        require(total <= Int.MAX_VALUE / 2) { "Trace declares $total cells, more than a v2 payload can hold" }
        val n = total.toInt()
        require(wire.positionStep.isFinite() && wire.positionStep > 0f) {
            "positionStep must be finite and > 0, was ${wire.positionStep}"
        }
        for ((name, channel) in listOf(
            "x" to wire.cellX,
            "y" to wire.cellY,
            "z" to wire.cellZ,
            "scale" to wire.cellScale,
        )) {
            require(channel.size == 2 * n) { "cell $name channel holds ${channel.size} bytes for $n cells" }
        }
        for ((name, channel) in listOf("red" to wire.cellRed, "green" to wire.cellGreen, "blue" to wire.cellBlue)) {
            require(channel.size == n) { "cell $name channel holds ${channel.size} bytes for $n cells" }
        }
        require(wire.cellAlpha.isEmpty() || wire.cellAlpha.size == 2 * n) {
            "cell alpha channel holds ${wire.cellAlpha.size} bytes for $n cells"
        }

        val step = wire.positionStep
        val alpha = wire.cellAlpha
        return VoxelTrace(
            formatVersion = wire.formatVersion,
            fps = wire.fps,
            frameCount = wire.frameCount,
            phosphorVersion = wire.phosphorVersion,
            seed = wire.seed,
            paramSnapshot = wire.paramSnapshot,
            staticLattice = wire.staticLattice,
            segments = wire.segments,
            createdAtEpochMs = wire.createdAtEpochMs,
            ticks = wire.ticks,
            timestampsEpochMillis = wire.timestampsEpochMillis,
            cellCounts = wire.cellCounts,
            cellX = FloatArray(n) { signed16(wire.cellX, n, it) * step },
            cellY = FloatArray(n) { signed16(wire.cellY, n, it) * step },
            cellZ = FloatArray(n) { signed16(wire.cellZ, n, it) * step },
            cellScale = FloatArray(n) { unsigned16(wire.cellScale, n, it).toFloat() / SCALE_GRID },
            cellRed = FloatArray(n) { (wire.cellRed[it].toInt() and 0xFF) / CHANNEL_MAX.toFloat() },
            cellGreen = FloatArray(n) { (wire.cellGreen[it].toInt() and 0xFF) / CHANNEL_MAX.toFloat() },
            cellBlue = FloatArray(n) { (wire.cellBlue[it].toInt() and 0xFF) / CHANNEL_MAX.toFloat() },
            cellAlpha =
                FloatArray(n) {
                    if (alpha.isEmpty()) {
                        Float.NaN
                    } else {
                        val code = unsigned16(alpha, n, it)
                        if (code == ALPHA_NULL) Float.NaN else code / CHANNEL_MAX.toFloat()
                    }
                },
            ambient = wire.ambient,
            glyphs = wire.glyphs,
        )
    }

    /**
     * The finest power-of-two step, no finer than [DEFAULT_POSITION_STEP], that
     * fits [maxAbs] in a signed 16-bit code. A Lumos orb spans about ±1.3
     * lattice units and always gets the default; anything wider than ±8 trades
     * precision for range rather than failing.
     */
    internal fun positionStepFor(maxAbs: Float): Float {
        var step = DEFAULT_POSITION_STEP
        while (maxAbs / step > Short.MAX_VALUE) step *= 2f
        return step
    }

    internal fun quantizePosition(
        value: Float,
        step: Float,
    ): Int = (value / step).roundToInt()

    internal fun quantizeScale(value: Float): Int =
        if (value <= 0f) {
            0
        } else {
            (value * SCALE_GRID).roundToInt().coerceIn(
                1,
                UINT16_MAX,
            )
        }

    internal fun quantizeChannel(value: Float): Int = (value * CHANNEL_MAX + 0.5f).toInt().coerceIn(0, CHANNEL_MAX)

    private fun quantizeAlpha(value: Float): Int = if (value.isNaN()) ALPHA_NULL else quantizeChannel(value)

    private inline fun planes16(
        n: Int,
        codeAt: (Int) -> Int,
    ): ByteArray {
        val out = ByteArray(2 * n)
        for (i in 0 until n) {
            val code = codeAt(i)
            out[i] = (code shr 8).toByte()
            out[n + i] = code.toByte()
        }
        return out
    }

    private fun signed16(
        planes: ByteArray,
        n: Int,
        i: Int,
    ): Int = ((planes[i].toInt() shl 8) or (planes[n + i].toInt() and 0xFF)).toShort().toInt()

    private fun unsigned16(
        planes: ByteArray,
        n: Int,
        i: Int,
    ): Int = ((planes[i].toInt() and 0xFF) shl 8) or (planes[n + i].toInt() and 0xFF)
}
