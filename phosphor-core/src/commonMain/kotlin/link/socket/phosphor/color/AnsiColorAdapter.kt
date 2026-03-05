package link.socket.phosphor.color

/**
 * ANSI output mode.
 */
enum class AnsiColorMode {
    ANSI_256,
    TRUE_COLOR,
}

/**
 * Adapts neutral colors to ANSI terminal escape sequences.
 *
 * In ANSI_256 mode, colors are quantized to the nearest ANSI palette index.
 * In TRUE_COLOR mode, exact RGB values are emitted using 24-bit escapes.
 */
class AnsiColorAdapter(
    private val mode: AnsiColorMode = AnsiColorMode.ANSI_256,
) : PlatformColorAdapter<String> {
    override fun adapt(color: NeutralColor): String = foreground(color)

    fun foreground(color: NeutralColor): String {
        return when (mode) {
            AnsiColorMode.ANSI_256 -> foregroundEscapeForCode(ansi256Code(color))
            AnsiColorMode.TRUE_COLOR -> {
                "${ESC}38;2;${color.redInt};${color.greenInt};${color.blueInt}m"
            }
        }
    }

    fun background(color: NeutralColor): String {
        return when (mode) {
            AnsiColorMode.ANSI_256 -> backgroundEscapeForCode(ansi256Code(color))
            AnsiColorMode.TRUE_COLOR -> {
                "${ESC}48;2;${color.redInt};${color.greenInt};${color.blueInt}m"
            }
        }
    }

    fun ansi256Code(color: NeutralColor): Int = Ansi256Palette.nearestIndex(color)

    fun neutralFromAnsi256(index: Int): NeutralColor = ansi256ToNeutral(index)

    companion object {
        private const val ESC = "\u001B["
        const val RESET = "${ESC}0m"

        val DEFAULT: AnsiColorAdapter = AnsiColorAdapter()

        fun ansi256ToNeutral(index: Int): NeutralColor = Ansi256Palette.colorAt(index)

        fun foregroundEscapeForCode(code: Int): String = "${ESC}38;5;${code.coerceIn(0, 255)}m"

        fun backgroundEscapeForCode(code: Int): String = "${ESC}48;5;${code.coerceIn(0, 255)}m"
    }
}

private object Ansi256Palette {
    private val baseColors =
        arrayOf(
            intArrayOf(0, 0, 0),
            intArrayOf(128, 0, 0),
            intArrayOf(0, 128, 0),
            intArrayOf(128, 128, 0),
            intArrayOf(0, 0, 128),
            intArrayOf(128, 0, 128),
            intArrayOf(0, 128, 128),
            intArrayOf(192, 192, 192),
            intArrayOf(128, 128, 128),
            intArrayOf(255, 0, 0),
            intArrayOf(0, 255, 0),
            intArrayOf(255, 255, 0),
            intArrayOf(0, 0, 255),
            intArrayOf(255, 0, 255),
            intArrayOf(0, 255, 255),
            intArrayOf(255, 255, 255),
        )

    private val colorCubeLevels = intArrayOf(0, 95, 135, 175, 215, 255)

    private val colors: List<NeutralColor> = List(256) { index -> colorAtIndex(index) }

    fun colorAt(index: Int): NeutralColor = colors[index.coerceIn(0, 255)]

    fun nearestIndex(color: NeutralColor): Int {
        val targetR = color.redInt
        val targetG = color.greenInt
        val targetB = color.blueInt

        var bestIndex = 0
        var bestDistance = Long.MAX_VALUE

        for (index in colors.indices) {
            val candidate = colors[index]
            val dR = targetR - candidate.redInt
            val dG = targetG - candidate.greenInt
            val dB = targetB - candidate.blueInt
            val distance = ((dR * dR) + (dG * dG) + (dB * dB)).toLong()

            if (distance < bestDistance || (distance == bestDistance && index > bestIndex)) {
                bestDistance = distance
                bestIndex = index
            }
        }

        return bestIndex
    }

    private fun colorAtIndex(index: Int): NeutralColor {
        if (index < baseColors.size) {
            val base = baseColors[index]
            return NeutralColor.fromRgba(
                red = base[0] / NeutralColor.CHANNEL_MAX_FLOAT,
                green = base[1] / NeutralColor.CHANNEL_MAX_FLOAT,
                blue = base[2] / NeutralColor.CHANNEL_MAX_FLOAT,
                alpha = 1f,
            )
        }

        if (index in 16..231) {
            val cubeIndex = index - 16
            val red = colorCubeLevels[cubeIndex / 36]
            val green = colorCubeLevels[(cubeIndex % 36) / 6]
            val blue = colorCubeLevels[cubeIndex % 6]
            return NeutralColor.fromRgba(
                red = red / NeutralColor.CHANNEL_MAX_FLOAT,
                green = green / NeutralColor.CHANNEL_MAX_FLOAT,
                blue = blue / NeutralColor.CHANNEL_MAX_FLOAT,
                alpha = 1f,
            )
        }

        val gray = 8 + ((index - 232) * 10)
        return NeutralColor.fromRgba(
            red = gray / NeutralColor.CHANNEL_MAX_FLOAT,
            green = gray / NeutralColor.CHANNEL_MAX_FLOAT,
            blue = gray / NeutralColor.CHANNEL_MAX_FLOAT,
            alpha = 1f,
        )
    }
}
