package link.socket.phosphor.color

import kotlin.math.pow

/**
 * Linear RGB color with normalized channels.
 *
 * This is the linear-light form of Phosphor's sRGB [NeutralColor]. Use it as
 * the bridge between packed sRGB and [OklabColor] when a caller needs explicit
 * color-space conversion. For interpolation, prefer [NeutralColor.lerp] for
 * nearby hues and performance-sensitive paths; prefer [NeutralColor.lerpOklab]
 * for distant hues where perceptual uniformity matters.
 */
data class LinearRgbColor(
    val red: Float,
    val green: Float,
    val blue: Float,
) {
    /**
     * Convert this linear RGB color to packed sRGB.
     *
     * Channels outside the displayable sRGB gamut are clamped by [NeutralColor].
     */
    fun toSrgb(alpha: Float = 1f): NeutralColor =
        NeutralColor.fromRgba(
            red = linearToSrgb(red),
            green = linearToSrgb(green),
            blue = linearToSrgb(blue),
            alpha = alpha,
        )

    /**
     * Convert this linear RGB color to OKLab using Bjorn Ottosson's published matrices.
     */
    fun toOklab(): OklabColor = OklabColor.fromLinearRgb(this)

    companion object {
        /**
         * Decode a packed sRGB [NeutralColor] into linear-light RGB.
         */
        fun fromSrgb(color: NeutralColor): LinearRgbColor =
            LinearRgbColor(
                red = srgbToLinear(color.red),
                green = srgbToLinear(color.green),
                blue = srgbToLinear(color.blue),
            )
    }
}

/**
 * OKLab color in perceptually uniform L, a, b coordinates.
 *
 * OKLab interpolation is useful when moving between distant hues because equal
 * steps better match perceived color movement. Keep using [NeutralColor.lerp]
 * or [ColorRamp.sample] for nearby hues and hot render-loop code; use
 * [NeutralColor.lerpOklab] or [ColorRamp.sampleOklab] when clean perceptual
 * handoffs matter more than the extra conversion cost.
 */
data class OklabColor(
    val lightness: Float,
    val a: Float,
    val b: Float,
) {
    /**
     * Convert this OKLab color to linear RGB using Bjorn Ottosson's published matrices.
     */
    fun toLinearRgb(): LinearRgbColor {
        val lightness = lightness.toDouble()
        val a = a.toDouble()
        val b = b.toDouble()

        val lPrime = lightness + (0.3963377774 * a) + (0.2158037573 * b)
        val mPrime = lightness - (0.1055613458 * a) - (0.0638541728 * b)
        val sPrime = lightness - (0.0894841775 * a) - (1.2914855480 * b)

        val l = lPrime * lPrime * lPrime
        val m = mPrime * mPrime * mPrime
        val s = sPrime * sPrime * sPrime

        return LinearRgbColor(
            red = ((4.0767416621 * l) - (3.3077115913 * m) + (0.2309699292 * s)).toFloat(),
            green = ((-1.2684380046 * l) + (2.6097574011 * m) - (0.3413193965 * s)).toFloat(),
            blue = ((-0.0041960863 * l) - (0.7034186147 * m) + (1.7076147010 * s)).toFloat(),
        )
    }

    /**
     * Convert this OKLab color directly to packed sRGB.
     */
    fun toSrgb(alpha: Float = 1f): NeutralColor = toLinearRgb().toSrgb(alpha)

    companion object {
        /**
         * Convert packed sRGB to OKLab through linear RGB.
         */
        fun fromSrgb(color: NeutralColor): OklabColor = LinearRgbColor.fromSrgb(color).toOklab()

        /**
         * Convert linear RGB to OKLab using Bjorn Ottosson's published matrices.
         */
        fun fromLinearRgb(color: LinearRgbColor): OklabColor {
            val red = color.red.toDouble()
            val green = color.green.toDouble()
            val blue = color.blue.toDouble()

            val l = (0.4122214708 * red) + (0.5363325363 * green) + (0.0514459929 * blue)
            val m = (0.2119034982 * red) + (0.6806995451 * green) + (0.1073969566 * blue)
            val s = (0.0883024619 * red) + (0.2817188376 * green) + (0.6299787005 * blue)

            val lPrime = signedCubeRoot(l)
            val mPrime = signedCubeRoot(m)
            val sPrime = signedCubeRoot(s)

            return OklabColor(
                lightness = ((0.2104542553 * lPrime) + (0.7936177850 * mPrime) - (0.0040720468 * sPrime)).toFloat(),
                a = ((1.9779984951 * lPrime) - (2.4285922050 * mPrime) + (0.4505937099 * sPrime)).toFloat(),
                b = ((0.0259040371 * lPrime) + (0.7827717662 * mPrime) - (0.8086757660 * sPrime)).toFloat(),
            )
        }

        /**
         * Interpolate between two OKLab colors in perceptual coordinates.
         */
        fun lerp(
            start: OklabColor,
            end: OklabColor,
            t: Float,
        ): OklabColor {
            val clamped = t.coerceIn(0f, 1f)
            return OklabColor(
                lightness = interpolate(start.lightness, end.lightness, clamped),
                a = interpolate(start.a, end.a, clamped),
                b = interpolate(start.b, end.b, clamped),
            )
        }
    }
}

private fun srgbToLinear(channel: Float): Float {
    val value = channel.coerceIn(0f, 1f).toDouble()
    return if (value <= 0.04045) {
        (value / 12.92).toFloat()
    } else {
        (((value + 0.055) / 1.055).pow(2.4)).toFloat()
    }
}

private fun linearToSrgb(channel: Float): Float {
    val value = channel.toDouble()
    return if (value <= 0.0031308) {
        (12.92 * value).toFloat()
    } else {
        ((1.055 * value.pow(1.0 / 2.4)) - 0.055).toFloat()
    }
}

private fun signedCubeRoot(value: Double): Double =
    when {
        value < 0.0 -> -((-value).pow(1.0 / 3.0))
        value > 0.0 -> value.pow(1.0 / 3.0)
        else -> 0.0
    }

private fun interpolate(
    start: Float,
    end: Float,
    t: Float,
): Float = start + ((end - start) * t)
