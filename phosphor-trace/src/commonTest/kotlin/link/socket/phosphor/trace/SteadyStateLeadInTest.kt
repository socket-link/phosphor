package link.socket.phosphor.trace

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import link.socket.phosphor.choreography.AtmosphereTransitionSpec
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AtmosphereState

/** [SignalGenerator.steadyStateLeadIn]: a loop's own past, synthesized rather than recorded (PHO-49). */
class SteadyStateLeadInTest {
    @Test
    fun `the lead-in reproduces the frames a settled simulation recorded`() {
        // THINKING rather than a bipolar preset: its colour is continuous in the
        // pattern mix, so a phase a few ulps off cannot flip a cell across a hue step.
        val generator = generator(AtmospherePresets.THINKING.copy(resolution = RESOLUTION))
        val recorded = List(RUN_FRAMES) { generator.nextFrame() }

        val leadIn = generator.steadyStateLeadIn(LEAD_IN)

        assertEquals(LEAD_IN, leadIn.size)
        leadIn.forEachIndexed { index, frame ->
            // The lead-in ends one step before the current frame, recorded.last().
            val expected = recorded[RUN_FRAMES - 1 - LEAD_IN + index]
            assertEquals(expected.tick, frame.tick, "tick of lead-in frame $index")
            assertTrue(
                abs(expected.timestampEpochMillis - frame.timestampEpochMillis) <= 1L,
                "timestamp of lead-in frame $index: ${frame.timestampEpochMillis} vs ${expected.timestampEpochMillis}",
            )
            assertEquals(expected.resolution, frame.resolution)
            assertCellsClose(expected.cells, frame.cells, "lead-in frame $index")
            assertEquals(expected.ambient.glowRed, frame.ambient.glowRed, "glow of lead-in frame $index")
            assertEquals(expected.ambient.glowGreen, frame.ambient.glowGreen, "glow of lead-in frame $index")
            assertEquals(expected.ambient.glowBlue, frame.ambient.glowBlue, "glow of lead-in frame $index")
            assertEquals(expected.ambient.glowIntensity, frame.ambient.glowIntensity, "glow of lead-in frame $index")
            val rotation = "rotation of lead-in frame $index"
            assertEquals(expected.ambient.orbRotationX, frame.ambient.orbRotationX, TOLERANCE, rotation)
            assertEquals(expected.ambient.orbRotationY, frame.ambient.orbRotationY, TOLERANCE, rotation)
        }
    }

    @Test
    fun `the lead-in is steady state even where the recording before it is a transition`() {
        val thinking = AtmospherePresets.THINKING.copy(resolution = RESOLUTION)
        val generator = generator(AtmospherePresets.IDLE.copy(resolution = RESOLUTION))
        repeat(LEAD_FRAMES) { generator.nextFrame() }
        // 0.8 s is 16 steps at 20 fps; one more so the transition has landed for certain.
        generator.setAtmosphere(thinking, AtmosphereTransitionSpec(durationSeconds = 0.8f, easingName = "easeOut"))
        val transition = List(17) { generator.nextFrame() }
        val loopFirst = generator.nextFrame()

        val leadIn = generator.steadyStateLeadIn(LEAD_IN)

        // Every lead-in frame carries the loop's own halo, bit for bit...
        leadIn.forEachIndexed { index, frame ->
            assertEquals(loopFirst.ambient.glowRed, frame.ambient.glowRed, "glow of lead-in frame $index")
            assertEquals(loopFirst.ambient.glowGreen, frame.ambient.glowGreen, "glow of lead-in frame $index")
            assertEquals(loopFirst.ambient.glowBlue, frame.ambient.glowBlue, "glow of lead-in frame $index")
        }
        // ...whereas the frames actually recorded in that span were still on their way there.
        val recordedBefore = transition.takeLast(LEAD_IN).first()
        assertNotEquals(loopFirst.ambient.glowRed, recordedBefore.ambient.glowRed)
    }

    @Test
    fun `the lead-in leaves the generator where it was`() {
        val atmosphere = AtmospherePresets.IDLE.copy(resolution = RESOLUTION)
        val generator = generator(atmosphere)
        val twin = generator(atmosphere)
        repeat(5) {
            generator.nextFrame()
            twin.nextFrame()
        }
        val before = generator.frameCount

        generator.steadyStateLeadIn(3)

        assertEquals(before, generator.frameCount)
        // The next frame is the one that would have come anyway.
        assertEquals(twin.nextFrame(), generator.nextFrame())
    }

    @Test
    fun `an empty lead-in is allowed and a negative one is not`() {
        val generator = generator(AtmospherePresets.IDLE.copy(resolution = RESOLUTION))
        generator.nextFrame()

        assertEquals(emptyList(), generator.steadyStateLeadIn(0))
        assertFailsWith<IllegalArgumentException> { generator.steadyStateLeadIn(-1) }
    }

    private fun generator(atmosphere: AtmosphereState): SignalGenerator =
        SignalGenerator(
            configuration =
                SceneConfiguration(
                    width = 1,
                    height = 1,
                    enableWaveform = false,
                    enableParticles = false,
                    enableFlow = false,
                    enableEmitters = false,
                    enableCamera = false,
                    enableAtmosphere = true,
                    initialAtmosphere = atmosphere,
                ),
            fps = FPS,
        )

    private fun assertCellsClose(
        expected: List<VoxelCell>,
        actual: List<VoxelCell>,
        label: String,
    ) {
        assertEquals(expected.size, actual.size, "$label cell count")
        expected.zip(actual).forEachIndexed { index, (want, got) ->
            assertEquals(want.x, got.x, TOLERANCE, "$label x of cell $index")
            assertEquals(want.y, got.y, TOLERANCE, "$label y of cell $index")
            assertEquals(want.z, got.z, TOLERANCE, "$label z of cell $index")
            assertEquals(want.scale, got.scale, TOLERANCE, "$label scale of cell $index")
            assertEquals(want.red, got.red, TOLERANCE, "$label red of cell $index")
            assertEquals(want.green, got.green, TOLERANCE, "$label green of cell $index")
            assertEquals(want.blue, got.blue, TOLERANCE, "$label blue of cell $index")
            assertEquals(want.alpha, got.alpha, "$label alpha of cell $index")
        }
    }

    private companion object {
        const val FPS = 20
        const val RESOLUTION = 6
        const val RUN_FRAMES = 40
        const val LEAD_FRAMES = 10
        const val LEAD_IN = 12
        const val TOLERANCE = 1e-4f
    }
}
