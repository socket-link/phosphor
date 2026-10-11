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
 * Built and read only through [TraceQuantization]. A decoded trace keeps these
 * channel arrays as its [CellChannels.Quantized] backing, so the public type
 * stays [VoxelTrace] and playback never sees this shape.
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

/** Converts between a [VoxelTrace] and its [VoxelTraceWireV2] encoding, and dequantizes single cells. */
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
     * Quantize [trace]'s float columns into the v2 wire shape. A trace already
     * holding [CellChannels.Quantized] channels writes them as they are.
     *
     * @throws IllegalArgumentException when a cell channel is not finite (a `NaN`
     *  alpha is the null sentinel and is allowed), or a scale exceeds [MAX_SCALE].
     */
    fun toWire(trace: VoxelTrace): VoxelTraceWireV2 =
        when (val cells = trace.cells) {
            is CellChannels.Floats -> quantize(trace, cells)
            is CellChannels.Quantized ->
                wireOf(
                    trace,
                    cells.positionStep,
                    cells.cellX,
                    cells.cellY,
                    cells.cellZ,
                    cells.cellScale,
                    cells.cellRed,
                    cells.cellGreen,
                    cells.cellBlue,
                    cells.cellAlpha,
                )
        }

    private fun quantize(
        trace: VoxelTrace,
        cells: CellChannels.Floats,
    ): VoxelTraceWireV2 {
        val n = cells.size
        var maxAbsPosition = 0f
        for (i in 0 until n) {
            val x = cells.cellX[i]
            val y = cells.cellY[i]
            val z = cells.cellZ[i]
            require(x.isFinite() && y.isFinite() && z.isFinite()) {
                "VoxelTrace v2 cannot encode a non-finite position at cell $i"
            }
            require(cells.cellScale[i].isFinite() && cells.cellScale[i] <= MAX_SCALE) {
                "VoxelTrace v2 encodes scales in 0..$MAX_SCALE; cell $i has ${cells.cellScale[i]}"
            }
            require(cells.cellRed[i].isFinite() && cells.cellGreen[i].isFinite() && cells.cellBlue[i].isFinite()) {
                "VoxelTrace v2 cannot encode a non-finite colour at cell $i"
            }
            require(!cells.cellAlpha[i].isInfinite()) {
                "VoxelTrace v2 cannot encode an infinite alpha at cell $i"
            }
            maxAbsPosition = maxOf(maxAbsPosition, abs(x), abs(y), abs(z))
        }

        val step = positionStepFor(maxAbsPosition)
        val anyAlpha = cells.cellAlpha.any { !it.isNaN() }

        return wireOf(
            trace,
            step,
            cellX = planes16(n) { quantizePosition(cells.cellX[it], step) },
            cellY = planes16(n) { quantizePosition(cells.cellY[it], step) },
            cellZ = planes16(n) { quantizePosition(cells.cellZ[it], step) },
            cellScale = planes16(n) { quantizeScale(cells.cellScale[it]) },
            cellRed = ByteArray(n) { quantizeChannel(cells.cellRed[it]).toByte() },
            cellGreen = ByteArray(n) { quantizeChannel(cells.cellGreen[it]).toByte() },
            cellBlue = ByteArray(n) { quantizeChannel(cells.cellBlue[it]).toByte() },
            cellAlpha =
                if (anyAlpha) {
                    planes16(n) { quantizeAlpha(cells.cellAlpha[it]) }
                } else {
                    ByteArray(0)
                },
        )
    }

    @Suppress("LongParameterList")
    private fun wireOf(
        trace: VoxelTrace,
        positionStep: Float,
        cellX: ByteArray,
        cellY: ByteArray,
        cellZ: ByteArray,
        cellScale: ByteArray,
        cellRed: ByteArray,
        cellGreen: ByteArray,
        cellBlue: ByteArray,
        cellAlpha: ByteArray,
    ): VoxelTraceWireV2 =
        VoxelTraceWireV2(
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
            positionStep = positionStep,
            cellX = cellX,
            cellY = cellY,
            cellZ = cellZ,
            cellScale = cellScale,
            cellRed = cellRed,
            cellGreen = cellGreen,
            cellBlue = cellBlue,
            cellAlpha = cellAlpha,
            ambient = trace.ambient,
            glyphs = trace.glyphs,
        )

    /**
     * Read a v2 wire payload as a [VoxelTrace] that keeps the quantized channels
     * as its backing. Nothing is expanded to floats here: playback dequantizes
     * the cells of the one frame it builds.
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
            cells =
                CellChannels.Quantized(
                    positionStep = wire.positionStep,
                    cellX = wire.cellX,
                    cellY = wire.cellY,
                    cellZ = wire.cellZ,
                    cellScale = wire.cellScale,
                    cellRed = wire.cellRed,
                    cellGreen = wire.cellGreen,
                    cellBlue = wire.cellBlue,
                    cellAlpha = wire.cellAlpha,
                ),
            ambient = wire.ambient,
            glyphs = wire.glyphs,
        )
    }

    // Dequantization. Each is the expression v2 decoding has always used, so a
    // cell read through CellChannels.Quantized is bit-identical to the float
    // column PHO-42's decoder built from the same bytes.

    /** Position code [i] of an [n]-cell plane pair, in lattice units. */
    fun dequantizePosition(
        planes: ByteArray,
        n: Int,
        i: Int,
        step: Float,
    ): Float = signed16(planes, n, i) * step

    /** Scale code [i] of an [n]-cell plane pair. */
    fun dequantizeScale(
        planes: ByteArray,
        n: Int,
        i: Int,
    ): Float = unsigned16(planes, n, i).toFloat() / SCALE_GRID

    /** An 8-bit sRGB colour byte. */
    fun dequantizeChannel(code: Byte): Float = (code.toInt() and 0xFF) / CHANNEL_MAX.toFloat()

    /** Alpha code [i] of an [n]-cell plane pair; `NaN` for [ALPHA_NULL]. */
    fun dequantizeAlpha(
        planes: ByteArray,
        n: Int,
        i: Int,
    ): Float {
        val code = unsigned16(planes, n, i)
        return if (code == ALPHA_NULL) Float.NaN else code / CHANNEL_MAX.toFloat()
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
