package link.socket.phosphor.trace

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneConfiguration

/**
 * PHO-53: every source `:phosphor-trace` declares opens as a `LumosFrameSource`, stepped by a
 * driver instead of pacing itself, and keeps what its own stream promises — the generator's scene,
 * the player's carried rotation, the machine's transitions.
 */
class FrameSourcesTest {
    @Test
    fun `a generator's frame source steps the same scene its fixed step does`() {
        val stepped = SignalGenerator(scene(), fps = 20)
        val driven = SignalGenerator(scene(), fps = 20).asFrameSource()

        repeat(4) { assertEquals(stepped.nextFrame(), driven.next(1f / 20f)) }
    }

    @Test
    fun `a live source opens through the generator it wraps`() {
        val reference = SignalGenerator(scene(), fps = 20)
        val source = LumosSource.Live(scene(), fps = 20).asFrameSource()

        repeat(3) { assertEquals(reference.nextFrame(), source.next(1f / 20f)) }
    }

    @Test
    fun `a player's frame source carries a loop's rotation across its seam`() {
        val source = TracePlayer(trace(), SPIN).asFrameSource()

        rotationSteps(source, steps = 3 * LOOP_FRAMES).forEach { assertNear(SPIN_RATE, it) }
    }

    @Test
    fun `without carrying a player's frame source replays the drop at the seam`() {
        val source = TracePlayer(trace(), SPIN).asFrameSource(carryRotation = false)

        val steps = rotationSteps(source, steps = LOOP_FRAMES)

        assertNear(-(LOOP_FRAMES - 1) * SPIN_RATE, steps.last())
    }

    @Test
    fun `a trace source opens through its player and keeps its carry`() {
        val source = LumosSource.Trace(trace(), SPIN).asFrameSource()

        rotationSteps(source, steps = 2 * LOOP_FRAMES).forEach { assertNear(SPIN_RATE, it) }
    }

    @Test
    fun `a player's still holds the frame the playhead rests on`() {
        val player = TracePlayer(trace(), SPIN)
        val source = player.asFrameSource()
        source.next(0.25f)
        val playhead = player.playhead

        val held = source.still(0.5f)

        assertEquals(playhead, player.playhead)
        assertSame(held, source.still(0.5f))
        assertEquals(trace().toFrames()[2].cells, held.cells)
    }

    @Test
    fun `a player's revision moves on a seek and on a segment change`() {
        val player = TracePlayer(trace(), SPIN)
        val source = player.asFrameSource()
        val start = source.revision
        assertEquals(start, source.revision)

        player.seek(2)
        val sought = source.revision
        assertNotEquals(start, sought)

        player.setSegment(OTHER)
        assertNotEquals(sought, source.revision)
    }

    @Test
    fun `a player keys its clock on the segment it plays`() {
        assertEquals(SPIN, TracePlayer(trace(), SPIN).asFrameSource().lookKey)
        assertNull(TracePlayer(trace()).asFrameSource().lookKey, "the whole recording is no look")
    }

    @Test
    fun `a machine's still lands a requested state at once`() {
        val machine = TraceStateMachine(StateMachineTraces.threeState(), initialState = "idle")
        val source = machine.asFrameSource()
        source.next(0.25f)
        machine.requestState("thinking").getOrThrow()
        assertTrue(machine.isTransitioning)

        val landed = source.still(0f)

        assertFalse(machine.isTransitioning)
        assertEquals("thinking", machine.state.value)
        assertSame(landed, source.still(0.1f))
    }

    @Test
    fun `a machine's still lands a request held behind a transition too`() {
        val machine = TraceStateMachine(StateMachineTraces.threeState(), initialState = "idle")
        val source = machine.asFrameSource()
        machine.requestState("thinking").getOrThrow()
        machine.requestState("idle").getOrThrow()

        source.still(0f)

        assertEquals("idle", machine.state.value)
        assertFalse(machine.isTransitioning)
    }

    @Test
    fun `a machine's revision moves when a state is requested`() {
        val machine = TraceStateMachine(StateMachineTraces.threeState(), initialState = "idle")
        val source = machine.asFrameSource()
        val start = source.revision
        assertEquals(start, source.revision)

        machine.requestState("thinking").getOrThrow()

        assertNotEquals(start, source.revision)
    }

    @Test
    fun `a machine keys its clock on the state it is heading to`() {
        val machine = TraceStateMachine(StateMachineTraces.threeState(), initialState = "idle")
        val source = machine.asFrameSource()
        assertEquals("idle", source.lookKey)

        machine.requestState("thinking").getOrThrow()

        assertEquals("thinking", source.lookKey)
    }

    private fun rotationSteps(
        source: LumosFrameSource,
        steps: Int,
    ): List<Float> {
        var previous = source.next(0f).ambient.orbRotationY
        return List(steps) {
            val next = source.next(FRAME_SECONDS).ambient.orbRotationY
            shortestArc(previous, next).also { previous = next }
        }
    }

    private fun scene(): SceneConfiguration =
        SceneConfiguration(
            width = 8,
            height = 8,
            enableWaveform = false,
            enableParticles = false,
            enableFlow = false,
            enableEmitters = false,
            enableCamera = false,
            enableAtmosphere = true,
            initialAtmosphere = AtmospherePresets.THINKING.copy(resolution = 3),
        )

    // Two loops turning at their own rates from unrelated angles, as in RotationCarryTest.
    private fun trace(): VoxelTrace {
        val frames = ArrayList<VoxelFrame>()
        val segments = ArrayList<TraceSegment>()

        fun record(
            name: String,
            from: Float,
            rate: Float,
        ) {
            val start = frames.size
            repeat(LOOP_FRAMES) { step -> frames += frameOf(frames.size, from + rate * step) }
            segments += TraceSegment(name, start, frames.size - 1, loop = true)
        }

        record(SPIN, from = 1.0f, rate = SPIN_RATE)
        record(OTHER, from = 4.0f, rate = OTHER_RATE)
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
            cells =
                listOf(
                    VoxelCell(x = tick.toFloat(), y = 0f, z = 0f, scale = 1f, red = 0.5f, green = 0.5f, blue = 0.5f),
                ),
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
        const val FRAME_SECONDS = 0.1f
        const val LOOP_FRAMES = 6

        const val SPIN = "spin"
        const val OTHER = "other"

        const val SPIN_RATE = 0.1f
        const val OTHER_RATE = 0.2f

        const val TWO_PI = (2.0 * PI).toFloat()
        const val TOLERANCE = 1e-3f
    }
}
