package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import link.socket.phosphor.field.VoxelSphere
import link.socket.phosphor.palette.AtmospherePresets

/**
 * Determinism proof for [Oscilloscope.capture] (PHO-36 Task C).
 *
 * The scenario below exercises an atmosphere transition (idle → thinking) so the
 * `AtmosphereChoreographer` interpolation path, not just steady state, is covered.
 */
class OscilloscopeDeterminismTest {
    private val resolution = 4

    private fun scenario(seed: Long = 7L): CaptureConfig =
        CaptureConfig(
            params = CaptureParams(atmosphere = AtmospherePresets.IDLE.copy(resolution = resolution)),
            seed = seed,
            durationMs = 1_000L,
            fps = 30,
            segments =
                listOf(
                    TraceSegment("idle", 0, 9, loop = true),
                    TraceSegment("idle→thinking", 10, 29),
                ),
            cues =
                listOf(
                    AtmosphereCue(
                        atFrame = 10,
                        atmosphere = AtmospherePresets.THINKING.copy(resolution = resolution),
                    ),
                ),
        )

    @Test
    fun `identical configs produce byte-identical encoded traces`() {
        val first = Oscilloscope.capture(scenario()).getOrThrow()
        val second = Oscilloscope.capture(scenario()).getOrThrow()

        assertEquals(first, second)
        assertContentEquals(TraceCodec.encode(first), TraceCodec.encode(second))
    }

    @Test
    fun `different seeds produce different encoded bytes`() {
        val seedA = Oscilloscope.capture(scenario(seed = 1L)).getOrThrow()
        val seedB = Oscilloscope.capture(scenario(seed = 2L)).getOrThrow()

        assertEquals(1L, seedA.seed)
        assertEquals(2L, seedB.seed)
        assertFalse(TraceCodec.encode(seedA).contentEquals(TraceCodec.encode(seedB)))
    }

    @Test
    fun `frame content is seed-invariant because the voxel path consumes no RNG`() {
        // Recon finding (PHO-36 Task A): SceneConfiguration.seed reaches the
        // substrate animator, flow layer and particle choreographer only, none of
        // which contribute to a VoxelFrame. If this test starts failing, a seeded
        // stochastic element has entered the voxel path: tighten the
        // seed-divergence test above to compare frame content, then delete this one.
        val seedA = Oscilloscope.capture(scenario(seed = 1L)).getOrThrow()
        val seedB = Oscilloscope.capture(scenario(seed = 2L)).getOrThrow()

        assertEquals(seedA.toFrames(), seedB.toFrames())
    }

    @Test
    fun `simulation is stepped at exactly one over fps with no wall-clock input`() {
        val config = scenario()
        val trace = Oscilloscope.capture(config).getOrThrow()

        val dt = 1f / config.fps
        var elapsed = 0f
        val expectedTimestamps =
            LongArray(config.frameCount) {
                elapsed += dt
                (elapsed * 1_000.0).toLong()
            }

        assertContentEquals(expectedTimestamps, trace.timestampsEpochMillis)
        assertContentEquals(LongArray(config.frameCount) { it + 1L }, trace.ticks)
        assertEquals(0L, trace.createdAtEpochMs)
    }

    @Test
    fun `cues change the atmosphere mid-capture`() {
        val frames = Oscilloscope.capture(scenario()).getOrThrow().toFrames()

        val before = frames[9].ambient
        val after = frames.last().ambient
        assertNotEquals(
            Triple(before.glowRed, before.glowGreen, before.glowBlue),
            Triple(after.glowRed, after.glowGreen, after.glowBlue),
            "ambient glow should move toward the THINKING hues after the cue",
        )
    }

    @Test
    fun `five second clip captures headlessly at full IDLE resolution`() {
        val config = CaptureConfig(durationMs = 5_000L, fps = 30)

        val trace = Oscilloscope.capture(config).getOrThrow()

        assertEquals(150, trace.frameCount)
        assertEquals(30, trace.fps)
        assertEquals(AtmospherePresets.IDLE.resolution, trace.staticLattice.resolution)
        val expectedCells = VoxelSphere(AtmospherePresets.IDLE.resolution).count
        assertTrue(trace.cellCounts.all { it == expectedCells })
        assertTrue(TraceCodec.decode(TraceCodec.encode(trace)).isSuccess)
    }
}
