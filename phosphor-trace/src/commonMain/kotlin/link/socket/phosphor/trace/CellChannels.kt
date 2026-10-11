package link.socket.phosphor.trace

import link.socket.phosphor.lumos.VoxelCell

/**
 * Where a [VoxelTrace] keeps its per-cell channels.
 *
 * A trace built from frames, constructed directly, or decoded from v1 holds
 * [Floats] — its public float columns are the storage. A trace decoded from v2
 * or v3 holds [Quantized] — the wire codes, 11 bytes per voxel-frame (13 with an alpha
 * channel) against the 32 that float columns take — and dequantizes one cell at
 * a time as it is read.
 *
 * Everything in this module that walks cells — [TracePlayer], [VoxelTrace.toFrames],
 * [seamError] — reads through these accessors, so none of it materializes the
 * float columns. Only the public `VoxelTrace.cell*` properties do.
 */
internal sealed class CellChannels {
    /** Number of cells across all frames. */
    abstract val size: Int

    abstract fun x(i: Int): Float

    abstract fun y(i: Int): Float

    abstract fun z(i: Int): Float

    abstract fun scale(i: Int): Float

    abstract fun red(i: Int): Float

    abstract fun green(i: Int): Float

    abstract fun blue(i: Int): Float

    /** Alpha as a float column stores it: `NaN` for a null alpha. */
    abstract fun alpha(i: Int): Float

    /** Cell [i] as a `VoxelCell`, identical to slicing the float columns. */
    fun cellAt(i: Int): VoxelCell {
        val alpha = alpha(i)
        return VoxelCell(
            x = x(i),
            y = y(i),
            z = z(i),
            scale = scale(i),
            red = red(i),
            green = green(i),
            blue = blue(i),
            alpha = if (alpha.isNaN()) null else alpha,
        )
    }

    /** Float columns holding exactly the values the accessors return. */
    abstract fun toFloats(): Floats

    /**
     * Whether every channel holds the same float, bit for bit, as [other]'s —
     * the comparison `FloatArray.contentEquals` makes, whichever way each side
     * stores them.
     */
    fun contentEquals(other: CellChannels): Boolean {
        if (this is Floats && other is Floats) {
            return cellX.contentEquals(other.cellX) &&
                cellY.contentEquals(other.cellY) &&
                cellZ.contentEquals(other.cellZ) &&
                cellScale.contentEquals(other.cellScale) &&
                cellRed.contentEquals(other.cellRed) &&
                cellGreen.contentEquals(other.cellGreen) &&
                cellBlue.contentEquals(other.cellBlue) &&
                cellAlpha.contentEquals(other.cellAlpha)
        }
        return size == other.size &&
            sameChannel(other, CellChannels::x) &&
            sameChannel(other, CellChannels::y) &&
            sameChannel(other, CellChannels::z) &&
            sameChannel(other, CellChannels::scale) &&
            sameChannel(other, CellChannels::red) &&
            sameChannel(other, CellChannels::green) &&
            sameChannel(other, CellChannels::blue) &&
            sameChannel(other, CellChannels::alpha)
    }

    /**
     * One hash per channel, folded the way `FloatArray.contentHashCode` folds an
     * array, but computed here so [Floats] and [Quantized] holding the same
     * values hash alike on every platform.
     */
    fun contentHashCode(): Int {
        var result = 1
        for (channel in CHANNELS) {
            var hash = 1
            for (i in 0 until size) hash = 31 * hash + channel(this, i).toBits()
            result = 31 * result + hash
        }
        return result
    }

    private inline fun sameChannel(
        other: CellChannels,
        channel: (CellChannels, Int) -> Float,
    ): Boolean {
        for (i in 0 until size) {
            if (channel(this, i).toBits() != channel(other, i).toBits()) return false
        }
        return true
    }

    /** Full-precision columns: what [VoxelTrace.fromFrames] records and v1 decodes to. */
    class Floats(
        val cellX: FloatArray,
        val cellY: FloatArray,
        val cellZ: FloatArray,
        val cellScale: FloatArray,
        val cellRed: FloatArray,
        val cellGreen: FloatArray,
        val cellBlue: FloatArray,
        val cellAlpha: FloatArray,
    ) : CellChannels() {
        override val size: Int get() = cellX.size

        override fun x(i: Int): Float = cellX[i]

        override fun y(i: Int): Float = cellY[i]

        override fun z(i: Int): Float = cellZ[i]

        override fun scale(i: Int): Float = cellScale[i]

        override fun red(i: Int): Float = cellRed[i]

        override fun green(i: Int): Float = cellGreen[i]

        override fun blue(i: Int): Float = cellBlue[i]

        override fun alpha(i: Int): Float = cellAlpha[i]

        override fun toFloats(): Floats = this
    }

    /**
     * The v2 wire channels, kept as decoded: 16-bit channels as their two byte
     * planes, colour as one byte per cell, and an empty [cellAlpha] when every
     * alpha is null. [TraceCodec] re-encodes these arrays as they are, so a
     * decoded v2 trace re-encodes to identical bytes without being requantized.
     *
     * Positions are always absolute codes here, so any cell reads without its
     * neighbours in time. A v3 payload's previous-frame deltas are undone once
     * when it is decoded and redone into fresh arrays when it is encoded; these
     * arrays are never rewritten in place.
     *
     * Holding the planes rather than repacking them into `ShortArray`s costs the
     * same two bytes a value and skips a copy, so decoding peaks at the payload
     * plus these arrays.
     */
    class Quantized(
        val positionStep: Float,
        val cellX: ByteArray,
        val cellY: ByteArray,
        val cellZ: ByteArray,
        val cellScale: ByteArray,
        val cellRed: ByteArray,
        val cellGreen: ByteArray,
        val cellBlue: ByteArray,
        val cellAlpha: ByteArray,
    ) : CellChannels() {
        override val size: Int = cellRed.size

        override fun x(i: Int): Float = TraceQuantization.dequantizePosition(cellX, size, i, positionStep)

        override fun y(i: Int): Float = TraceQuantization.dequantizePosition(cellY, size, i, positionStep)

        override fun z(i: Int): Float = TraceQuantization.dequantizePosition(cellZ, size, i, positionStep)

        override fun scale(i: Int): Float = TraceQuantization.dequantizeScale(cellScale, size, i)

        override fun red(i: Int): Float = TraceQuantization.dequantizeChannel(cellRed[i])

        override fun green(i: Int): Float = TraceQuantization.dequantizeChannel(cellGreen[i])

        override fun blue(i: Int): Float = TraceQuantization.dequantizeChannel(cellBlue[i])

        override fun alpha(i: Int): Float =
            if (cellAlpha.isEmpty()) Float.NaN else TraceQuantization.dequantizeAlpha(cellAlpha, size, i)

        override fun toFloats(): Floats =
            Floats(
                cellX = FloatArray(size, ::x),
                cellY = FloatArray(size, ::y),
                cellZ = FloatArray(size, ::z),
                cellScale = FloatArray(size, ::scale),
                cellRed = FloatArray(size, ::red),
                cellGreen = FloatArray(size, ::green),
                cellBlue = FloatArray(size, ::blue),
                cellAlpha = FloatArray(size, ::alpha),
            )
    }

    private companion object {
        val CHANNELS: List<(CellChannels, Int) -> Float> =
            listOf(
                CellChannels::x,
                CellChannels::y,
                CellChannels::z,
                CellChannels::scale,
                CellChannels::red,
                CellChannels::green,
                CellChannels::blue,
                CellChannels::alpha,
            )
    }
}
