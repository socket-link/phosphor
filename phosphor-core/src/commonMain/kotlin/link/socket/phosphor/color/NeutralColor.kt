package link.socket.phosphor.color

import kotlin.jvm.JvmInline
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Platform-neutral color representation with normalized RGBA channels.
 *
 * Internally this is stored as packed 8-bit RGBA for stable equality and
 * deterministic round-trips through hex serialization.
 */
@JvmInline
value class NeutralColor private constructor(
    private val packedRgba: UInt,
) {
    val red: Float get() = redInt / CHANNEL_MAX_FLOAT
    val green: Float get() = greenInt / CHANNEL_MAX_FLOAT
    val blue: Float get() = blueInt / CHANNEL_MAX_FLOAT
    val alpha: Float get() = alphaInt / CHANNEL_MAX_FLOAT

    val r: Float get() = red
    val g: Float get() = green
    val b: Float get() = blue
    val a: Float get() = alpha

    val redInt: Int get() = ((packedRgba shr 24) and CHANNEL_MASK).toInt()
    val greenInt: Int get() = ((packedRgba shr 16) and CHANNEL_MASK).toInt()
    val blueInt: Int get() = ((packedRgba shr 8) and CHANNEL_MASK).toInt()
    val alphaInt: Int get() = (packedRgba and CHANNEL_MASK).toInt()

    fun toHex(includeAlpha: Boolean = true): String {
        val rgb = channelToHex(redInt) + channelToHex(greenInt) + channelToHex(blueInt)
        return if (includeAlpha) {
            "#$rgb${channelToHex(alphaInt)}"
        } else {
            "#$rgb"
        }
    }

    fun withAlpha(alpha: Float): NeutralColor = fromRgba(red, green, blue, alpha)

    companion object {
        internal const val CHANNEL_MAX_INT: Int = 255
        internal const val CHANNEL_MAX_FLOAT: Float = 255f
        private const val HUE_DEGREES: Float = 360f
        private val CHANNEL_MASK: UInt = 0xFFu

        val BLACK: NeutralColor = fromRgba(0f, 0f, 0f, 1f)
        val WHITE: NeutralColor = fromRgba(1f, 1f, 1f, 1f)
        val TRANSPARENT: NeutralColor = fromRgba(0f, 0f, 0f, 0f)

        fun fromRgba(
            red: Float,
            green: Float,
            blue: Float,
            alpha: Float = 1f,
        ): NeutralColor {
            val r = toChannel(red)
            val g = toChannel(green)
            val b = toChannel(blue)
            val a = toChannel(alpha)

            val packed =
                ((r.toUInt() and CHANNEL_MASK) shl 24) or
                    ((g.toUInt() and CHANNEL_MASK) shl 16) or
                    ((b.toUInt() and CHANNEL_MASK) shl 8) or
                    (a.toUInt() and CHANNEL_MASK)

            return NeutralColor(packed)
        }

        /**
         * Parse #RRGGBB or #RRGGBBAA (with or without leading #).
         */
        fun fromHex(hex: String): NeutralColor {
            val trimmed = hex.trim().removePrefix("#")
            require(trimmed.length == 6 || trimmed.length == 8) {
                "hex must be RRGGBB or RRGGBBAA, got '$hex'"
            }
            require(trimmed.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "hex contains invalid characters: '$hex'"
            }

            val r = trimmed.substring(0, 2).toInt(16)
            val g = trimmed.substring(2, 4).toInt(16)
            val b = trimmed.substring(4, 6).toInt(16)
            val a = if (trimmed.length == 8) trimmed.substring(6, 8).toInt(16) else CHANNEL_MAX_INT

            return fromRgba(
                red = r / CHANNEL_MAX_FLOAT,
                green = g / CHANNEL_MAX_FLOAT,
                blue = b / CHANNEL_MAX_FLOAT,
                alpha = a / CHANNEL_MAX_FLOAT,
            )
        }

        /**
         * Convert HSL values (h in degrees, s/l in 0..1) to normalized RGBA.
         */
        fun fromHsl(
            h: Float,
            s: Float,
            l: Float,
            a: Float = 1f,
        ): NeutralColor {
            val hue = ((h % HUE_DEGREES) + HUE_DEGREES) % HUE_DEGREES
            val saturation = s.coerceIn(0f, 1f)
            val lightness = l.coerceIn(0f, 1f)

            if (saturation == 0f) {
                return fromRgba(lightness, lightness, lightness, a)
            }

            val chroma = (1f - abs((2f * lightness) - 1f)) * saturation
            val huePrime = hue / 60f
            val x = chroma * (1f - abs((huePrime % 2f) - 1f))

            val (rPrime, gPrime, bPrime) =
                when {
                    huePrime < 1f -> Triple(chroma, x, 0f)
                    huePrime < 2f -> Triple(x, chroma, 0f)
                    huePrime < 3f -> Triple(0f, chroma, x)
                    huePrime < 4f -> Triple(0f, x, chroma)
                    huePrime < 5f -> Triple(x, 0f, chroma)
                    else -> Triple(chroma, 0f, x)
                }

            val m = lightness - (chroma / 2f)
            return fromRgba(
                red = (rPrime + m).coerceIn(0f, 1f),
                green = (gPrime + m).coerceIn(0f, 1f),
                blue = (bPrime + m).coerceIn(0f, 1f),
                alpha = a.coerceIn(0f, 1f),
            )
        }

        fun lerp(
            start: NeutralColor,
            end: NeutralColor,
            t: Float,
        ): NeutralColor {
            val clamped = t.coerceIn(0f, 1f)
            return fromRgba(
                red = interpolate(start.red, end.red, clamped),
                green = interpolate(start.green, end.green, clamped),
                blue = interpolate(start.blue, end.blue, clamped),
                alpha = interpolate(start.alpha, end.alpha, clamped),
            )
        }

        private fun interpolate(
            start: Float,
            end: Float,
            t: Float,
        ): Float = start + ((end - start) * t)

        private fun toChannel(value: Float): Int = (value.coerceIn(0f, 1f) * CHANNEL_MAX_FLOAT).roundToInt()

        private fun channelToHex(channel: Int): String = channel.toString(radix = 16).uppercase().padStart(2, '0')
    }
}
