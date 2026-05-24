package link.socket.phosphor.color

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import link.socket.phosphor.signal.CognitivePhase

class OklabColorTest {
    @Test
    fun `srgb to oklab to srgb preserves sampled colors`() {
        val colors =
            listOf(
                NeutralColor.BLACK,
                NeutralColor.WHITE,
                NeutralColor.fromHex("#FF0000"),
                NeutralColor.fromHex("#00FF00"),
                NeutralColor.fromHex("#0000FF"),
                NeutralColor.fromHex("#3366CC"),
                NeutralColor.fromHex("#C89A65"),
                NeutralColor.fromHex("#8C46AF"),
                NeutralColor.fromHex("#22334480"),
            )

        colors.forEach { color ->
            val roundTripped = OklabColor.fromSrgb(color).toSrgb(alpha = color.alpha)

            assertColorClose(color, roundTripped, tolerance = 1e-4f)
        }
    }

    @Test
    fun `oklab midpoint avoids linear rgb amber to purple midpoint`() {
        val amber = NeutralColor.fromHex("#C89A65")
        val purple = NeutralColor.fromHex("#8C46AF")

        val linearMidpoint = NeutralColor.lerp(amber, purple, 0.5f)
        val perceptualMidpoint = NeutralColor.lerpOklab(amber, purple, 0.5f)

        assertEquals("#AA708AFF", linearMidpoint.toHex())
        assertEquals("#A87592FF", perceptualMidpoint.toHex())
        assertNotEquals(linearMidpoint, perceptualMidpoint)
    }

    @Test
    fun `oklab lerp preserves identity color`() {
        val red = NeutralColor.fromHex("#FF0000")

        assertEquals(red, NeutralColor.lerpOklab(red, red, 0.5f))
    }

    @Test
    fun `oklab lerp from black to white produces perceptual neutral grey`() {
        val midpoint = NeutralColor.lerpOklab(NeutralColor.BLACK, NeutralColor.WHITE, 0.5f)

        assertEquals(midpoint.redInt, midpoint.greenInt)
        assertEquals(midpoint.greenInt, midpoint.blueInt)
        assertEquals("#636363FF", midpoint.toHex())
    }

    @Test
    fun `ColorRamp sample remains linear rgb for existing ramps`() {
        val ramps =
            listOf(
                CognitiveColorModel.phaseRampFor(CognitivePhase.PERCEIVE),
                CognitiveColorModel.phaseRampFor(CognitivePhase.EXECUTE),
                CognitiveColorModel.confidenceRamp,
                CognitiveColorModel.flowIntensityRamp,
            )
        val samples = listOf(-1f, 0f, 0.125f, 0.5f, 0.875f, 1f, 2f)

        ramps.forEach { ramp ->
            samples.forEach { t ->
                assertEquals(expectedLinearSample(ramp, t), ramp.sample(t))
            }
        }
    }

    @Test
    fun `ColorRamp sampleOklab uses perceptual interpolation`() {
        val ramp =
            ColorRamp(
                stops =
                    listOf(
                        NeutralColor.fromHex("#C89A65"),
                        NeutralColor.fromHex("#8C46AF"),
                    ),
            )

        assertEquals(NeutralColor.lerpOklab(ramp.stops.first(), ramp.stops.last(), 0.5f), ramp.sampleOklab(0.5f))
    }

    private fun expectedLinearSample(
        ramp: ColorRamp,
        t: Float,
    ): NeutralColor {
        val clamped = t.coerceIn(0f, 1f)
        if (clamped <= 0f) return ramp.stops.first()
        if (clamped >= 1f) return ramp.stops.last()

        val lastIndex = ramp.stops.lastIndex
        val scaled = clamped * lastIndex
        val lower = scaled.toInt().coerceIn(0, lastIndex)
        val upper = (lower + 1).coerceIn(0, lastIndex)

        if (lower == upper) return ramp.stops[lower]

        val localT = scaled - lower
        val start = ramp.stops[lower]
        val end = ramp.stops[upper]

        return NeutralColor.fromRgba(
            red = interpolate(start.red, end.red, localT),
            green = interpolate(start.green, end.green, localT),
            blue = interpolate(start.blue, end.blue, localT),
            alpha = interpolate(start.alpha, end.alpha, localT),
        )
    }

    private fun interpolate(
        start: Float,
        end: Float,
        t: Float,
    ): Float = start + ((end - start) * t)

    private fun assertColorClose(
        expected: NeutralColor,
        actual: NeutralColor,
        tolerance: Float,
    ) {
        assertTrue(abs(expected.red - actual.red) <= tolerance, "red expected ${expected.red}, got ${actual.red}")
        assertTrue(
            abs(expected.green - actual.green) <= tolerance,
            "green expected ${expected.green}, got ${actual.green}",
        )
        assertTrue(abs(expected.blue - actual.blue) <= tolerance, "blue expected ${expected.blue}, got ${actual.blue}")
        assertTrue(
            abs(expected.alpha - actual.alpha) <= tolerance,
            "alpha expected ${expected.alpha}, got ${actual.alpha}",
        )
    }
}
