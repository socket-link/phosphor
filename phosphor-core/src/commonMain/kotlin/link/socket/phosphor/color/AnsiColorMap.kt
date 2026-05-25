package link.socket.phosphor.color

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Maps OKLab colors to terminal-ready ANSI escape sequences.
 *
 * This is the low-level bridge from Lumos' perceptual voxel colors to terminal
 * foreground/background color wire formats. ANSI_256 mode searches the standard
 * xterm 256-color palette; TRUECOLOR mode emits 24-bit sRGB channels.
 */
object AnsiColorMap {
    private const val ESC = "\u001B["

    /**
     * Return a foreground color escape sequence for [color].
     */
    fun escape(
        color: OklabColor,
        mode: AnsiColorMode,
    ): String {
        val rgb = color.toPackedSrgb()
        return when (mode) {
            AnsiColorMode.ANSI_256 -> "${ESC}38;5;${Ansi256Palette.nearestIndex(rgb)}m"
            AnsiColorMode.TRUECOLOR -> "${ESC}38;2;${red(rgb)};${green(rgb)};${blue(rgb)}m"
        }
    }

    /**
     * Return a background color escape sequence for [color].
     */
    fun backgroundEscape(
        color: OklabColor,
        mode: AnsiColorMode,
    ): String {
        val rgb = color.toPackedSrgb()
        return when (mode) {
            AnsiColorMode.ANSI_256 -> "${ESC}48;5;${Ansi256Palette.nearestIndex(rgb)}m"
            AnsiColorMode.TRUECOLOR -> "${ESC}48;2;${red(rgb)};${green(rgb)};${blue(rgb)}m"
        }
    }

    /**
     * Return the nearest standard ANSI 256-color palette index for [color].
     */
    fun nearestPaletteIndex(color: OklabColor): Int = Ansi256Palette.nearestIndex(color.toPackedSrgb())
}

internal object Ansi256Palette {
    private const val PALETTE_SIZE = 256
    private const val COLOR_CUBE_START = 16
    private const val COLOR_CUBE_END = 231
    private const val GRAYSCALE_START = 232

    private val baseColors =
        intArrayOf(
            pack(red = 0, green = 0, blue = 0),
            pack(red = 128, green = 0, blue = 0),
            pack(red = 0, green = 128, blue = 0),
            pack(red = 128, green = 128, blue = 0),
            pack(red = 0, green = 0, blue = 128),
            pack(red = 128, green = 0, blue = 128),
            pack(red = 0, green = 128, blue = 128),
            pack(red = 192, green = 192, blue = 192),
            pack(red = 128, green = 128, blue = 128),
            pack(red = 255, green = 0, blue = 0),
            pack(red = 0, green = 255, blue = 0),
            pack(red = 255, green = 255, blue = 0),
            pack(red = 0, green = 0, blue = 255),
            pack(red = 255, green = 0, blue = 255),
            pack(red = 0, green = 255, blue = 255),
            pack(red = 255, green = 255, blue = 255),
        )

    private val colorCubeLevels = intArrayOf(0, 95, 135, 175, 215, 255)

    private val colors = IntArray(PALETTE_SIZE) { index -> packedColorAt(index) }

    val size: Int get() = colors.size

    fun packedAt(index: Int): Int = colors[index.coerceIn(0, 255)]

    fun neutralAt(index: Int): NeutralColor {
        val rgb = packedAt(index)
        return NeutralColor.fromRgba(
            red = red(rgb) / NeutralColor.CHANNEL_MAX_FLOAT,
            green = green(rgb) / NeutralColor.CHANNEL_MAX_FLOAT,
            blue = blue(rgb) / NeutralColor.CHANNEL_MAX_FLOAT,
            alpha = 1f,
        )
    }

    fun nearestIndex(color: NeutralColor): Int =
        nearestIndex(
            pack(
                red = color.redInt,
                green = color.greenInt,
                blue = color.blueInt,
            ),
        )

    fun nearestIndex(rgb: Int): Int {
        val targetR = red(rgb)
        val targetG = green(rgb)
        val targetB = blue(rgb)

        var bestIndex = 0
        var bestDistance = Long.MAX_VALUE

        for (index in colors.indices) {
            val candidate = colors[index]
            val dR = targetR - red(candidate)
            val dG = targetG - green(candidate)
            val dB = targetB - blue(candidate)
            val distance = ((dR * dR) + (dG * dG) + (dB * dB)).toLong()

            if (distance < bestDistance || (distance == bestDistance && index > bestIndex)) {
                bestDistance = distance
                bestIndex = index
            }
        }

        return bestIndex
    }

    private fun packedColorAt(index: Int): Int {
        if (index < baseColors.size) return baseColors[index]

        if (index in COLOR_CUBE_START..COLOR_CUBE_END) {
            val cubeIndex = index - COLOR_CUBE_START
            return pack(
                red = colorCubeLevels[cubeIndex / 36],
                green = colorCubeLevels[(cubeIndex % 36) / 6],
                blue = colorCubeLevels[cubeIndex % 6],
            )
        }

        val gray = 8 + ((index - GRAYSCALE_START) * 10)
        return pack(red = gray, green = gray, blue = gray)
    }
}

private fun OklabColor.toPackedSrgb(): Int {
    val lightness = lightness.toDouble()
    val a = a.toDouble()
    val b = b.toDouble()

    val lPrime = lightness + (0.3963377774 * a) + (0.2158037573 * b)
    val mPrime = lightness - (0.1055613458 * a) - (0.0638541728 * b)
    val sPrime = lightness - (0.0894841775 * a) - (1.2914855480 * b)

    val l = lPrime * lPrime * lPrime
    val m = mPrime * mPrime * mPrime
    val s = sPrime * sPrime * sPrime

    return pack(
        red = ((4.0767416621 * l) - (3.3077115913 * m) + (0.2309699292 * s)).toSrgbByte(),
        green = ((-1.2684380046 * l) + (2.6097574011 * m) - (0.3413193965 * s)).toSrgbByte(),
        blue = ((-0.0041960863 * l) - (0.7034186147 * m) + (1.7076147010 * s)).toSrgbByte(),
    )
}

private fun Double.toSrgbByte(): Int {
    val encoded =
        if (this <= 0.0031308) {
            12.92 * this
        } else {
            (1.055 * pow(1.0 / 2.4)) - 0.055
        }

    return (encoded.coerceIn(0.0, 1.0) * NeutralColor.CHANNEL_MAX_INT).roundToInt()
}

private fun pack(
    red: Int,
    green: Int,
    blue: Int,
): Int = (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

private fun red(rgb: Int): Int = (rgb shr 16) and 0xFF

private fun green(rgb: Int): Int = (rgb shr 8) and 0xFF

private fun blue(rgb: Int): Int = rgb and 0xFF
