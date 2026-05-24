package link.socket.phosphor.choreography

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmospherePattern
import link.socket.phosphor.timeline.Easing

class AtmosphereChoreographerTest {
    private val tolerance = 1e-4f

    @Test
    fun `update with no active transition returns currentState unchanged`() {
        val choreographer = AtmosphereChoreographer(AtmospherePresets.IDLE)

        val result = choreographer.update(0.1f)

        assertEquals(AtmospherePresets.IDLE, result)
        assertEquals(AtmospherePresets.IDLE, choreographer.currentState)
        assertNull(choreographer.activeTransition)
    }

    @Test
    fun `update with no active transition advances phases by dt times frequency`() {
        val choreographer = AtmosphereChoreographer(AtmospherePresets.IDLE)
        val expectedPulse = 0.25f * AtmospherePresets.IDLE.pulseFrequency
        val expectedPattern = 0.25f * AtmospherePresets.IDLE.patternSpeed

        choreographer.update(0.25f)

        assertEquals(expectedPulse, choreographer.pulsePhase, tolerance)
        assertEquals(expectedPattern, choreographer.patternPhase, tolerance)
    }

    @Test
    fun `setAtmosphere from idle to listening resolves to tabled spec`() {
        val choreographer = AtmosphereChoreographer(AtmospherePresets.IDLE)

        choreographer.setAtmosphere(AtmospherePresets.LISTENING, targetPresetName = "listening")

        val transition = assertNotNull(choreographer.activeTransition)
        assertEquals(0.6f, transition.durationSeconds, tolerance)
        assertEquals("eager", transition.easingName)
        assertEquals("idle", transition.fromPresetName)
        assertEquals("listening", transition.toPresetName)
    }

    @Test
    fun `update at half duration produces eased amplitude and linear crossfade weight`() {
        val choreographer = AtmosphereChoreographer(AtmospherePresets.IDLE)
        choreographer.setAtmosphere(AtmospherePresets.LISTENING, targetPresetName = "listening")

        val state = choreographer.update(0.3f)

        val linear = 0.5f
        val eased = Easing.eager(linear)
        val expectedPulseAmplitude =
            AtmospherePresets.IDLE.pulseAmplitude +
                (AtmospherePresets.LISTENING.pulseAmplitude - AtmospherePresets.IDLE.pulseAmplitude) * eased
        assertEquals(expectedPulseAmplitude, state.pulseAmplitude, tolerance)
        assertEquals(linear, choreographer.patternBlend, tolerance)
        val transition = assertNotNull(choreographer.activeTransition)
        assertEquals(linear, transition.progressLinear, tolerance)
        assertEquals(eased, transition.progressEased, tolerance)
        assertTrue(choreographer.pulsePhase > 0f)
        assertTrue(choreographer.patternPhase > 0f)
    }

    @Test
    fun `activeTransition is null before setAtmosphere and after completion`() {
        val choreographer = AtmosphereChoreographer(AtmospherePresets.IDLE)
        assertNull(choreographer.activeTransition)

        choreographer.setAtmosphere(AtmospherePresets.LISTENING, targetPresetName = "listening")
        assertNotNull(choreographer.activeTransition)

        choreographer.update(0.6f)
        assertNull(choreographer.activeTransition)
        assertEquals(AtmospherePresets.LISTENING, choreographer.currentState)
    }

    @Test
    fun `interrupting transition starts new transition from current interpolated state`() {
        val choreographer = AtmosphereChoreographer(AtmospherePresets.IDLE)
        choreographer.setAtmosphere(AtmospherePresets.LISTENING, targetPresetName = "listening")
        choreographer.update(0.3f)
        val midState = choreographer.currentState

        choreographer.setAtmosphere(AtmospherePresets.THINKING, targetPresetName = "thinking")

        val transition = assertNotNull(choreographer.activeTransition)
        assertEquals(midState, transition.from)
        assertEquals(AtmospherePresets.THINKING, transition.to)
        assertEquals("thinking", transition.toPresetName)
    }

    @Test
    fun `setAtmosphere does not reset phase accumulators`() {
        val choreographer = AtmosphereChoreographer(AtmospherePresets.IDLE)
        choreographer.update(0.5f)
        val pulseBefore = choreographer.pulsePhase
        val patternBefore = choreographer.patternPhase

        choreographer.setAtmosphere(AtmospherePresets.LISTENING, targetPresetName = "listening")

        assertEquals(pulseBefore, choreographer.pulsePhase, tolerance)
        assertEquals(patternBefore, choreographer.patternPhase, tolerance)
    }

