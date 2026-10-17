package link.socket.phosphor.trace

/**
 * A dependency-free gzip (RFC 1952) reader in common Kotlin: RFC 1951 inflate following zlib's
 * `puff.c`, and the CRC32 the trailer is checked against.
 *
 * Only the web actual of [gzipDecompress] calls this. The browser's `DecompressionStream` is
 * promise-based and the codec's seam is synchronous, so the web inflates here instead; the JVM
 * and Apple targets keep zlib, which is faster. It lives in `commonMain` rather
 * than `webMain` so the JVM tests can hold it to `java.util.zip` byte for byte.
 *
 * Reads a single gzip member, which is all [gzipCompress] writes on any target. Malformed input
 * throws; [TraceCodec.decode] reports that as [TraceDecodeError.Corrupt].
 */
internal object PureGzip {
    fun gunzip(data: ByteArray): ByteArray {
        require(data.size >= 18) { "gzip stream too short: ${data.size} bytes" }
        require(data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) { "not a gzip stream" }
        require(data[2] == 8.toByte()) { "unsupported gzip compression method ${data[2]}" }
        val flags = data[3].toInt()
        var pos = 10
        if (flags and FEXTRA != 0) {
            val extraLength = (data[pos].toInt() and 0xff) or ((data[pos + 1].toInt() and 0xff) shl 8)
            pos += 2 + extraLength
        }
        if (flags and FNAME != 0) {
            while (data[pos] != 0.toByte()) pos++
            pos++
        }
        if (flags and FCOMMENT != 0) {
            while (data[pos] != 0.toByte()) pos++
            pos++
        }
        if (flags and FHCRC != 0) pos += 2
        val inflater = Inflater(data, pos)
        val out = inflater.inflate()
        val trailer = inflater.position
        require(trailer + 8 <= data.size) { "gzip trailer missing" }
        val expectedCrc = readIntLe(data, trailer)
        val expectedSize = readIntLe(data, trailer + 4)
        require(expectedSize == out.size) { "gzip ISIZE ${expectedSize.toUInt()} != inflated ${out.size}" }
        require(expectedCrc == crc32(out)) { "gzip CRC mismatch" }
        return out
    }

    fun crc32(data: ByteArray): Int {
        var crc = -1
        for (b in data) {
            crc = CRC_TABLE[(crc xor b.toInt()) and 0xff] xor (crc ushr 8)
        }
        return crc.inv()
    }

