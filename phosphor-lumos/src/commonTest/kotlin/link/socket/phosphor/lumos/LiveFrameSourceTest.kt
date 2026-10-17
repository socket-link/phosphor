package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.choreography.AtmosphereTransitionSpec
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AtmosphereState

class LiveFrameSourceTest {
    @Test
    fun `next steps the runtime and the builder together`() {
        val source = LiveFrameSource(scene())
        val runtime = CognitiveSceneRuntime(scene())
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)

        repeat(5) { assertEquals(builder.build(runtime.update(0.05f), 0.05f), source.next(0.05f)) }
    }

    @Test
    fun `an atmosphere alone makes a source`() {
        val source = LiveFrameSource(AtmospherePresets.THINKING)

        assertEquals("thinking", source.lookKey)
        assertEquals(AtmospherePresets.THINKING.resolution, source.next(0.05f).resolution)
        assertNull(source.runtime.particles, "only the atmosphere is simulated")
    }

    @Test
    fun `a scene without an atmosphere is rejected`() {
        assertFailsWith<IllegalArgumentException> { LiveFrameSource(SceneConfiguration(width = 8, height = 8)) }
    }

    @Test
    fun `a longer step moves the phases further and leaves the atmosphere's rates alone`() {
        // Speed reaches a source as nothing but a longer step: what speed 2 hands over at 20 fps.
        val source = LiveFrameSource(scene())

        source.next(0.1f)

        assertEquals(FramePhases.ZERO.advanced(THINKING, 0.1f), source.builder.phases)
        assertSame(THINKING, source.runtime.currentAtmosphere)
    }

    @Test
    fun `a still lands an atmosphere transition on its target at once`() {
        val source = LiveFrameSource(scene(initial = IDLE))
        source.next(0.05f)
        source.runtime.setAtmosphere(THINKING)

        val frame = source.still(0.016f)

        assertNull(source.runtime.atmosphereChoreographer?.activeTransition)
        assertSame(THINKING, source.runtime.currentAtmosphere)
        assertEquals(
            source.builder.buildSteadyState(THINKING, source.builder.phases, frame.tick, frame.timestampEpochMillis),
            frame,
        )
    }

    @Test
    fun `a still holds the phases while time passes`() {
        val source = LiveFrameSource(scene())
        source.next(0.2f)
        val held = source.builder.phases

        repeat(10) { source.still(0.1f) }

        assertEquals(held, source.builder.phases)
    }

    @Test
    fun `a held still is one instance`() {
        val source = LiveFrameSource(scene())

        assertSame(source.still(0.05f), source.still(0.05f))
    }

    @Test
    fun `revision moves when an atmosphere is set and not when a glyph fires`() {
        val source = LiveFrameSource(scene())
        val start = source.revision
        assertEquals(start, source.revision)

        source.builder.queueGlyph(LumosGlyph.CHECK)
        assertEquals(start, source.revision)

        source.runtime.setAtmosphere(IDLE)
        assertNotEquals(start, source.revision)
    }

    @Test
    fun `revision read after a still holds until the next atmosphere`() {
        // still() lands the transition, which moves the revision once more; read after, it settles.
        val source = LiveFrameSource(scene())
        source.runtime.setAtmosphere(IDLE)

        source.still(0f)
        val settled = source.revision
        source.still(0f)

        assertEquals(settled, source.revision)
    }

    @Test
    fun `lookKey names the preset the scene is in or heading to`() {
        val source = LiveFrameSource(scene(initial = AtmospherePresets.IDLE))
        assertEquals("idle", source.lookKey)

        source.runtime.setAtmosphere(AtmospherePresets.THINKING)
        assertEquals("thinking", source.lookKey)

        source.runtime.setAtmosphere(AtmospherePresets.THINKING.copy(glow = 0.3f), AtmosphereTransitionSpec.Immediate)
        assertNull(source.lookKey, "a custom atmosphere is no preset, so it keeps a clock of its own")
    }

    @Test
    fun `lookKey carries the builder's look when it is not BASE`() {
        val source = LiveFrameSource(scene(initial = AtmospherePresets.THINKING))

        source.builder.look = Look(LumosLook.WORKING, "gyro")
        assertEquals("thinking/working-gyro", source.lookKey)

        source.builder.look = Look(LumosLook.SEARCHING)
        assertEquals("thinking/searching", source.lookKey)

        source.builder.look = Look(LumosLook.BASE)
        assertEquals("thinking", source.lookKey, "BASE keeps the preset's key, so today's orbs share it")
    }

    @Test
    fun `align puts a steady scene on the clock's phase`() {
        val source = LiveFrameSource(scene())
        source.next(0.3f)

        source.align(42.0)

        assertEquals(FramePhases.at(THINKING, 42.0), source.builder.phases)
    }

    @Test
    fun `align leaves a scene alone mid-transition`() {
        val source = LiveFrameSource(scene())
        source.next(0.3f)
        source.runtime.setAtmosphere(IDLE)
        val before = source.builder.phases

        source.align(42.0)

        assertEquals(before, source.builder.phases)
    }

    @Test
    fun `an orb drawn late and aligned breathes with one that has been on screen all along`() {
        val early = LiveFrameSource(scene())
        early.align(0.0)
        repeat(20) { early.next(0.05f) }
        val late = LiveFrameSource(scene())
        late.align(1.0)

        early.next(0.05f)
        late.next(0.05f)

        assertPhasesClose(early.builder.phases, late.builder.phases)
    }

    private fun assertPhasesClose(
        expected: FramePhases,
        actual: FramePhases,
    ) {
        assertTrue(abs(expected.pulsePhase - actual.pulsePhase) < TOLERANCE, "pulse $expected vs $actual")
        assertTrue(abs(expected.patternPhase - actual.patternPhase) < TOLERANCE, "pattern $expected vs $actual")
        assertTrue(abs(expected.orbRotationX - actual.orbRotationX) < TOLERANCE, "rotationX $expected vs $actual")
        assertTrue(abs(expected.orbRotationY - actual.orbRotationY) < TOLERANCE, "rotationY $expected vs $actual")
    }

    private fun scene(initial: AtmosphereState = THINKING): SceneConfiguration =
        SceneConfiguration(
            width = 8,
            height = 8,
            enableWaveform = false,
            enableParticles = false,
            enableFlow = false,
            enableEmitters = false,
            enableCamera = false,
            enableAtmosphere = true,
            initialAtmosphere = initial,
        )

    private companion object {
        const val RESOLUTION = 5
        const val TOLERANCE = 1e-4f

        val IDLE: AtmosphereState = AtmospherePresets.IDLE.copy(resolution = RESOLUTION)
        val THINKING: AtmosphereState = AtmospherePresets.THINKING.copy(resolution = RESOLUTION)
    }
}