    @Test
    fun `color crossfade snapshots set when bipolar transition involves distinct hues`() {
        val choreographer = AtmosphereChoreographer(AtmospherePresets.IDLE)

        choreographer.setAtmosphere(AtmospherePresets.UNCERTAIN, targetPresetName = "uncertain")

        val from = assertNotNull(choreographer.colorFromSnapshot)
        val to = assertNotNull(choreographer.colorToSnapshot)
        assertEquals(AtmospherePresets.IDLE.primaryHue, from.primaryHue, tolerance)
        assertEquals(AtmospherePresets.IDLE.bipolarStrength, from.bipolarStrength, tolerance)
        assertEquals(AtmospherePresets.UNCERTAIN.primaryHue, to.primaryHue, tolerance)
        assertEquals(AtmospherePresets.UNCERTAIN.bipolarStrength, to.bipolarStrength, tolerance)
    }

    @Test
    fun `color crossfade snapshots null when no endpoint has bipolar strength`() {
        val choreographer = AtmosphereChoreographer(AtmospherePresets.IDLE)

        choreographer.setAtmosphere(AtmospherePresets.LISTENING, targetPresetName = "listening")

        assertNull(choreographer.colorFromSnapshot)
        assertNull(choreographer.colorToSnapshot)
    }

    @Test
    fun `eager easing is front-loaded with expected value at quarter progress`() {
        val expected = 1f - (1f - 0.25f).pow(4f)
        assertEquals(expected, Easing.eager(0.25f), tolerance)
        assertEquals(0f, Easing.eager(0f), tolerance)
        assertEquals(1f, Easing.eager(1f), tolerance)
        assertTrue(Easing.eager(0.25f) > 0.25f, "eager should be ahead of linear early")
    }

    @Test
    fun `reluctant easing is back-loaded with expected value at three-quarter progress`() {
        val expected = 0.75f.pow(3.5f)
        assertEquals(expected, Easing.reluctant(0.75f), tolerance)
        assertEquals(0f, Easing.reluctant(0f), tolerance)
        assertEquals(1f, Easing.reluctant(1f), tolerance)
        assertTrue(Easing.reluctant(0.5f) < 0.5f, "reluctant should lag linear at midpoint")
    }

    @Test
    fun `overshoot easing exceeds target before settling`() {
        assertEquals(0f, Easing.overshoot(0f), tolerance)
        assertEquals(1f, Easing.overshoot(1f), tolerance)
        val late = Easing.overshoot(0.8f)
        assertTrue(late > 1f, "overshoot should exceed 1 before settling; got $late")
    }

    @Test
    fun `settled easing matches piecewise quadratic at known points`() {
        assertEquals(0f, Easing.settled(0f), tolerance)
        assertEquals(0.5f, Easing.settled(0.5f), tolerance)
        assertEquals(1f, Easing.settled(1f), tolerance)
        // At t=0.25 (first half): 2 * 0.25^2 = 0.125
        assertEquals(0.125f, Easing.settled(0.25f), tolerance)
        // At t=0.75 (second half): 1 - (-2*0.75 + 2)^2 / 2 = 1 - 0.5^2/2 = 0.875
        assertEquals(0.875f, Easing.settled(0.75f), tolerance)
    }

    @Test
    fun `all four named easings resolve via byName`() {
        assertNotNull(Easing.byName("eager"))
        assertNotNull(Easing.byName("reluctant"))
        assertNotNull(Easing.byName("overshoot"))
        assertNotNull(Easing.byName("settled"))
        assertNotNull(Easing.byName("EAGER"))
    }

    @Test
    fun `pattern-changing transition sets patternFrom and blends linearly`() {
        val choreographer = AtmosphereChoreographer(AtmospherePresets.IDLE)
        choreographer.setAtmosphere(AtmospherePresets.LISTENING, targetPresetName = "listening")

        assertEquals(AtmospherePattern.LONGITUDE, choreographer.patternFrom)

        choreographer.update(0.3f)
        assertEquals(AtmospherePattern.PLASMA, choreographer.currentState.pattern)
        assertEquals(0.5f, choreographer.patternBlend, tolerance)

        choreographer.update(0.3f)
        assertNull(choreographer.patternFrom)
        assertEquals(AtmospherePattern.PLASMA, choreographer.currentState.pattern)
    }

    @Test
    fun `non-pattern-changing transition leaves patternFrom null`() {
        val choreographer = AtmosphereChoreographer(AtmospherePresets.THINKING)

        choreographer.setAtmosphere(AtmospherePresets.UNCERTAIN, targetPresetName = "uncertain")

        assertNull(choreographer.patternFrom)
        assertEquals(AtmospherePattern.SPIRAL, choreographer.currentState.pattern)
    }

    @Test
    fun `unknown transition pair falls back to default spec`() {
        val choreographer = AtmosphereChoreographer(AtmospherePresets.IDLE)
        val customTarget = AtmospherePresets.IDLE.copy(pulseAmplitude = 0.99f)

        choreographer.setAtmosphere(customTarget)

        val transition = assertNotNull(choreographer.activeTransition)
        assertEquals(AtmosphereChoreographer.DefaultSpec.durationSeconds, transition.durationSeconds, tolerance)
        assertEquals(AtmosphereChoreographer.DefaultSpec.easingName, transition.easingName)
    }
}
