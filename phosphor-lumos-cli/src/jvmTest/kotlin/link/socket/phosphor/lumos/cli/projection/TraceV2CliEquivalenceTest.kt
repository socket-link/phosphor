package link.socket.phosphor.lumos.cli.projection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.color.AnsiColorMap
import link.socket.phosphor.color.AnsiColorMode
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.trace.AtmosphereCue
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.Oscilloscope
import link.socket.phosphor.trace.TraceCodec

/**
 * PHO-42: a v2 trace projects through [CliLattice] to the same terminal as the
 * capture it was encoded from, within the tolerance agreed at the recon gate.
 * The codec now writes v3 (PHO-44), which decodes to exactly v2's columns, so
 * this holds both formats to the same bound.
 *
 * The capture is real — Socket's resolution-10 orb at 20 fps — and spans IDLE,
 * whose scale breathes straight through a luminance-ramp threshold, then a cue
 * into UNCERTAIN's bipolar colour bands.
 *
 * Two claims, tested separately so a failure names which one broke:
 *
 * 1. **Colour and scale are exact.** With positions held at their recorded
 *    values, every cell's character, occupancy, truecolor escape and 256-colour
 *    escape is identical.
 * 2. **Position is bounded.** `CliLattice` floors a continuous point at a
 *    runtime width, so any position error at all moves the voxels sitting on a
 *    cell boundary. v2's error is `2⁻¹³` lattice units; the recon measured
 *    0.57% of occupied cells differing at 80×40, against hundreds of cells the
 *    live orb changes every frame. The ceiling here catches a regression in
 *    step or rounding without pretending the flips do not exist.
 */
class TraceV2CliEquivalenceTest {
    private val recorded: List<VoxelFrame>
    private val decoded: List<VoxelFrame>

    init {
        val trace = Oscilloscope.capture(CAPTURE).getOrThrow()
        recorded = trace.toFrames()
        decoded = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow().toFrames()
    }

    @Test
    fun `colour and scale quantization leave every terminal cell identical`() {
        val appearanceOnly = withRecordedPositions(decoded)
        var compared = 0

        for ((width, height) in SIZES) {
            val lattice = CliLattice(width, height)
            for (frameIndex in recorded.indices) {
                val expected = lattice.project(recorded[frameIndex]).cells
                val actual = lattice.project(appearanceOnly[frameIndex]).cells
                for (cell in expected.indices) {
                    val e = expected[cell]
                    val a = actual[cell]
                    val where = "cell $cell of ${width}x$height, frame $frameIndex"
                    assertEquals(e.char, a.char, "character changed at $where")
                    assertEquals(e.foreground == null, a.foreground == null, "occupancy changed at $where")
                    val ef = e.foreground ?: continue
                    val af = a.foreground!!
                    assertEquals(
                        AnsiColorMap.escape(ef, AnsiColorMode.TRUECOLOR),
                        AnsiColorMap.escape(af, AnsiColorMode.TRUECOLOR),
                        "truecolor changed at $where",
                    )
                    assertEquals(
                        AnsiColorMap.escape(ef, AnsiColorMode.ANSI_256),
                        AnsiColorMap.escape(af, AnsiColorMode.ANSI_256),
                        "256-colour changed at $where",
                    )
                    compared++
                }
            }
        }

        assertTrue(compared > 0, "no occupied cells compared — the test would pass vacuously")
    }

    @Test
    fun `position quantization changes only a bounded sliver of terminal cells`() {
        // Compare against the appearance-only frames, which claim 1 shows are
        // terminal-identical to the recording, so every difference counted here
        // is caused by position alone.
        val baseline = withRecordedPositions(decoded)
        val lattice = CliLattice(BOUNDED_WIDTH, BOUNDED_HEIGHT)
        var occupied = 0L
        var differing = 0L

        for (frameIndex in recorded.indices) {
            val expected = lattice.project(baseline[frameIndex]).cells
            val actual = lattice.project(decoded[frameIndex]).cells
            for (cell in expected.indices) {
                val e = expected[cell]
                val a = actual[cell]
                if (e.foreground == null && a.foreground == null) continue
                occupied++
                if (e.char != a.char || e.foreground != a.foreground) differing++
            }
        }

        val rate = differing.toDouble() / occupied
        println("[PHO-42] CLI ${BOUNDED_WIDTH}x$BOUNDED_HEIGHT: $differing of $occupied occupied cells differ ($rate)")
        assertTrue(occupied > 0, "no occupied cells compared — the test would pass vacuously")
        assertTrue(
            rate <= MAX_DIFFERING_RATE,
            "$differing of $occupied occupied cells differ; the ceiling is $MAX_DIFFERING_RATE",
        )
    }

    /** [frames] with each cell's position replaced by the recorded one, keeping decoded scale and colour. */
    private fun withRecordedPositions(frames: List<VoxelFrame>): List<VoxelFrame> =
        frames.mapIndexed { frameIndex, frame ->
            val positions = recorded[frameIndex].cells
            frame.copy(
                cells =
                    frame.cells.mapIndexed {
                            i,
                            cell,
                        ->
                        cell.copy(x = positions[i].x, y = positions[i].y, z = positions[i].z)
                    },
            )
        }

    private companion object {
        val CAPTURE =
            CaptureConfig(
                params = CaptureParams(atmosphere = AtmospherePresets.IDLE),
                seed = 0x10_5E55L,
                durationMs = 5_000L,
                fps = 20,
                cues = listOf(AtmosphereCue(atFrame = 60, atmosphere = AtmospherePresets.UNCERTAIN)),
            )

        val SIZES = listOf(40 to 20, 60 to 30, 80 to 40, 120 to 60)

        const val BOUNDED_WIDTH = 80
        const val BOUNDED_HEIGHT = 40

        // Recon measured 0.57% at 80×40 across seven real captures.
        const val MAX_DIFFERING_RATE = 0.0075
    }
}