    private val CRC_TABLE =
        IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
            c
        }

    private fun readIntLe(
        data: ByteArray,
        at: Int,
    ): Int =
        (data[at].toInt() and 0xff) or
            ((data[at + 1].toInt() and 0xff) shl 8) or
            ((data[at + 2].toInt() and 0xff) shl 16) or
            ((data[at + 3].toInt() and 0xff) shl 24)

    private const val FHCRC = 2
    private const val FEXTRA = 4
    private const val FNAME = 8
    private const val FCOMMENT = 16

    /** RFC 1951 inflate over [input] from [position]; after [inflate], [position] is the first byte past the deflate stream. */
    private class Inflater(
        private val input: ByteArray,
        var position: Int,
    ) {
        private var bitBuffer = 0
        private var bitCount = 0
        private var out = ByteArray(maxOf(1024, input.size * 4))
        private var outLength = 0

        fun inflate(): ByteArray {
            var last: Int
            do {
                last = bits(1)
                when (bits(2)) {
                    0 -> stored()
                    1 -> codes(fixedLengthCode, fixedDistanceCode)
                    2 -> dynamic()
                    else -> throw IllegalStateException("invalid deflate block type")
                }
            } while (last == 0)
            // Discard the partial byte; the gzip trailer starts at the next whole byte.
            bitBuffer = 0
            bitCount = 0
            return out.copyOf(outLength)
        }

        private fun bits(need: Int): Int {
            var value = bitBuffer
            while (bitCount < need) {
                if (position >= input.size) throw IllegalStateException("deflate stream truncated")
                value = value or ((input[position++].toInt() and 0xff) shl bitCount)
                bitCount += 8
            }
            bitBuffer = value ushr need
            bitCount -= need
            return value and ((1 shl need) - 1)
        }

        private fun stored() {
            bitBuffer = 0
            bitCount = 0
            if (position + 4 > input.size) throw IllegalStateException("deflate stream truncated")
            val length = (input[position].toInt() and 0xff) or ((input[position + 1].toInt() and 0xff) shl 8)
            val complement = (input[position + 2].toInt() and 0xff) or ((input[position + 3].toInt() and 0xff) shl 8)
            position += 4
            if (length != (complement.inv() and 0xffff)) throw IllegalStateException("stored block length mismatch")
            if (position + length > input.size) throw IllegalStateException("deflate stream truncated")
            ensure(length)
            input.copyInto(out, outLength, position, position + length)
            position += length
            outLength += length
        }

        private fun dynamic() {
            val literalCount = bits(5) + 257
            val distanceCount = bits(5) + 1
            val codeLengthCount = bits(4) + 4
            if (literalCount > MAX_LITERAL_CODES || distanceCount > MAX_DISTANCE_CODES) {
                throw IllegalStateException("bad dynamic block counts")
            }
            val lengths = IntArray(MAX_LITERAL_CODES + MAX_DISTANCE_CODES)
            for (i in 0 until codeLengthCount) lengths[CODE_LENGTH_ORDER[i]] = bits(3)
            val lengthCode = Huffman(lengths, 19)

            var index = 0
            while (index < literalCount + distanceCount) {
                val symbol = lengthCode.decode()
                if (symbol < 16) {
                    lengths[index++] = symbol
                } else {
                    var repeatValue = 0
                    val runLength: Int
                    when (symbol) {
                        16 -> {
                            if (index == 0) throw IllegalStateException("repeat with no previous length")
                            repeatValue = lengths[index - 1]
                            runLength = 3 + bits(2)
                        }
                        17 -> runLength = 3 + bits(3)
                        else -> runLength = 11 + bits(7)
                    }
                    if (index + runLength > literalCount + distanceCount) {
                        throw IllegalStateException("too many lengths")
                    }
                    repeat(runLength) { lengths[index++] = repeatValue }
                }
            }
            if (lengths[256] == 0) throw IllegalStateException("no end-of-block code")
            val literal = Huffman(lengths.copyOfRange(0, literalCount), literalCount)
            val distance = Huffman(lengths.copyOfRange(literalCount, literalCount + distanceCount), distanceCount)
            codes(literal, distance)
        }

        private fun codes(
            literal: Huffman,
            distance: Huffman,
        ) {
            while (true) {
                var symbol = literal.decode()
                if (symbol < 256) {
                    ensure(1)
                    out[outLength++] = symbol.toByte()
                } else if (symbol == 256) {
                    return
                } else {
                    symbol -= 257
                    if (symbol >= 29) throw IllegalStateException("invalid length code")
                    val length = LENGTH_BASE[symbol] + bits(LENGTH_EXTRA[symbol])
                    val distanceSymbol = distance.decode()
                    if (distanceSymbol >= 30) throw IllegalStateException("invalid distance code")
                    val dist = DISTANCE_BASE[distanceSymbol] + bits(DISTANCE_EXTRA[distanceSymbol])
                    if (dist > outLength) throw IllegalStateException("distance too far back")
                    ensure(length)
                    var from = outLength - dist
                    repeat(length) { out[outLength++] = out[from++] }
                }
            }
        }

        private fun ensure(more: Int) {
            if (outLength + more <= out.size) return
            var size = out.size
            while (outLength + more > size) size *= 2
            out = out.copyOf(size)
        }

        /** Canonical Huffman decoding table, decoded bit by bit as `puff.c` does. */
        private inner class Huffman(
            lengths: IntArray,
            n: Int,
        ) {
            private val count = IntArray(MAX_BITS + 1)
            private val symbol = IntArray(n)

            init {
                for (i in 0 until n) count[lengths[i]]++
                val offsets = IntArray(MAX_BITS + 1)
                for (len in 1 until MAX_BITS) offsets[len + 1] = offsets[len] + count[len]
                for (i in 0 until n) if (lengths[i] != 0) symbol[offsets[lengths[i]]++] = i
            }

            fun decode(): Int {
                var code = 0
                var first = 0
                var index = 0
                for (len in 1..MAX_BITS) {
                    code = code or bits(1)
                    val c = count[len]
                    if (code - c < first) return symbol[index + (code - first)]
                    index += c
                    first += c
                    first = first shl 1
                    code = code shl 1
                }
                throw IllegalStateException("invalid Huffman code")
            }
        }

        private val fixedLengthCode: Huffman by lazy {
            val lengths = IntArray(288)
            for (i in 0 until 144) lengths[i] = 8
            for (i in 144 until 256) lengths[i] = 9
            for (i in 256 until 280) lengths[i] = 7
            for (i in 280 until 288) lengths[i] = 8
            Huffman(lengths, 288)
        }

        private val fixedDistanceCode: Huffman by lazy { Huffman(IntArray(30) { 5 }, 30) }
    }

    private const val MAX_BITS = 15
    private const val MAX_LITERAL_CODES = 286
    private const val MAX_DISTANCE_CODES = 30

    private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
    private val LENGTH_BASE =
        intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195,
            227, 258,
        )
    private val LENGTH_EXTRA =
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DISTANCE_BASE =
        intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097,
            6145, 8193, 12289, 16385, 24577,
        )
    private val DISTANCE_EXTRA =
        intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
}
