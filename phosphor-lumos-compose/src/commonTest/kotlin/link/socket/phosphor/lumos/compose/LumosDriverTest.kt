package link.socket.phosphor.lumos.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LookClock
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.probe.FrameProbe

/**
 * PHO-53: the rules `LumosOrb` keeps about time — speed, the cap on a step, alignment to a shared
 * look clock, reduced motion, the paused redraw — and the projection it reuses.
 */
class LumosDriverTest {
    @Test
    fun `the first step passes no time`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { false }

        driver.step(secondsToNanos(5.0))

        assertEquals(listOf(0f), source.steps)
    }

    @Test
    fun `a step passes the frame gap times speed`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { false }
        driver.update(LumosConfig(speed = 2f), FrameProbe.Disabled)

        driver.step(secondsToNanos(1.0))
        driver.step(secondsToNanos(1.016))

        assertEquals(0.032f, source.steps.last(), 1e-6f)
    }

    @Test
    fun `the first step after a long stop is a tenth of a second times speed`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { false }
        driver.update(LumosConfig(speed = 1.5f), FrameProbe.Disabled)
        driver.step(secondsToNanos(1.0))

        driver.rejoin()
        driver.step(secondsToNanos(6.0))

        assertEquals(0.15f, source.steps.last(), 1e-6f)
    }

    @Test
    fun `an orb aligns to its look's clock on its first step and on each return`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { false }

        driver.step(secondsToNanos(1.0))
        driver.step(secondsToNanos(1.016))
        assertEquals(listOf(0.0), source.aligned)

        driver.rejoin()
        driver.step(secondsToNanos(3.016))

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
        val awayDriver = LumosDriver(away, clock) { false }
        val stayedDriver = LumosDriver(stayed, clock) { false }
        awayDriver.step(secondsToNanos(10.0))

        // Two seconds of frames for the orb that stayed; none for the one scrolled away.
        var now = 10.0
        while (now < 12.0) {
            stayedDriver.step(secondsToNanos(now))
            now += FRAME_SECONDS
        }
        awayDriver.rejoin()
        awayDriver.step(secondsToNanos(now))
        stayedDriver.step(secondsToNanos(now))

        val keyTime = assertNotNull(clock.timeOf(LookClock.key("idle", 1f)))
        assertEquals(keyTime - LookClock.MAX_GAP_SECONDS, away.aligned.last(), 1e-6)
    }

    @Test
    fun `a source without a look is never aligned`() {
        val source = RecordingSource(key = null)
        val driver = LumosDriver(source, LookClock()) { false }

        driver.step(secondsToNanos(1.0))
        driver.rejoin()
        driver.step(secondsToNanos(2.0))

        assertTrue(source.aligned.isEmpty())
    }

    @Test
    fun `a change of look is not a return and moves no phase`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { false }
        driver.step(secondsToNanos(1.0))

        source.key = "thinking"
        driver.step(secondsToNanos(1.016))

        assertEquals(1, source.aligned.size)
    }

    @Test
    fun `reduced motion draws stills instead of stepping`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { false }
        driver.update(LumosConfig(reducedMotion = true), FrameProbe.Disabled)

        driver.step(secondsToNanos(1.0))
        driver.step(secondsToNanos(1.05))

        assertTrue(source.steps.isEmpty())
        assertEquals(2, source.stills.size)
        assertEquals(0f, source.stills[0])
        assertEquals(0.05f, source.stills[1], 1e-6f)
    }

    @Test
    fun `the platform asking for reduced motion is enough on its own`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { true }

        driver.step(secondsToNanos(1.0))

        assertTrue(driver.reducedMotion)
        assertTrue(source.steps.isEmpty())
        assertEquals(1, source.stills.size)
    }

    @Test
    fun `nothing is projected until the orb has a size`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { false }

        driver.step(secondsToNanos(1.0))
        assertNull(driver.canvasFrame)

        driver.resize(100, 100)
        assertEquals(100, assertNotNull(driver.canvasFrame).width)
    }

    @Test
    fun `a frame handed back unchanged is not projected again`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { false }
        driver.resize(100, 100)
        driver.step(secondsToNanos(1.0))
        val first = driver.canvasFrame

        driver.step(secondsToNanos(1.016))
        assertSame(first, driver.canvasFrame)

        source.frame = frame(tick = 2L)
        driver.step(secondsToNanos(1.032))
        assertNotSame(first, driver.canvasFrame)
    }

    @Test
    fun `a resize projects the held frame again at the new size`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { false }
        driver.resize(100, 100)
        driver.step(secondsToNanos(1.0))

        driver.resize(240, 240)

        assertEquals(240, assertNotNull(driver.canvasFrame).width)
        assertEquals(1, source.steps.size)
    }

    @Test
    fun `each voxel is half a lattice cell times the dot size`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { false }
        driver.update(LumosConfig(dotSize = 1.5f), FrameProbe.Disabled)
        driver.resize(400, 300)

        driver.step(secondsToNanos(1.0))

        // min(400, 300) / (4 · resolution 10) · 1.5
        assertEquals(11.25f, assertNotNull(driver.canvasFrame).voxels.single().radiusPx, 1e-4f)
    }

    @Test
    fun `the tilt reaches the projection`() {
        val source = RecordingSource(frame = frame(cellY = 0.5f))
        val driver = LumosDriver(source, LookClock()) { false }
        driver.resize(400, 400)
        driver.step(secondsToNanos(1.0))
        assertEquals(100f, assertNotNull(driver.canvasFrame).voxels.single().screenY, 1e-3f)

        driver.update(LumosConfig(tiltDegrees = 90f), FrameProbe.Disabled)

        // Seen from straight above, a cell up the Y axis sits at the centre of the canvas.
        assertEquals(200f, assertNotNull(driver.canvasFrame).voxels.single().screenY, 1e-3f)
    }

    @Test
    fun `holding draws one still and then only when the source changes`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { false }
        driver.resize(100, 100)

        driver.hold()
        driver.pollHeld()
        driver.pollHeld()
        assertEquals(listOf(0f), source.stills)

        source.revisionValue++
        driver.pollHeld()
        driver.pollHeld()
        assertEquals(listOf(0f, 0f), source.stills)
    }

    @Test
    fun `holding keeps a frame already drawn`() {
        val source = RecordingSource()
        val driver = LumosDriver(source, LookClock()) { false }
        driver.resize(100, 100)
        driver.step(secondsToNanos(1.0))
        val drawn = driver.canvasFrame

        driver.hold()

        assertTrue(source.stills.isEmpty())
        assertSame(drawn, driver.canvasFrame)
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
