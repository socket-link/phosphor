package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase

/**
 * PHO-58: the look pass in [VoxelFrameBuilder]. [LumosLook.BASE] without
 * [LumosRenderConfig.rimFade] is the frame the builder produced before looks
 * existed; every other look modulates in view space on the look's clock.
 */
class VoxelFrameBuilderLookTest {
    @Test
    fun `the builder wears BASE by default and emits no alpha`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)

        assertEquals(Look(LumosLook.BASE), builder.look)
        val frame = builder.build(snapshot(THINKING), dt = 0.1f)
        assertTrue(frame.cells.all { it.alpha == null }, "BASE without rimFade carries no alpha")
    }

    @Test
    fun `BASE without rimFade is bit-identical to a builder that never wore a look`() {
        val reference = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val wearer = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val expected = (1..5).map { reference.build(snapshot(THINKING), dt = 0.05f) }

        // Wear a look, then take it off: the null-modulator path is the original loop.
        wearer.look = Look(LumosLook.WORKING)
        wearer.look = Look(LumosLook.BASE)
        val actual = (1..5).map { wearer.build(snapshot(THINKING), dt = 0.05f) }

        assertEquals(expected, actual)
        assertEquals(reference.phases, wearer.phases)
    }

    @Test
    fun `switching back to BASE restores the look-free frame at the same phases`() {
        val reference = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val wearer = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val phases =
            FramePhases(
                pulsePhase = 0.4f,
                patternPhase = 1.1f,
                orbRotationX = 0.2f,
                orbRotationY = 0.9f,
                lookSeconds = 3.3,
            )

        wearer.look = Look(LumosLook.SEARCHING)
        val searching = wearer.buildSteadyState(THINKING, phases, tick = 1L, timestampEpochMillis = 100L)
        wearer.look = Look(LumosLook.BASE)
        val restored = wearer.buildSteadyState(THINKING, phases, tick = 1L, timestampEpochMillis = 100L)

        assertNotEquals(reference.buildSteadyState(THINKING, phases, tick = 1L, timestampEpochMillis = 100L), searching)
        assertEquals(reference.buildSteadyState(THINKING, phases, tick = 1L, timestampEpochMillis = 100L), restored)
    }

    @Test
    fun `rimFade on BASE fades the back of the sphere and leaves colour and position alone`() {
        val plain = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val faded = VoxelFrameBuilder(initialResolution = RESOLUTION, config = LumosRenderConfig(rimFade = true))
        val sample = Forms.Lattice(RESOLUTION).sample(0, LumosLook.BASE, 0f)
        val front = (0 until sample.count).maxByOrNull { sample.positions[it].z }!!
        val back = (0 until sample.count).minByOrNull { sample.positions[it].z }!!

        val reference = plain.buildSteadyState(THINKING, FramePhases.ZERO, tick = 0L, timestampEpochMillis = 0L)
        val frame = faded.buildSteadyState(THINKING, FramePhases.ZERO, tick = 0L, timestampEpochMillis = 0L)

        assertEquals(reference.cells.size, frame.cells.size)
        assertNull(frame.cells[front].alpha, "the front is opaque, so it carries no alpha")
        assertEquals(0f, frame.cells[back].alpha, "the back is invisible")
        assertEquals(reference.cells[front].scale, frame.cells[front].scale, 1e-6f, "the front keeps its size")
        assertTrue(frame.cells[back].scale < reference.cells[back].scale, "the back shrinks")
        for (i in reference.cells.indices) {
            val expected = reference.cells[i]
            val actual = frame.cells[i]
            assertEquals(expected.red, actual.red, "red of $i")
            assertEquals(expected.green, actual.green, "green of $i")
            assertEquals(expected.blue, actual.blue, "blue of $i")
            assertEquals(expected.x, actual.x, "x of $i")
            assertEquals(expected.y, actual.y, "y of $i")
            assertEquals(expected.z, actual.z, "z of $i")
        }
        assertEquals(reference.ambient, frame.ambient)
    }

    @Test
    fun `a look's modulation follows the look clock`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.look = Look(LumosLook.WORKING)
        val base = FramePhases(pulsePhase = 0f, patternPhase = 0f, orbRotationX = 0f, orbRotationY = 0f)

        fun at(lookSeconds: Double) = builder.buildSteadyState(THINKING, base.copy(lookSeconds = lookSeconds), 0L, 0L)
        val early = at(0.6)
        val later = at(1.1)
        val again = at(0.6)

        assertNotEquals(early.cells, later.cells, "the ring has moved on")
        assertEquals(early, again, "the same clock draws the same frame")
        assertTrue(early.cells.any { it.alpha != null }, "the rim fade is on under every look")
    }

    @Test
    fun `build advances the look clock by dt and align re-anchors it`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.build(snapshot(THINKING), dt = 0.25f)
        builder.build(snapshot(THINKING), dt = 0.25f)
        assertEquals(0.5, builder.phases.lookSeconds, 1e-6)

        builder.phases = FramePhases.at(THINKING, 42.0)
        assertEquals(42.0, builder.phases.lookSeconds, 0.0)
    }

    @Test
    fun `a lit cell moves toward white`() {
        val plain = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val walking = VoxelFrameBuilder(initialResolution = RESOLUTION)
        walking.look = Look(LumosLook.REASONING)
        val sample = Forms.Lattice(RESOLUTION).sample(0, LumosLook.BASE, 0f)
        val head = (0 until sample.count).maxByOrNull { sample.positions[it].z }!!

        val reference = plain.buildSteadyState(THINKING, FramePhases.ZERO, tick = 0L, timestampEpochMillis = 0L)
        val frame = walking.buildSteadyState(THINKING, FramePhases.ZERO, tick = 0L, timestampEpochMillis = 0L)

        val before = reference.cells[head]
        val after = frame.cells[head]
        assertTrue(
            after.red + after.green + after.blue > before.red + before.green + before.blue,
            "the walk's head is lit: $before -> $after",
        )
        assertNull(after.alpha, "the head is fully opaque")
    }

    @Test
    fun `the lens moves cells it covers`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.look = Look(LumosLook.SEARCHING)
        val reference = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val phases = FramePhases.ZERO.copy(lookSeconds = 0.9)

        val plain = reference.buildSteadyState(THINKING, phases, tick = 0L, timestampEpochMillis = 0L)
        val lensed = builder.buildSteadyState(THINKING, phases, tick = 0L, timestampEpochMillis = 0L)

        val moved =
            plain.cells.indices.count { i ->
                plain.cells[i].x != lensed.cells[i].x || plain.cells[i].y != lensed.cells[i].y
            }
        assertTrue(moved > 0, "cells inside the lens are swelled and spread")
        assertTrue(moved < plain.cells.size / 2, "cells outside the lens stay put: $moved of ${plain.cells.size} moved")
    }

    @Test
    fun `a still under a look holds the look too`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.look = Look(LumosLook.WORKING)

        val first = builder.buildStill(snapshot(THINKING), dt = 0.1f)
        val second = builder.buildStill(snapshot(THINKING), dt = 0.1f)

        assertTrue(first.cells.any { it.alpha != null })
        assertEquals(first, second, "nothing moved, so the still is the same frame")
    }

    private fun snapshot(atmosphere: AtmosphereState?): SceneSnapshot =
        SceneSnapshot(
            frameIndex = 0L,
            elapsedTimeSeconds = 0f,
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
            atmosphereTransition = null,
        )

    private companion object {
        const val RESOLUTION = 4
        val THINKING: AtmosphereState = AtmospherePresets.THINKING.copy(resolution = RESOLUTION)
    }
}
