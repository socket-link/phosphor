package link.socket.phosphor.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ColorRampTest {
    @Test
    fun `sample returns first stop at zero`() {
        val ramp =
            ColorRamp(
                listOf(
                    NeutralColor.fromHex("#000000"),
                    NeutralColor.fromHex("#FFFFFF"),
                ),
            )

        assertEquals(ramp.stops.first(), ramp.sample(0f))
    }

    @Test
    fun `sample returns last stop at one`() {
        val ramp =
            ColorRamp(
                listOf(
                    NeutralColor.fromHex("#000000"),
                    NeutralColor.fromHex("#FFFFFF"),
                ),
            )

        assertEquals(ramp.stops.last(), ramp.sample(1f))
    }

    @Test
    fun `sample interpolates midpoint`() {
        val ramp =
            ColorRamp(
                listOf(
                    NeutralColor.fromHex("#000000"),
                    NeutralColor.fromHex("#FFFFFF"),
                ),
            )

        val midpoint = ramp.sample(0.5f)
        assertEquals(128, midpoint.redInt)
        assertEquals(128, midpoint.greenInt)
        assertEquals(128, midpoint.blueInt)
    }

    @Test
    fun `sample clamps input bounds`() {
        val ramp =
            ColorRamp(
                listOf(
                    NeutralColor.fromHex("#111111"),
                    NeutralColor.fromHex("#EEEEEE"),
                ),
            )

        assertEquals(ramp.stops.first(), ramp.sample(-10f))
        assertEquals(ramp.stops.last(), ramp.sample(10f))
    }

    @Test
    fun `gradient builds inclusive stops`() {
        val start = NeutralColor.fromHex("#000000")
        val end = NeutralColor.fromHex("#FFFFFF")
        val ramp = ColorRamp.gradient(start, end, steps = 5)

        assertEquals(5, ramp.stops.size)
        assertEquals(start, ramp.stops.first())
        assertEquals(end, ramp.stops.last())
    }

    @Test
    fun `gradient with fewer than 2 steps throws`() {
        assertFailsWith<IllegalArgumentException> {
            ColorRamp.gradient(NeutralColor.BLACK, NeutralColor.WHITE, steps = 1)
        }
    }

    @Test
    fun `ramp with fewer than 2 stops throws`() {
        assertFailsWith<IllegalArgumentException> {
            ColorRamp(listOf(NeutralColor.BLACK))
        }
    }
}
