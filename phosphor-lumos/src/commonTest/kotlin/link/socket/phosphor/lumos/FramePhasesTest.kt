package link.socket.phosphor.lumos

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.palette.AtmospherePresets

class FramePhasesTest {
    private val atmosphere =
        AtmospherePresets.IDLE.copy(
            pulseFrequency = 0.5f,
            patternSpeed = 0.7f,
            rotationX = 0.1f,
            rotationY = 0.45f,
        )

    @Test
    fun `advanced integrates each accumulator at the atmosphere's rate`() {
        val dt = 0.1f

        val next = FramePhases.ZERO.advanced(atmosphere, dt)

        assertEquals(dt * 0.5f * TWO_PI, next.pulsePhase, TOLERANCE, "pulsePhase")
        assertEquals(dt * 0.7f, next.patternPhase, TOLERANCE, "patternPhase")
        assertEquals(dt * 0.1f, next.orbRotationX, TOLERANCE, "orbRotationX")
        assertEquals(dt * 0.45f, next.orbRotationY, TOLERANCE, "orbRotationY")
    }

    @Test
    fun `advanced wraps each accumulator into its range`() {
        val fast = atmosphere.copy(pulseFrequency = 50f, patternSpeed = 50f, rotationX = 50f, rotationY = 50f)

        var phases = FramePhases.ZERO
        repeat(200) { phases = phases.advanced(fast, 0.5f) }

        assertInRange("pulsePhase", phases.pulsePhase, TWO_PI)
        assertInRange("patternPhase", phases.patternPhase, FramePhases.PATTERN_WRAP)
        assertInRange("orbRotationX", phases.orbRotationX, TWO_PI)
        assertInRange("orbRotationY", phases.orbRotationY, TWO_PI)
    }

    @Test
    fun `a negative dt rewinds and wraps back into range`() {
        val start = FramePhases(pulsePhase = 0.01f, patternPhase = 0.02f, orbRotationX = 0.03f, orbRotationY = 0.04f)

        val back = start.advanced(atmosphere, -0.5f)

        // Every accumulator sat closer to zero than half a second of its motion, so each wrapped.
        assertWrappedHigh("pulsePhase", back.pulsePhase, TWO_PI)
        assertWrappedHigh("patternPhase", back.patternPhase, FramePhases.PATTERN_WRAP)
        assertWrappedHigh("orbRotationX", back.orbRotationX, TWO_PI)
        assertWrappedHigh("orbRotationY", back.orbRotationY, TWO_PI)
        assertClose(start, back.advanced(atmosphere, 0.5f))
    }

    @Test
    fun `rewinding k steps lands where k fewer forward steps did`() {
        val dt = 1f / 24f
        var forward = FramePhases.ZERO
        repeat(40) { forward = forward.advanced(atmosphere, dt) }
        var expected = FramePhases.ZERO
        repeat(28) { expected = expected.advanced(atmosphere, dt) }

        assertClose(expected, forward.advanced(atmosphere, -12 * dt))
    }

    @Test
    fun `at lands where advancing from zero by the same time does`() {
        val seconds = 12.5

        assertClose(FramePhases.ZERO.advanced(atmosphere, seconds.toFloat()), FramePhases.at(atmosphere, seconds))
    }

    @Test
    fun `at is still one frame apart after days of clock`() {
        // A Float clock this old would have lost the frame step entirely; at() works in doubles.
        val days = 86_400.0 * 3
        val frame = 1.0 / 60.0

        val now = FramePhases.at(atmosphere, days)
        val next = FramePhases.at(atmosphere, days + frame)

        assertCircularlyClose(now.advanced(atmosphere, frame.toFloat()), next)
    }

    @Test
    fun `at wraps every accumulator into its range`() {
        val phases = FramePhases.at(atmosphere.copy(rotationX = -0.3f), 1_000.0)

        assertInRange("pulsePhase", phases.pulsePhase, TWO_PI)
        assertInRange("patternPhase", phases.patternPhase, FramePhases.PATTERN_WRAP)
        assertInRange("orbRotationX", phases.orbRotationX, TWO_PI)
        assertInRange("orbRotationY", phases.orbRotationY, TWO_PI)
    }

    private fun assertCircularlyClose(
        expected: FramePhases,
        actual: FramePhases,
    ) {
        assertCircular("pulsePhase", expected.pulsePhase, actual.pulsePhase, TWO_PI)
        assertCircular("patternPhase", expected.patternPhase, actual.patternPhase, FramePhases.PATTERN_WRAP)
        assertCircular("orbRotationX", expected.orbRotationX, actual.orbRotationX, TWO_PI)
        assertCircular("orbRotationY", expected.orbRotationY, actual.orbRotationY, TWO_PI)
    }

    // Distance the short way round, so a pair either side of the wrap still reads as close.
    private fun assertCircular(
        name: String,
        expected: Float,
        actual: Float,
        limit: Float,
    ) {
        val apart = abs(expected - actual) % limit
        val distance = minOf(apart, limit - apart)
        assertTrue(distance <= TOLERANCE, "$name: expected $expected, was $actual")
    }

    private fun assertClose(
        expected: FramePhases,
        actual: FramePhases,
    ) {
        assertEquals(expected.pulsePhase, actual.pulsePhase, TOLERANCE, "pulsePhase")
        assertEquals(expected.patternPhase, actual.patternPhase, TOLERANCE, "patternPhase")
        assertEquals(expected.orbRotationX, actual.orbRotationX, TOLERANCE, "orbRotationX")
        assertEquals(expected.orbRotationY, actual.orbRotationY, TOLERANCE, "orbRotationY")
    }

    private fun assertInRange(
        name: String,
        value: Float,
        limit: Float,
    ) {
        assertTrue(value >= 0f && value < limit + TOLERANCE, "$name should stay in [0, $limit), was $value")
    }

    private fun assertWrappedHigh(
        name: String,
        value: Float,
        limit: Float,
    ) {
        assertTrue(value > limit / 2f, "$name should wrap to the top of its range, was $value")
    }

    private companion object {
        const val TWO_PI: Float = (2.0 * PI).toFloat()
        const val TOLERANCE: Float = 1e-4f
    }
}
