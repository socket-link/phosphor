package link.socket.phosphor.trace

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import link.socket.phosphor.lumos.FramePhases
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.palette.AtmospherePresets

/**
 * PHO-59 Task A: a look that owns the yaw (retrying's rewind) records it into
 * `VoxelAmbient.orbRotationY`, and [TraceStateMachine] carries it across a
 * loop's seam as it carries the atmosphere's spin.
 *
 * The loop is two retry cycles, 3.2 s at 30 fps, recorded in steady state from
 * a builder wearing [LumosLook.RETRYING]; the same builder, run on for three
 * loops' worth, is the live reference playback is held against.
 */
class LookYawCarryTest {
    private val live: List<VoxelFrame> = record(3 * LOOP_FRAMES)
    private val trace: VoxelTrace =
        VoxelTrace.fromFrames(
            frames = live.subList(0, LOOP_FRAMES),
            fps = FPS,
            phosphorVersion = "test",
            createdAtEpochMs = 0L,
            segments = listOf(TraceSegment(RETRYING, 0, LOOP_FRAMES - 1, loop = true)),
        )

    @Test
    fun `the recording is the look's yaw, not the atmosphere's spin`() {
        // 1.33 s in, the first retry is springing back: 0.22 rad against the atmosphere's 0.6.
        val atmosphereYaw = FramePhases.at(ATMOSPHERE, 40.0 / FPS).orbRotationY
        val recorded = live[40].ambient.orbRotationY
        assertTrue(abs(shortestArc(atmosphereYaw, recorded)) > 0.1f, "$recorded vs the atmosphere's $atmosphereYaw")
        // The spring turns the orb back, so the recording runs backward in places.
        assertTrue(steps(live.subList(0, LOOP_FRAMES)).any { it < 0f }, "the rewind is in the recording")
    }

    @Test
    fun `a look-owned yaw keeps turning across the loop's seam`() {
        val played = play(3 * LOOP_FRAMES)
        val recordedSteps = steps(live.subList(0, LOOP_FRAMES))
        val playedSteps = steps(played)

        // Inside every pass the carried steps are the recorded ones.
        for (pass in 0 until 3) {
            for (i in 0 until LOOP_FRAMES - 1) {
                assertEquals(
                    recordedSteps[i],
                    playedSteps[pass * LOOP_FRAMES + i],
                    STEP_TOLERANCE,
                    "pass $pass step $i",
                )
            }
        }
        // At each seam the carry repeats the loop's last step, so the yaw never jumps back
        // by the loop's travel; the live yaw's own step there differs only by its
        // acceleration across one frame.
        for (pass in 1 until 3) {
            val seam = playedSteps[pass * LOOP_FRAMES - 1]
            assertEquals(recordedSteps.last(), seam, STEP_TOLERANCE, "seam $pass repeats the last step")
            val liveStep =
                shortestArc(
                    live[pass * LOOP_FRAMES - 1].ambient.orbRotationY,
                    live[pass * LOOP_FRAMES].ambient.orbRotationY,
                )
            assertTrue(abs(seam - liveStep) < SEAM_TOLERANCE, "seam $pass: carried $seam, live $liveStep")
        }
    }

    @Test
    fun `carried playback stays on the live look's yaw`() {
        val played = play(3 * LOOP_FRAMES)
        var worst = 0f
        for (i in played.indices) {
            worst = maxOf(worst, abs(shortestArc(live[i].ambient.orbRotationY, played[i].ambient.orbRotationY)))
        }
        // Each seam is off by the one-frame acceleration error, and two seams were crossed.
        assertTrue(worst < 2 * SEAM_TOLERANCE, "playback drifted $worst rad from the live yaw")
    }

    private fun play(frames: Int): List<VoxelFrame> {
        val machine = TraceStateMachine(trace, RETRYING)
        val played = ArrayList<VoxelFrame>(frames)
        played += machine.advance(Duration.ZERO)
        repeat(frames - 1) { played += machine.advance(machine.frameDuration) }
        return played
    }

    private fun record(frames: Int): List<VoxelFrame> {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.look = Look(LumosLook.RETRYING)
        return List(frames) { i ->
            builder.buildSteadyState(
                atmosphere = ATMOSPHERE,
                phases = FramePhases.at(ATMOSPHERE, i.toDouble() / FPS),
                tick = i.toLong(),
                timestampEpochMillis = i * 1000L / FPS,
            )
        }
    }

    private fun steps(frames: List<VoxelFrame>): List<Float> =
        frames.zipWithNext { previous, next -> shortestArc(previous.ambient.orbRotationY, next.ambient.orbRotationY) }

    private fun shortestArc(
        from: Float,
        to: Float,
    ): Float {
        var arc = (to - from) % TWO_PI
        if (arc > PI.toFloat()) arc -= TWO_PI
        if (arc < -PI.toFloat()) arc += TWO_PI
        return arc
    }

    private companion object {
        const val RETRYING = "retrying"
        const val FPS = 30
        const val RESOLUTION = 3

        /** Two retry cycles: [Look.RETRYING_PERIOD_SECONDS] is 1.6 s. */
        const val LOOP_FRAMES = 96

        const val TWO_PI = (2.0 * PI).toFloat()
        const val STEP_TOLERANCE = 1e-5f

        /** A tenth of a degree past the launch's one-frame change in step, about 0.0054 rad. */
        const val SEAM_TOLERANCE = 0.0072f

        val ATMOSPHERE = AtmospherePresets.THINKING.copy(resolution = RESOLUTION)
    }
}
