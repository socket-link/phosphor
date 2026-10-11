package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState

/** `CaptureConfig.seamBlendFrames`: loops that wrap because capture made them. */
class SeamBlendCaptureTest {
    @Test
    fun `every canonical preset wraps like one more frame once its seam is baked`() {
        AtmospherePresets.ALL.forEach { (name, preset) ->
            val raw = Oscilloscope.capture(loopAt(preset, start = 0)).getOrThrow().seamErrors().single()
            val baked =
                Oscilloscope
                    .capture(loopAt(preset, start = 0, blendFrames = BLEND_FRAMES))
                    .getOrThrow()
                    .seamErrors()
                    .single()

            assertTrue(raw.contentRatio > RAW_FLOOR, "$name should pop untouched, was $raw")
            assertTrue(baked.contentRatio < BAKED_CEILING, "$name should wrap once baked, was $baked")
        }
    }

    @Test
    fun `a baked seam survives v2 quantization`() {
        // PHO-50: v2 rounds every cell to a quantum, and a slow preset's per-frame
        // colour step is about one RGB8 quantum. The seam and its median step are
        // both means over every cell, so rounding barely moves either one. The codec
        // writes v3, which decodes to exactly v2's columns (PHO-44), so this covers both.
        AtmospherePresets.ALL.forEach { (name, preset) ->
            val captured =
                Oscilloscope
                    .capture(loopAt(preset, start = 0, blendFrames = BLEND_FRAMES))
                    .getOrThrow()
            val decoded = TraceCodec.decode(TraceCodec.encode(captured)).getOrThrow()

            val before = captured.seamErrors().single()
            val after = decoded.seamErrors().single()

            assertEquals(VoxelTrace.CURRENT_FORMAT_VERSION, decoded.formatVersion)
            assertTrue(after.contentRatio < BAKED_CEILING, "$name should still wrap after a v2 round trip, was $after")
            assertEquals(
                before.contentRatio,
                after.contentRatio,
                QUANTIZATION_DRIFT,
                "$name's seam should read the same quantized as in floats",
            )
        }
    }

    @Test
    fun `baking leaves the rotation as recorded for playback to carry`() {
        val raw = Oscilloscope.capture(loopAt(AtmospherePresets.THINKING, start = BLEND_FRAMES)).getOrThrow()
        val baked =
            Oscilloscope
                .capture(loopAt(AtmospherePresets.THINKING, start = BLEND_FRAMES, blendFrames = BLEND_FRAMES))
                .getOrThrow()

        assertEquals(raw.ambient.map { it.orbRotationY }, baked.ambient.map { it.orbRotationY })
        // A rotation seam a short loop cannot give back: the whole loop's travel.
        assertTrue(baked.seamErrors().single().rotation.ratio > RAW_FLOOR)
    }

    @Test
    fun `only the loop's tail changes when the clip already holds its lead-in`() {
        val config = loopAt(AtmospherePresets.IDLE, start = BLEND_FRAMES)
        val raw = Oscilloscope.capture(config).getOrThrow().toFrames()
        val baked = Oscilloscope.capture(config.copy(seamBlendFrames = BLEND_FRAMES)).getOrThrow().toFrames()

        val tail = (config.segments.single().endFrame - BLEND_FRAMES + 1)..config.segments.single().endFrame
        raw.indices.forEach { index ->
            if (index in tail) {
                assertNotEquals(raw[index].cells, baked[index].cells, "frame $index is in the window")
                assertEquals(raw[index].tick, baked[index].tick)
            } else {
                assertEquals(raw[index], baked[index], "frame $index is outside the window")
            }
        }
    }

    @Test
    fun `a loop at the head of the clip is given a hidden lead-in`() {
        val trace =
            Oscilloscope.capture(
                loopAt(AtmospherePresets.IDLE, start = 0, blendFrames = BLEND_FRAMES),
            ).getOrThrow()

        // The simulation stepped BLEND_FRAMES times before the first recorded frame.
        val unshifted = Oscilloscope.capture(loopAt(AtmospherePresets.IDLE, start = 0)).getOrThrow()
        assertEquals(unshifted.ticks.first() + BLEND_FRAMES, trace.ticks.first())
        assertEquals(BLEND_FRAMES.toString(), trace.paramSnapshot["capture.warmUpFrames"])
        assertEquals(BLEND_FRAMES.toString(), trace.paramSnapshot["capture.seamBlendFrames"])
    }

    @Test
    fun `an unblended capture records no blend parameters`() {
        val snapshot = Oscilloscope.capture(loopAt(AtmospherePresets.IDLE, start = 0)).getOrThrow().paramSnapshot

        assertFalse("capture.seamBlendFrames" in snapshot)
        assertFalse("capture.warmUpFrames" in snapshot)
    }

    @Test
    fun `a blend that cannot fit or pair is rejected`() {
        val base = loopAt(AtmospherePresets.IDLE, start = 0)
        val loopFrames = base.segments.single().frameCount
        val invalid =
            listOf(
                base.copy(seamBlendFrames = -1),
                base.copy(seamBlendFrames = loopFrames),
                base.copy(
                    seamBlendFrames = BLEND_FRAMES,
                    params = base.params.copy(renderConfig = LumosRenderConfig(omitBelowScale = 0.1f)),
                ),
            )

        invalid.forEach { config ->
            assertIs<CaptureError.InvalidConfig>(Oscilloscope.capture(config).exceptionOrNull(), "$config")
        }
    }

    private fun loopAt(
        preset: AtmosphereState,
        start: Int,
        blendFrames: Int = 0,
    ): CaptureConfig =
        CaptureConfig(
            params = CaptureParams(atmosphere = preset.copy(resolution = RESOLUTION)),
            durationMs = (start + LOOP_FRAMES) * 1_000L / FPS,
            fps = FPS,
            segments = listOf(TraceSegment("loop", start, start + LOOP_FRAMES - 1, loop = true)),
            seamBlendFrames = blendFrames,
        )

    private companion object {
        const val FPS = 20
        const val RESOLUTION = 6
        const val LOOP_FRAMES = 80
        const val BLEND_FRAMES = 20

        // Three frame steps is already a visible pop; the coarse test lattice puts listening near four.
        const val RAW_FLOOR = 3f

        // A perfectly periodic sine already reads ~1.4; see SeamErrorTest.
        const val BAKED_CEILING = 2f

        // The PHO-50 recon moved no canonical loop's contentRatio by more than 0.13.
        const val QUANTIZATION_DRIFT = 0.25f
    }
}
