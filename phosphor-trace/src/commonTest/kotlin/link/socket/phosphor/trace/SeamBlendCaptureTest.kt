package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        // Started BLEND_FRAMES in, where #98's hidden warm-up used to place it, so
        // the seam lands where the recon measured it. A loop at frame 0 straddles
        // pulse phase 0 instead, the sine's steepest step; see the head-of-clip test.
        AtmospherePresets.ALL.forEach { (name, preset) ->
            val raw = Oscilloscope.capture(loopAt(preset, start = BLEND_FRAMES)).getOrThrow().seamErrors().single()
            val baked =
                Oscilloscope
                    .capture(loopAt(preset, start = BLEND_FRAMES, blendFrames = BLEND_FRAMES))
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
        // Started BLEND_FRAMES in for the same reason as the presets test above.
        AtmospherePresets.ALL.forEach { (name, preset) ->
            val captured =
                Oscilloscope
                    .capture(loopAt(preset, start = BLEND_FRAMES, blendFrames = BLEND_FRAMES))
                    .getOrThrow()
            val decoded = TraceCodec.decode(encodeForTest(captured)).getOrThrow()

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
    fun `only the loop's tail changes when a seam is baked`() {
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
    fun `a loop at the head of the clip needs no warm-up`() {
        val config = loopAt(AtmospherePresets.IDLE, start = 0)
        val raw = Oscilloscope.capture(config).getOrThrow()
        val baked = Oscilloscope.capture(config.copy(seamBlendFrames = BLEND_FRAMES)).getOrThrow()

        // The lead-in is synthesized, so nothing is stepped before frame 0 and the
        // recorded ticks and timestamps are the simulation's own.
        assertContentEquals(raw.ticks, baked.ticks)
        assertContentEquals(raw.timestampsEpochMillis, baked.timestampsEpochMillis)
        assertEquals(BLEND_FRAMES.toString(), baked.paramSnapshot["capture.seamBlendFrames"])
        assertFalse("capture.warmUpFrames" in baked.paramSnapshot)

        // A fresh builder starts at phase 0, so this seam straddles the pulse's
        // steepest step and reads about 2 against the loop's median step, which
        // the blend window has already halved. Against the loop's own largest
        // step it is one more frame — within 5 %, since the first ordinary step
        // sits one step past the steepest point.
        val seam = baked.seamErrors().single()
        val steps = (0 until LOOP_FRAMES - 1).map { baked.seamError(TraceSegment("step", it, it + 1)) }
        assertTrue(seam.position.jump <= steps.maxOf { it.position.jump } * STEEPEST_STEP_SLACK, "position: $seam")
        assertTrue(seam.scale.jump <= steps.maxOf { it.scale.jump } * STEEPEST_STEP_SLACK, "scale: $seam")
        assertTrue(seam.color.jump <= steps.maxOf { it.color.jump } * STEEPEST_STEP_SLACK, "colour: $seam")
        assertEquals(0f, seam.glow.jump)
    }

    @Test
    fun `a loop may start the frame after a transition lands`() {
        val thinking = AtmospherePresets.THINKING.copy(resolution = RESOLUTION)
        // Neither endpoint is a registered preset at this resolution, so the cue
        // takes the 1.1 s default spec: 22 steps at 20 fps, and one more so it has
        // landed for certain inside its own segment.
        val transitionFrames = 23
        val loopStart = LEAD_FRAMES + transitionFrames
        val config =
            CaptureConfig(
                params = CaptureParams(atmosphere = AtmospherePresets.IDLE.copy(resolution = RESOLUTION)),
                durationMs = (loopStart + LOOP_FRAMES) * 1_000L / FPS,
                fps = FPS,
                segments =
                    listOf(
                        TraceSegment("idle→thinking", LEAD_FRAMES, loopStart - 1),
                        TraceSegment("thinking", loopStart, loopStart + LOOP_FRAMES - 1, loop = true),
                    ),
                cues = listOf(AtmosphereCue(atFrame = LEAD_FRAMES, atmosphere = thinking)),
                seamBlendFrames = BLEND_FRAMES,
            )

        val trace = Oscilloscope.capture(config).getOrThrow()

        val seam = trace.seamErrors().single()
        assertTrue(seam.contentRatio < BAKED_CEILING, "a loop straight after a transition should wrap, was $seam")
        // The halo is the atmosphere's colour and nothing else, so a lean toward
        // idle would show there first. The frames recorded before the loop were
        // still mid-transition; the window holds the loop's own halo regardless.
        val loopGlow = glowOf(trace, loopStart)
        assertNotEquals(glowOf(trace, loopStart - BLEND_FRAMES), loopGlow)
        for (frame in seam.segment.endFrame - BLEND_FRAMES + 1..seam.segment.endFrame) {
            assertEquals(loopGlow, glowOf(trace, frame), "frame $frame in the blend window should hold the loop's halo")
        }
    }

    @Test
    fun `an unblended capture records no blend parameters`() {
        val snapshot = Oscilloscope.capture(loopAt(AtmospherePresets.IDLE, start = 0)).getOrThrow().paramSnapshot

        assertFalse("capture.seamBlendFrames" in snapshot)
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

    private fun glowOf(
        trace: VoxelTrace,
        frame: Int,
    ): List<Float> = trace.ambient[frame].let { listOf(it.glowRed, it.glowGreen, it.glowBlue, it.glowIntensity) }

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

        /** Frames of the starting atmosphere recorded before a cue fires. */
        const val LEAD_FRAMES = 10

        // Three frame steps is already a visible pop; the coarse test lattice puts listening near four.
        const val RAW_FLOOR = 3f

        // A perfectly periodic sine already reads ~1.4; see SeamErrorTest.
        const val BAKED_CEILING = 2f

        // The PHO-50 recon moved no canonical loop's contentRatio by more than 0.13.
        const val QUANTIZATION_DRIFT = 0.25f

        // How far a seam may exceed the loop's largest ordinary step and still be one of them.
        const val STEEPEST_STEP_SLACK = 1.05f
    }
}
