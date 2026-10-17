package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.probe.FrameProbe

/**
 * PHO-53: the rules a pixel-surface orb keeps about time — speed, the cap on a step, alignment to
 * a shared look clock, reduced motion, the paused redraw — and the projection it reuses. Moved
 * with [OrbStepper] out of `LumosOrb`'s driver (PHO-85).
 */
class OrbStepperTest {
    @Test
    fun `the first step passes no time`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }

        stepper.step(secondsToNanos(5.0))

        assertEquals(listOf(0f), source.steps)
    }

    @Test
    fun `a step passes the frame gap times speed`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }
        stepper.update(LumosConfig(speed = 2f), FrameProbe.Disabled)

        stepper.step(secondsToNanos(1.0))
        stepper.step(secondsToNanos(1.016))

        assertEquals(0.032f, source.steps.last(), 1e-6f)
    }

    @Test
    fun `the first step after a long stop is a tenth of a second times speed`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }
        stepper.update(LumosConfig(speed = 1.5f), FrameProbe.Disabled)
        stepper.step(secondsToNanos(1.0))

        stepper.rejoin()
        stepper.step(secondsToNanos(6.0))

        assertEquals(0.15f, source.steps.last(), 1e-6f)
    }

    @Test
    fun `an orb aligns to its look's clock on its first step and on each return`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }

        stepper.step(secondsToNanos(1.0))
        stepper.step(secondsToNanos(1.016))
        assertEquals(listOf(0.0), source.aligned)

        stepper.rejoin()
        stepper.step(secondsToNanos(3.016))

        // The key moved a capped tenth; the source is aligned one step behind, so stepping that
        // tenth lands it on the key's time.
        assertEquals(2, source.aligned.size)
        assertEquals(0.016, source.aligned.last(), 1e-6)
    }

    @Test
    fun `an orb returning to a look its peers kept moving takes their phase`() {
        val clock = LookClock()
        val away = RecordingSource()
        val stayed = RecordingSource()
        val awayStepper = OrbStepper(away, clock) { false }
        val stayedStepper = OrbStepper(stayed, clock) { false }
        awayStepper.step(secondsToNanos(10.0))

        // Two seconds of frames for the orb that stayed; none for the one scrolled away.
        var now = 10.0
        while (now < 12.0) {
            stayedStepper.step(secondsToNanos(now))
            now += FRAME_SECONDS
        }
        awayStepper.rejoin()
        awayStepper.step(secondsToNanos(now))
        stayedStepper.step(secondsToNanos(now))

        val keyTime = assertNotNull(clock.timeOf(LookClock.key("idle", 1f)))
        assertEquals(keyTime - LookClock.MAX_GAP_SECONDS, away.aligned.last(), 1e-6)
    }

    @Test
    fun `a source without a look is never aligned`() {
        val source = RecordingSource(key = null)
        val stepper = OrbStepper(source, LookClock()) { false }

        stepper.step(secondsToNanos(1.0))
        stepper.rejoin()
        stepper.step(secondsToNanos(2.0))

        assertTrue(source.aligned.isEmpty())
    }

    @Test
    fun `a change of look is not a return and moves no phase`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }
        stepper.step(secondsToNanos(1.0))

        source.key = "thinking"
        stepper.step(secondsToNanos(1.016))

        assertEquals(1, source.aligned.size)
    }

    @Test
    fun `reduced motion draws stills instead of stepping`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }
        stepper.update(LumosConfig(reducedMotion = true), FrameProbe.Disabled)

        stepper.step(secondsToNanos(1.0))
        stepper.step(secondsToNanos(1.05))

        assertTrue(source.steps.isEmpty())
        assertEquals(2, source.stills.size)
        assertEquals(0f, source.stills[0])
        assertEquals(0.05f, source.stills[1], 1e-6f)
    }

    @Test
    fun `the platform asking for reduced motion is enough on its own`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { true }

        stepper.step(secondsToNanos(1.0))

        assertTrue(stepper.reducedMotion)
        assertTrue(source.steps.isEmpty())
        assertEquals(1, source.stills.size)
    }

    @Test
    fun `nothing is projected until the orb has a size`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }

        stepper.step(secondsToNanos(1.0))
        assertNull(stepper.frame)

        stepper.resize(100, 100)
        assertEquals(100, assertNotNull(stepper.frame).width)
    }

    @Test
    fun `a frame handed back unchanged is not projected again`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }
        stepper.resize(100, 100)
        stepper.step(secondsToNanos(1.0))
        val first = stepper.frame

        stepper.step(secondsToNanos(1.016))
        assertSame(first, stepper.frame)

        source.frame = frame(tick = 2L)
        stepper.step(secondsToNanos(1.032))
        assertNotSame(first, stepper.frame)
    }

    @Test
    fun `a resize projects the held frame again at the new size`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }
        stepper.resize(100, 100)
        stepper.step(secondsToNanos(1.0))

        stepper.resize(240, 240)

        assertEquals(240, assertNotNull(stepper.frame).width)
        assertEquals(1, source.steps.size)
    }

    @Test
    fun `each voxel is half a lattice cell times the dot size`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }
        stepper.update(LumosConfig(dotSize = 1.5f), FrameProbe.Disabled)
        stepper.resize(400, 300)

        stepper.step(secondsToNanos(1.0))

        // min(400, 300) / (4 · resolution 10) · 1.5
        assertEquals(11.25f, assertNotNull(stepper.frame).voxels.single().radiusPx, 1e-4f)
    }

    @Test
    fun `the tilt reaches the projection`() {
        val source = RecordingSource(frame = frame(cellY = 0.5f))
        val stepper = OrbStepper(source, LookClock()) { false }
        stepper.resize(400, 400)
        stepper.step(secondsToNanos(1.0))
        assertEquals(100f, assertNotNull(stepper.frame).voxels.single().screenY, 1e-3f)

        stepper.update(LumosConfig(tiltDegrees = 90f), FrameProbe.Disabled)

        // Seen from straight above, a cell up the Y axis sits at the centre of the canvas.
        assertEquals(200f, assertNotNull(stepper.frame).voxels.single().screenY, 1e-3f)
    }

    @Test
    fun `holding draws one still and then only when the source changes`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }
        stepper.resize(100, 100)

        stepper.hold()
        stepper.pollHeld()
        stepper.pollHeld()
        assertEquals(listOf(0f), source.stills)

        source.revisionValue++
        stepper.pollHeld()
        stepper.pollHeld()
        assertEquals(listOf(0f, 0f), source.stills)
    }

    @Test
    fun `holding keeps a frame already drawn`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }
        stepper.resize(100, 100)
        stepper.step(secondsToNanos(1.0))
        val drawn = stepper.frame

        stepper.hold()

        assertTrue(source.stills.isEmpty())
        assertSame(drawn, stepper.frame)
    }

    @Test
    fun `each call hands back the frame it projected and null when nothing new was drawn`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }

        assertNull(stepper.step(secondsToNanos(1.0)), "no size, nothing projected")
        val resized = assertNotNull(stepper.resize(100, 100))
        assertSame(resized, stepper.frame)
        assertNull(stepper.resize(100, 100), "the same size reuses the projection")
        assertNull(stepper.step(secondsToNanos(1.016)), "the same frame is not projected again")
        assertNull(stepper.update(LumosConfig(), FrameProbe.Disabled), "nor is an unchanged config")

        source.frame = frame(tick = 2L)
        val stepped = assertNotNull(stepper.step(secondsToNanos(1.032)))
        assertSame(stepped, stepper.frame)
        assertEquals(2L, stepped.tick)
    }

    @Test
    fun `a paused orb hands back only the stills it draws`() {
        val source = RecordingSource()
        val stepper = OrbStepper(source, LookClock()) { false }
        stepper.resize(100, 100)

        assertNotNull(stepper.hold(), "the first still")
        assertNull(stepper.pollHeld(), "the source has not changed")

        source.revisionValue++
        source.frame = frame(tick = 2L)
        assertEquals(2L, assertNotNull(stepper.pollHeld()).tick)
        assertNull(stepper.hold(), "a frame is already drawn")
    }

    private class RecordingSource(
        var key: String? = "idle",
        var frame: VoxelFrame = frame(),
    ) : LumosFrameSource {
        val steps = mutableListOf<Float>()
        val stills = mutableListOf<Float>()
        val aligned = mutableListOf<Double>()
        var revisionValue = 0L

        override fun next(dt: Float): VoxelFrame {
            steps += dt
            return frame
        }

        override fun still(dt: Float): VoxelFrame {
            stills += dt
            return frame
        }

        override val revision: Long get() = revisionValue

        override val lookKey: String? get() = key

        override fun align(lookTime: Double) {
            aligned += lookTime
        }
    }

    private companion object {
        const val FRAME_SECONDS = 1.0 / 60.0

        fun secondsToNanos(seconds: Double): Long = (seconds * 1_000_000_000.0).toLong()

        fun frame(
            tick: Long = 1L,
            cellY: Float = 0f,
        ): VoxelFrame =
            VoxelFrame(
                tick = tick,
                timestampEpochMillis = 0L,
                resolution = 10,
                cells = listOf(VoxelCell(x = 0f, y = cellY, z = 0f, scale = 1f, red = 1f, green = 1f, blue = 1f)),
                ambient =
                    VoxelAmbient(
                        glowRed = 0f,
                        glowGreen = 0f,
                        glowBlue = 0f,
                        glowIntensity = 0f,
                        orbRotationX = 0f,
                        orbRotationY = 0f,
                        orbRotationZ = 0f,
                    ),
            )
    }
}
