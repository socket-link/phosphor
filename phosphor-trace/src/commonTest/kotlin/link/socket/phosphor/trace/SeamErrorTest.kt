package link.socket.phosphor.trace

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmospherePattern

class SeamErrorTest {
    // ------------------------------------------------------------------
    // Real captures: the metric against the simulation it exists to audit
    // ------------------------------------------------------------------

    @Test
    fun `a loop captured over exactly one period of every accumulator wraps like an ordinary frame`() {
        val trace = Oscilloscope.capture(periodicCapture(loopFrames = LOOP_FRAMES)).getOrThrow()

        val seam = trace.seamErrors().single()

        // A perfectly periodic sine can still read above 1: the seam lands on the
        // steepest step of the pulse, and the median step is shallower than that.
        assertTrue(seam.ratio < ALIGNED_CEILING, "aligned seam should read as one frame step, was $seam")
        assertTrue(seam.position.ratio > 0f, "the pulse moves voxels across the seam; it must not read as a hold")
        assertTrue(
            seam.rotation.ratio in 0.5f..1.5f,
            "one full turn per loop wraps on a single step, was ${seam.rotation}",
        )
    }

    @Test
    fun `a loop cut short of its period reports the jump in frame steps`() {
        // Same motion, but the loop closes ~0.6 of the way through the pulse
        // and the turn: wrapping drops the orb back by that far.
        val misaligned = LOOP_FRAMES * 3 / 5
        val trace = Oscilloscope.capture(periodicCapture(loopFrames = misaligned)).getOrThrow()

        val seam = trace.seamErrors().single()

        assertTrue(seam.position.ratio > MISALIGNED_FLOOR, "pulse seam should read as many frame steps, was $seam")
        // Rotation is linear in time, so its jump in frame steps is the shortest
        // arc back to the start: 48 frames forward is 32 frames back the short way.
        assertEquals((LOOP_FRAMES - misaligned + 1).toFloat(), seam.rotation.ratio, 0.05f)
    }

    @Test
    fun `canonical presets do not wrap in a budgetable loop`() {
        // The premise FinishLoopThenSwitch used to state as fact. A 4 s idle loop
        // carries ~0.56 rad of rotation and ~1.4 rad of surface-bump phase into
        // its seam, none of which the loop gives back.
        val trace =
            Oscilloscope
                .capture(
                    CaptureConfig(
                        params = CaptureParams(atmosphere = AtmospherePresets.IDLE.copy(resolution = RESOLUTION)),
                        durationMs = 4_000L,
                        fps = FPS,
                        segments = listOf(TraceSegment("idle", 0, 79, loop = true)),
                    ),
                ).getOrThrow()

        val seam = trace.seamError(trace.segments.single())

        assertTrue(seam.ratio > MISALIGNED_FLOOR, "a 4 s idle loop should not wrap continuously, was $seam")
    }

    // ------------------------------------------------------------------
    // Synthetic frames: the edges of the definition
    // ------------------------------------------------------------------

    @Test
    fun `a static loop reads zero rather than dividing by zero`() {
        val trace = synthetic(List(6) { frame(x = 0.5f) }, TraceSegment("still", 0, 5, loop = true))

        val seam = trace.seamErrors().single()

        assertEquals(0f, seam.ratio)
        seam.channels.forEach { (name, channel) -> assertEquals(0f, channel.ratio, name) }
    }

    @Test
    fun `a jump in a channel that never moves is unbounded`() {
        // Colour moves on one step in five, so its median step is zero, and the
        // seam has to undo that move: no ordinary motion in the channel hides it.
        val frames = List(5) { index -> frame(x = index * 0.01f, red = 0.2f) } + frame(x = 0.05f, red = 0.9f)
        val trace = synthetic(frames, TraceSegment("loop", 0, 5, loop = true))

        val seam = trace.seamErrors().single()

        assertEquals(Float.POSITIVE_INFINITY, seam.color.ratio)
        assertEquals(Float.POSITIVE_INFINITY, seam.ratio)
    }

    @Test
    fun `rotation is measured on the shortest arc across the 2π wrap`() {
        // 6.20 → 6.25 → 0.02 → 0.07 rad: every step is ~0.05 forward, including
        // the one that crosses the wrap, and the seam from 0.07 back to 6.20 is
        // 0.15 the short way — three steps, not a full turn.
        val rotations = listOf(6.20f, 6.25f, 0.02f, 0.07f)
        val trace = synthetic(rotations.map { frame(rotationY = it) }, TraceSegment("spin", 0, 3, loop = true))

        val rotation = trace.seamErrors().single().rotation

        assertEquals(0.05f, rotation.medianStep, 0.005f)
        assertEquals(0.15f, rotation.jump, 0.005f)
        assertTrue(rotation.jump < PI.toFloat())
    }

