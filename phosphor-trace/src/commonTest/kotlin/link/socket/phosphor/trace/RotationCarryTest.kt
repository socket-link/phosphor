package link.socket.phosphor.trace

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame

/**
 * Rotation is carried across seams by [TraceStateMachine], not replayed.
 *
 * The fixture's segments turn at different rates from unrelated recorded
 * angles, the way separate stretches of a real capture do, so any seam the
 * machine fails to carry shows up as a rotation step far from the local rate.
 * At 10 fps one recorded frame is 100 ms.
 */
class RotationCarryTest {
    @Test
    fun `a loop keeps turning across its own seam`() {
        val machine = TraceStateMachine(trace(), SPIN)

        val steps = rotationSteps(machine, frames = 3 * LOOP_FRAMES)

        steps.forEach { assertNear(SPIN_RATE, it) }
    }

    @Test
    fun `without carrying the seam drops the orb back by the loop's travel`() {
        val machine = TraceStateMachine(trace(), SPIN, carryRotation = false)

        val steps = rotationSteps(machine, frames = LOOP_FRAMES)

        // Five steps forward, then the wrap undoes all five.
        assertNear(-(LOOP_FRAMES - 1) * SPIN_RATE, steps.last())
    }

    @Test
    fun `finishing a loop into another continues the outgoing motion`() {
        val machine = TraceStateMachine(trace(), SPIN)
        machine.advance(250.milliseconds)
        machine.requestState(OTHER).getOrThrow()

        val steps = rotationSteps(machine, frames = 2 * LOOP_FRAMES)

        assertEquals(OTHER, machine.state.value)
        // The switch lands on the seam, one outgoing step on; after it, the
        // target's own rate. No step reaches back toward either recorded angle.
        steps.forEach { step -> assertTrue(step in SPIN_RATE - TOLERANCE..OTHER_RATE + TOLERANCE, "step $step") }
    }

    @Test
    fun `a recorded transition starts where the loop is and hands its rate to the next`() {
        val machine = TraceStateMachine(trace(), OTHER)
        machine.advance(250.milliseconds)
        machine.requestState(SPIN).getOrThrow()

        val steps = rotationSteps(machine, frames = TRANSITION_FRAMES + LOOP_FRAMES)

        assertEquals(SPIN, machine.state.value)
        // The transition opens on the angle the loop was showing, and every step
        // moves forward at a rate the recording contains — its own, then spin's.
        steps.forEach { step -> assertTrue(step in -TOLERANCE..TRANSITION_RATE + TOLERANCE, "step $step") }
    }

    @Test
    fun `a crossfade blends two heads that start at one orientation`() {
        val machine = TraceStateMachine(trace(), SPIN, TransitionPolicy.Crossfade(durationMs = 300))
        machine.advance(250.milliseconds)
        machine.requestState(OTHER).getOrThrow()

        val steps = rotationSteps(machine, frames = LOOP_FRAMES)

        // Two heads turning at different rates drift apart through the fade, so
        // the blend briefly outruns both — but by their difference, not by the
        // ~2.8 rad between their recorded angles that an uncarried fade sweeps.
        steps.forEach { step -> assertTrue(step in -TOLERANCE..2 * OTHER_RATE + TOLERANCE, "step $step") }
    }

    @Test
    fun `the first pass is the recording itself and a held carried frame is one instance`() {
        val trace = trace()
        val machine = TraceStateMachine(trace, SPIN)

        assertEquals(trace.toFrames().first(), machine.advance(Duration.ZERO))

        // Into the second iteration, where the rotation is offset.
        val carried = machine.advance(750.milliseconds)
        val held = machine.advance(25.milliseconds)
        assertSame(carried, held)
        assertEquals(trace.toFrames()[1].cells, carried.cells)
    }

    @Test
    fun `hours of looping keep the step exact`() {
        val machine = TraceStateMachine(trace(), SPIN)
        machine.advance(10.hours)

        rotationSteps(machine, frames = 2 * LOOP_FRAMES).forEach { assertNear(SPIN_RATE, it) }
    }

    private fun rotationSteps(
        machine: TraceStateMachine,
        frames: Int,
    ): List<Float> {
        var previous = machine.advance(Duration.ZERO).ambient.orbRotationY
        return List(frames) {
            val next = machine.advance(FRAME).ambient.orbRotationY
            shortestArc(previous, next).also { previous = next }
        }
    }

    private fun trace(): VoxelTrace {
        val frames = ArrayList<VoxelFrame>()
        val segments = ArrayList<TraceSegment>()

        fun record(
            name: String,
            count: Int,
            from: Float,
            rate: Float,
            loop: Boolean,
        ) {
            val start = frames.size
            repeat(count) { step -> frames += frameOf(frames.size, from + rate * step) }
            segments += TraceSegment(name, start, frames.size - 1, loop)
        }

        record(SPIN, LOOP_FRAMES, from = 1.0f, rate = SPIN_RATE, loop = true)
        record(OTHER, LOOP_FRAMES, from = 4.0f, rate = OTHER_RATE, loop = true)
        record(
            TraceStateMachine.transitionName(OTHER, SPIN),
            TRANSITION_FRAMES,
            from = 5.5f,
            rate = TRANSITION_RATE,
            loop = false,
        )
        return VoxelTrace.fromFrames(
            frames = frames,
            fps = FPS,
            phosphorVersion = "test",
            createdAtEpochMs = 0L,
            segments = segments,
        )
    }

    private fun frameOf(
        tick: Int,
        rotationY: Float,
    ): VoxelFrame =
        VoxelFrame(
            tick = tick.toLong(),
            timestampEpochMillis = tick * 100L,
            resolution = 1,
            cells = listOf(VoxelCell(x = 0f, y = 0f, z = 0f, scale = 1f, red = 0.5f, green = 0.5f, blue = 0.5f)),
            ambient =
                VoxelAmbient(
                    0.1f,
                    0.2f,
                    0.3f,
                    1f,
                    orbRotationX = 0f,
                    orbRotationY = rotationY,
                    orbRotationZ = 0f,
                ),
        )

    private fun shortestArc(
        from: Float,
        to: Float,
    ): Float {
        var arc = (to - from) % TWO_PI
        if (arc > PI.toFloat()) arc -= TWO_PI
        if (arc < -PI.toFloat()) arc += TWO_PI
        return arc
    }

    private fun assertNear(
        expected: Float,
        actual: Float,
    ) {
        assertTrue(abs(expected - actual) <= TOLERANCE, "expected $expected but was $actual")
    }

    private companion object {
        const val FPS = 10
        val FRAME = 100.milliseconds
        const val LOOP_FRAMES = 6
        const val TRANSITION_FRAMES = 4

        const val SPIN = "spin"
        const val OTHER = "other"

        const val SPIN_RATE = 0.1f
        const val OTHER_RATE = 0.2f
        const val TRANSITION_RATE = 0.3f

        const val TWO_PI = (2.0 * PI).toFloat()
        const val TOLERANCE = 1e-3f
    }
}
