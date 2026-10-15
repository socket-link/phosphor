package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.AtmosphereTransition
import link.socket.phosphor.signal.CognitivePhase

/**
 * PHO-53: [VoxelFrameBuilder.buildStill] is what reduced motion draws — the steady-state pose at
 * the builder's current phases, which it never advances, with a transition shown at its target
 * and a glyph at full strength for exactly its window.
 */
class VoxelFrameBuilderStillTest {
    @Test
    fun `a still with no transition and no glyph is the steady-state frame at the builder's phases`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        repeat(10) { builder.build(snapshot(THINKING), dt = 0.05f) }

        val still = builder.buildStill(snapshot(THINKING, frameIndex = 10L, elapsedSeconds = 0.5f), dt = 0.05f)

        assertEquals(builder.buildSteadyState(THINKING, builder.phases, tick = 10L, timestampEpochMillis = 500L), still)
    }

    @Test
    fun `a still never advances the phases however much time passes`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        repeat(5) { builder.build(snapshot(THINKING), dt = 0.05f) }
        val held = builder.phases

        repeat(20) { builder.buildStill(snapshot(THINKING), dt = 0.1f) }

        assertEquals(held, builder.phases)
    }

    @Test
    fun `a transition in flight is drawn at its target`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val transition =
            AtmosphereTransition(
                from = IDLE,
                to = THINKING,
                fromPresetName = "idle",
                toPresetName = "thinking",
                progressLinear = 0.3f,
                progressEased = 0.3f,
                easingName = "linear",
                durationSeconds = 1f,
            )

        val still = builder.buildStill(snapshot(atmosphere = IDLE, transition = transition), dt = 0.05f)

        assertEquals(builder.buildSteadyState(THINKING, builder.phases, tick = 0L, timestampEpochMillis = 0L), still)
    }

    @Test
    fun `a glyph is carved at full strength from the first frame of its window`() {
        // With every rate at zero the phases cannot move, so the only difference between the two
        // builders is how far into the glyph's envelope each one draws.
        val moving = VoxelFrameBuilder(initialResolution = RESOLUTION)
        moving.queueGlyph(LumosGlyph.CHECK, durationSeconds = 1f)
        val held = VoxelFrameBuilder(initialResolution = RESOLUTION)
        held.queueGlyph(LumosGlyph.CHECK, durationSeconds = 1f)

        val fadingIn = moving.build(snapshot(MOTIONLESS), dt = 0.1f)
        val still = held.buildStill(snapshot(MOTIONLESS), dt = 0.1f)
        val midWindow = VoxelFrameBuilder(initialResolution = RESOLUTION)
        midWindow.queueGlyph(LumosGlyph.CHECK, durationSeconds = 1f)
        val fullStrength = midWindow.build(snapshot(MOTIONLESS), dt = 0.5f)

        assertNotEquals(fadingIn.cells, still.cells, "a moving glyph is still fading in at 10% of its window")
        assertEquals(fullStrength, still)
        assertEquals(0.5f, assertNotNull(still.glyph).progress)
    }

    @Test
    fun `a held glyph leaves when its window closes`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.queueGlyph(LumosGlyph.STAR, durationSeconds = 0.5f)

        repeat(4) { assertNotNull(builder.buildStill(snapshot(THINKING), dt = 0.1f).glyph) }

        assertNull(builder.buildStill(snapshot(THINKING), dt = 0.1f).glyph)
        assertFalse(builder.hasActiveGlyph)
    }

    @Test
    fun `an unchanged still is the same instance`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)

        val first = builder.buildStill(snapshot(THINKING), dt = 0.1f)
        val second = builder.buildStill(snapshot(THINKING, frameIndex = 7L), dt = 0.1f)

        assertSame(first, second)
    }

    @Test
    fun `a still is rebuilt when its atmosphere or phases or glyph change`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val idle = builder.buildStill(snapshot(IDLE), dt = 0f)

        val thinking = builder.buildStill(snapshot(THINKING), dt = 0f)
        assertNotSame(idle, thinking)

        builder.phases = FramePhases(pulsePhase = 1f, patternPhase = 2f, orbRotationX = 0.5f, orbRotationY = 0.25f)
        val reanchored = builder.buildStill(snapshot(THINKING), dt = 0f)
        assertNotSame(thinking, reanchored)
        assertEquals(
            builder.buildSteadyState(THINKING, builder.phases, tick = 0L, timestampEpochMillis = 0L),
            reanchored,
        )

        builder.queueGlyph(LumosGlyph.HEART)
        assertNotSame(reanchored, builder.buildStill(snapshot(THINKING), dt = 0f))
    }

    @Test
    fun `a still follows the atmosphere's resolution`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)

        val finer = builder.buildStill(snapshot(THINKING.copy(resolution = RESOLUTION + 1)), dt = 0f)

        assertEquals(RESOLUTION + 1, finer.resolution)
        assertEquals(RESOLUTION + 1, builder.voxelSphere.resolution)
    }

    @Test
    fun `setting the phases re-anchors the next build`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val anchor = FramePhases(pulsePhase = 1f, patternPhase = 2f, orbRotationX = 0.5f, orbRotationY = 0.25f)

        builder.phases = anchor
        builder.build(snapshot(THINKING), dt = 0.1f)

        assertEquals(anchor.advanced(THINKING, 0.1f), builder.phases)
    }

    private fun snapshot(
        atmosphere: AtmosphereState,
        transition: AtmosphereTransition? = null,
        frameIndex: Long = 0L,
        elapsedSeconds: Float = 0f,
    ): SceneSnapshot =
        SceneSnapshot(
            frameIndex = frameIndex,
            elapsedTimeSeconds = elapsedSeconds,
            coordinateSpace = CoordinateSpace.WORLD_CENTERED,
            agentStates = emptyList(),
            substrateState = SubstrateState.create(2, 2),
            particleStates = emptyList(),
            flowConnections = emptyList(),
            flowField = null,
            waveformHeightField = null,
            waveformGridWidth = null,
            waveformGridDepth = null,
            cameraTransform = null,
            emitterStates = emptyList(),
            choreographyPhase = CognitivePhase.NONE,
            atmosphere = atmosphere,
            atmosphereTransition = transition,
        )

    private companion object {
        const val RESOLUTION = 5

        val IDLE: AtmosphereState = AtmospherePresets.IDLE.copy(resolution = RESOLUTION)
        val THINKING: AtmosphereState = AtmospherePresets.THINKING.copy(resolution = RESOLUTION)
        val MOTIONLESS: AtmosphereState =
            THINKING.copy(pulseFrequency = 0f, patternSpeed = 0f, rotationX = 0f, rotationY = 0f)
    }
}