    @Test
    fun `a seam between frames with different cell counts is unbounded in the cell channels`() {
        val frames = listOf(frame(cells = 2), frame(cells = 2), frame(cells = 3))
        val trace = synthetic(frames, TraceSegment("omitted", 0, 2, loop = true))

        val seam = trace.seamErrors().single()

        assertEquals(Float.POSITIVE_INFINITY, seam.position.jump)
        assertEquals(Float.POSITIVE_INFINITY, seam.scale.ratio)
        assertEquals(Float.POSITIVE_INFINITY, seam.color.ratio)
        // The ambient channels pair regardless of cell count.
        assertEquals(0f, seam.rotation.ratio)
    }

    @Test
    fun `seamErrors measures loops only and seamError measures anything`() {
        val frames = List(8) { index -> frame(x = index * 0.1f) }
        val trace =
            synthetic(
                frames,
                TraceSegment("idle", 0, 3, loop = true),
                TraceSegment("idle→thinking", 4, 5),
                TraceSegment("thinking", 6, 7, loop = true),
            )

        assertEquals(listOf("idle", "thinking"), trace.seamErrors().map { it.segment.name })
        // Four frames 0.1 apart: the seam drops back three steps.
        assertEquals(3f, trace.seamErrors().first().position.ratio, 0.001f)
        // A two-frame one-shot's seam is its only step, run backwards.
        assertEquals(1f, trace.seamError(trace.segments[1]).position.ratio, 0.001f)
    }

    @Test
    fun `a single-frame loop has no seam to jump across`() {
        val trace = synthetic(listOf(frame(), frame(x = 1f)), TraceSegment("held", 1, 1, loop = true))

        assertEquals(0f, trace.seamErrors().single().ratio)
    }

    @Test
    fun `a segment outside the trace is rejected`() {
        val trace = synthetic(listOf(frame(), frame()))

        assertFailsWith<IllegalArgumentException> { trace.seamError(TraceSegment("late", 0, 2, loop = true)) }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A capture whose every accumulator completes a whole number of cycles in
     * [LOOP_FRAMES]: one breath, one full turn about Y, and no pattern or
     * surface motion. Capturing [loopFrames] of it loops exactly when
     * `loopFrames == LOOP_FRAMES`.
     */
    private fun periodicCapture(loopFrames: Int): CaptureConfig {
        val loopSeconds = LOOP_FRAMES.toFloat() / FPS
        return CaptureConfig(
            params =
                CaptureParams(
                    atmosphere =
                        AtmospherePresets.IDLE.copy(
                            pattern = AtmospherePattern.SOLID,
                            patternSpeed = 0f,
                            surfaceBump = 0f,
                            pulseAmplitude = 0.06f,
                            pulseFrequency = 1f / loopSeconds,
                            rotationY = (2.0 * PI).toFloat() / loopSeconds,
                            rotationX = 0f,
                            resolution = RESOLUTION,
                        ),
                ),
            durationMs = loopFrames * 1_000L / FPS,
            fps = FPS,
            segments = listOf(TraceSegment("loop", 0, loopFrames - 1, loop = true)),
        )
    }

    private fun frame(
        x: Float = 0f,
        red: Float = 0.5f,
        rotationY: Float = 0f,
        cells: Int = 1,
    ): VoxelFrame =
        VoxelFrame(
            tick = 0L,
            timestampEpochMillis = 0L,
            resolution = 1,
            cells = List(cells) { VoxelCell(x = x, y = 0f, z = 0f, scale = 1f, red = red, green = 0.5f, blue = 0.5f) },
            ambient =
                VoxelAmbient(
                    0.3f,
                    0.4f,
                    0.5f,
                    1f,
                    orbRotationX = 0f,
                    orbRotationY = rotationY,
                    orbRotationZ = 0f,
                ),
        )

    private fun synthetic(
        frames: List<VoxelFrame>,
        vararg segments: TraceSegment,
    ): VoxelTrace =
        VoxelTrace.fromFrames(
            frames = frames,
            fps = FPS,
            phosphorVersion = "test",
            createdAtEpochMs = 0L,
            segments = segments.toList(),
        )

    private companion object {
        const val FPS = 20
        const val RESOLUTION = 4
        const val LOOP_FRAMES = 80

        // A perfect sine wraps at up to ~1.4 median steps; anything a viewer could
        // call continuous sits well inside 2.
        const val ALIGNED_CEILING = 2f
        const val MISALIGNED_FLOOR = 10f
    }
}
