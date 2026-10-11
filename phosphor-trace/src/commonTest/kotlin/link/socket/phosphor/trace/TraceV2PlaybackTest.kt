package link.socket.phosphor.trace

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import link.socket.phosphor.field.VoxelSphere
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.palette.AtmospherePresets

/**
 * A decoded v2 or v3 trace stays quantized in memory and is dequantized a frame
 * at a time, but plays exactly as a float trace of the same values would. These
 * pin that down on traces that went through the current codec (v3, which decodes
 * to exactly v2's channels): held-frame identity, blend alignment, and crossfades.
 */
class TraceV2PlaybackTest {
    @Test
    fun `a player over a decoded v2 trace still hands back the identical instance on a held frame`() {
        val decoded = roundTrip(realCapture())
        val player = TracePlayer(decoded)

        val first = player.frameAt(0.milliseconds)
        val held = player.frameAt(player.frameDuration / 2)

        assertSame(first, held)
    }

    @Test
    fun `cell index i is the same lattice voxel in every decoded v2 frame`() {
        // A blend pairs cells by index. v3's deltas pair cell i with cell i of the
        // previous frame and decode to absolute positions in lattice order, so
        // decoded cell i sits within half a quantization step
        // of the voxel it was recorded from — far inside half a lattice spacing.
        val trace = realCapture()
        val decoded = roundTrip(trace)
        val halfStep = TraceTestData.positionStepOf(trace) / 2f
        val halfLatticeSpacing = 0.5f / AtmospherePresets.IDLE.resolution

        assertTrue(halfStep < halfLatticeSpacing / 100f)
        val original = trace.toFrames()
        val replayed = decoded.toFrames()
        val lattice = VoxelSphere(AtmospherePresets.IDLE.resolution).count
        for (frameIndex in original.indices) {
            assertEquals(lattice, replayed[frameIndex].cells.size)
            original[frameIndex].cells.zip(replayed[frameIndex].cells).forEachIndexed { i, (recorded, decodedCell) ->
                assertTrue(
                    abs(recorded.x - decodedCell.x) <= halfStep,
                    "cell $i moved off its voxel in frame $frameIndex",
                )
                assertTrue(
                    abs(recorded.y - decodedCell.y) <= halfStep,
                    "cell $i moved off its voxel in frame $frameIndex",
                )
                assertTrue(
                    abs(recorded.z - decodedCell.z) <= halfStep,
                    "cell $i moved off its voxel in frame $frameIndex",
                )
            }
        }

        val blend = FrameBlend(replayed.first(), replayed.last(), weight = 0.5f)
        assertTrue(blend.isAligned)
        assertEquals(lattice, blend.transduce().cells.size)
    }

    @Test
    fun `a crossfade over a decoded v2 trace tracks the same crossfade over the original`() {
        val original = StateMachineTraces.threeState()
        val decoded = roundTrip(original)
        val policy = TransitionPolicy.Crossfade(durationMs = 500)
        val fromOriginal = TraceStateMachine(original, "idle", policy)
        val fromDecoded = TraceStateMachine(decoded, "idle", policy)
        val tolerance = TraceTestData.positionStepOf(original) / 2f + BLEND_ROUNDING

        // No recorded idle→uncertain segment, so the policy fires.
        fromOriginal.requestState("uncertain").getOrThrow()
        fromDecoded.requestState("uncertain").getOrThrow()

        var sawAlignedBlend = false
        repeat(12) { step ->
            val expected = fromOriginal.advance(100.milliseconds)
            val actual = fromDecoded.advance(100.milliseconds)
            fromDecoded.blend?.let { blend ->
                assertTrue(blend.isAligned, "decoded heads unpaired at step $step")
                sawAlignedBlend = true
            }
            assertCellsClose(expected, actual, tolerance, step)
        }

        assertTrue(sawAlignedBlend, "the crossfade never ran — the test would pass vacuously")
        assertEquals("uncertain", fromDecoded.state.value)
        assertNotNull(fromDecoded.currentFrame)
    }

    private fun assertCellsClose(
        expected: VoxelFrame,
        actual: VoxelFrame,
        tolerance: Float,
        step: Int,
    ) {
        assertEquals(expected.cells.size, actual.cells.size, "cell count diverged at step $step")
        expected.cells.zip(actual.cells).forEachIndexed { i, (e, a) ->
            assertTrue(abs(e.x - a.x) <= tolerance, "x diverged at cell $i, step $step: ${e.x} vs ${a.x}")
            assertTrue(abs(e.y - a.y) <= tolerance, "y diverged at cell $i, step $step: ${e.y} vs ${a.y}")
            assertTrue(abs(e.z - a.z) <= tolerance, "z diverged at cell $i, step $step: ${e.z} vs ${a.z}")
        }
    }

    private fun roundTrip(trace: VoxelTrace): VoxelTrace = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()

    private fun realCapture(): VoxelTrace =
        Oscilloscope
            .capture(
                CaptureConfig(
                    params = CaptureParams(atmosphere = AtmospherePresets.IDLE),
                    seed = 0x10_5E55L,
                    durationMs = 500L,
                    fps = 20,
                ),
            ).getOrThrow()

    private companion object {
        // A lerp of two quantized endpoints is within the endpoints' error, plus
        // the float rounding of the lerp itself.
        const val BLEND_ROUNDING = 1e-6f
    }
}
