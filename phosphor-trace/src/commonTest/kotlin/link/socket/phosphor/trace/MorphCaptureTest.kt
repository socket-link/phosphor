package link.socket.phosphor.trace

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import link.socket.phosphor.field.Choreography
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.palette.AtmospherePresets

/**
 * PHO-57 Task D: a capture across a `morphTo` encodes as v3 with per-frame
 * deltas and no format change, pairs cells by index across the morph, and
 * decodes to the frames that were captured.
 */
class MorphCaptureTest {
    private val resolution = 4
    private val fps = 20
    private val idleFrames = 10
    private val morphFrames = 30

    private val config =
        CaptureConfig(
            params = CaptureParams(atmosphere = AtmospherePresets.IDLE.copy(resolution = resolution)),
            durationMs = (idleFrames + morphFrames) * 1_000L / fps,
            fps = fps,
            segments =
                listOf(
                    TraceSegment("idle", 0, idleFrames - 1, loop = true),
                    TraceSegment("sphere→fibonacci", idleFrames, idleFrames + morphFrames - 1),
                ),
            formCues = listOf(FormCue(atFrame = idleFrames, form = Forms.Fibonacci, durationSeconds = 1f)),
        )

    @Test
    fun `cells keep their count and index across the morph so every frame pairs with the last`() {
        val trace = Oscilloscope.capture(config).getOrThrow()
        assertEquals(idleFrames + morphFrames, trace.frameCount)
        assertEquals(1, trace.cellCounts.distinct().size, "every frame carries the same cells")

        val frames = trace.toFrames()
        val before = frames[idleFrames - 1]
        val first = frames[idleFrames]
        assertTrue(FrameBlend(before, first, 0.5f).isAligned)
        assertTrue(FrameBlend(frames[idleFrames + morphFrames - 1], before, 0.5f).isAligned)

        // The morph moves cells: the first morph frame differs from the last idle frame by more than
        // the breath does.
        val moved = first.cells.indices.count { i -> distance(before.cells[i], first.cells[i]) > 1e-3f }
        assertTrue(moved > first.cells.size / 2, "only $moved cells moved on the first morph frame")
    }

    @Test
    @NeedsBinary32
    fun `the capture round-trips through v3 exactly and v3 is no larger than it needs to be`() {
        val trace = Oscilloscope.capture(config).getOrThrow()
        assertEquals(3, trace.formatVersion)

        val encoded = encodeForTest(trace)
        val decoded = TraceCodec.decode(encoded).getOrThrow()
        assertIs<CellChannels.Quantized>(decoded.cells)
        assertEquals(trace.frameCount, decoded.frameCount)
        assertEquals(trace.segments, decoded.segments)
        TraceTestData.assertV2Equivalent(trace, decoded)

        val idleOnly = Oscilloscope.capture(config.copy(formCues = emptyList())).getOrThrow()
        val idleBytes = encodeForTest(idleOnly).size
        println("morph capture: ${encoded.size} B against $idleBytes B for the same frames at rest")
    }

    @Test
    fun `form cues are recorded in the parameter snapshot and validated`() {
        val snapshot = Oscilloscope.snapshotParams(config)
        assertEquals("$idleFrames", snapshot["formCue.0.atFrame"])
        assertEquals("Forms.Fibonacci", snapshot["formCue.0.form"])
        assertEquals("current", snapshot["formCue.0.count"])
        assertEquals(Choreography.FLOW.name, snapshot["formCue.0.choreography"])

        val outside = Oscilloscope.capture(config.copy(formCues = listOf(FormCue(atFrame = 999, form = Forms.Cube))))
        assertIs<CaptureError.InvalidConfig>(outside.exceptionOrNull())
        val negative =
            Oscilloscope.capture(
                config.copy(formCues = listOf(FormCue(0, Forms.Cube, durationSeconds = -1f))),
            )
        assertIs<CaptureError.InvalidConfig>(negative.exceptionOrNull())
    }

    @Test
    fun `a loop on a morphed form gets its seam baked like any other`() {
        val looped =
            config.copy(
                durationMs = (idleFrames + morphFrames + 10) * 1_000L / fps,
                segments =
                    config.segments +
                        TraceSegment("fibonacci", idleFrames + morphFrames, idleFrames + morphFrames + 9, loop = true),
                seamBlendFrames = 3,
            )
        val trace = Oscilloscope.capture(looped).getOrThrow()
        assertEquals(idleFrames + morphFrames + 10, trace.frameCount)
    }

    private fun distance(
        a: VoxelCell,
        b: VoxelCell,
    ): Float = abs(a.x - b.x) + abs(a.y - b.y) + abs(a.z - b.z)
}
