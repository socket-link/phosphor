package link.socket.phosphor.lumos.compose.projection

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame.CanvasVoxel
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.trace.AtmosphereCue
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.Oscilloscope
import link.socket.phosphor.trace.TraceCodec

/**
 * PHO-42: a v2 trace projects through [ComposeLattice] to what `LumosCanvas`
 * would draw from the capture it was encoded from, within the tolerance agreed
 * at the recon gate. The codec now writes v3 (PHO-44), which decodes to exactly
 * v2's columns, so this holds both formats to the same bound.
 *
 * Per voxel, recorded against decoded:
 *
 * - **Colour exact.** The `Color` `LumosCanvas` builds packs to the same value.
 *   v2 stores exactly the byte Compose packs an sRGB float into.
 * - **Radius within one scale step.** `voxelRadiusPx / 6561`, effectively zero.
 * - **Centre within an eighth of a pixel** on a 1080 px canvas (the recon
 *   measured 0.109 px worst case; the bound is half of v2's `2⁻¹²` step).
 * - **Visibility unchanged**, except for a voxel whose centre sits within that
 *   eighth of a pixel of the canvas edge, where `ComposeLattice` culls it.
 *
 * Each cell is projected on its own so recorded and decoded voxels pair by
 * lattice index — `ComposeLattice` drops culled voxels from its output list, so
 * the lists alone cannot be zipped.
 */
class TraceV2ComposeEquivalenceTest {
    @Test
    fun `decoded v2 voxels draw within an eighth of a pixel and in identical colour`() {
        val trace = Oscilloscope.capture(CAPTURE).getOrThrow()
        val recorded = trace.toFrames()
        val decoded = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow().toFrames()
        val lattice = ComposeLattice(widthPx = CANVAS_PX, heightPx = CANVAS_PX, voxelRadiusPx = RADIUS_PX)

        var paired = 0
        var edgeCulls = 0
        for (frameIndex in recorded.indices) {
            val recordedFrame = recorded[frameIndex]
            val decodedFrame = decoded[frameIndex]
            assertEquals(recordedFrame.cells.size, decodedFrame.cells.size)
            for (i in recordedFrame.cells.indices) {
                val where = "cell $i, frame $frameIndex"
                val e = projectOne(lattice, recordedFrame, recordedFrame.cells[i])
                val a = projectOne(lattice, decodedFrame, decodedFrame.cells[i])
                when {
                    e != null && a != null -> {
                        assertTrue(
                            abs(e.screenX - a.screenX) <= MAX_POSITION_PX,
                            "x moved ${abs(e.screenX - a.screenX)} px at $where",
                        )
                        assertTrue(
                            abs(e.screenY - a.screenY) <= MAX_POSITION_PX,
                            "y moved ${abs(e.screenY - a.screenY)} px at $where",
                        )
                        assertTrue(abs(e.radiusPx - a.radiusPx) <= MAX_RADIUS_PX, "radius moved at $where")
                        assertEquals(drawnColor(e), drawnColor(a), "drawn colour changed at $where")
                        paired++
                    }
                    e != null || a != null -> {
                        val visible = e ?: a!!
                        assertTrue(
                            nearEdge(visible),
                            "voxel culled on one side only, away from the canvas edge, at $where",
                        )
                        edgeCulls++
                    }
                }
            }
        }

        println("[PHO-42] Compose ${CANVAS_PX}px: $paired voxel draws compared, $edgeCulls edge culls")
        assertTrue(paired > 0, "no visible voxels compared — the test would pass vacuously")
    }

    private fun projectOne(
        lattice: ComposeLattice,
        frame: VoxelFrame,
        cell: VoxelCell,
    ): CanvasVoxel? = lattice.project(frame.copy(cells = listOf(cell))).voxels.singleOrNull()

    /** The value of the `Color` `LumosCanvas` constructs for [voxel]. */
    private fun drawnColor(voxel: CanvasVoxel): ULong = Color(voxel.red, voxel.green, voxel.blue, voxel.alpha).value

    private fun nearEdge(voxel: CanvasVoxel): Boolean =
        minOf(voxel.screenX, CANVAS_PX - voxel.screenX, voxel.screenY, CANVAS_PX - voxel.screenY) <= MAX_POSITION_PX

    private companion object {
        const val CANVAS_PX = 1080

        // Half a lattice cell at resolution 10, the size LumosCanvas tiles the orb at.
        const val RADIUS_PX = CANVAS_PX / 40f

        const val MAX_POSITION_PX = 0.125f
        const val MAX_RADIUS_PX = RADIUS_PX / 6561f + 1e-4f

        val CAPTURE =
            CaptureConfig(
                params = CaptureParams(atmosphere = AtmospherePresets.IDLE),
                seed = 0x10_5E55L,
                durationMs = 3_000L,
                fps = 20,
                cues = listOf(AtmosphereCue(atFrame = 30, atmosphere = AtmospherePresets.UNCERTAIN)),
            )
    }
}
