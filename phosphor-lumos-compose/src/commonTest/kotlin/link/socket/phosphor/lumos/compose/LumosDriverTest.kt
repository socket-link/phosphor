package link.socket.phosphor.lumos.compose

import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LookClock
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame

/**
 * What [LumosDriver] adds to the `OrbStepper` it wraps: the frame it draws, in Compose state.
 * The rules about time are the stepper's, and `OrbStepperTest` in `phosphor-lumos` covers them.
 */
class LumosDriverTest {
    @Test
    fun `the canvas frame is Compose state`() {
        val driver = LumosDriver(StillSource(), LookClock()) { false }
        val reads = mutableListOf<Any>()

        Snapshot.observe(readObserver = { reads += it }) { driver.canvasFrame }

        assertTrue(reads.isNotEmpty())
    }

    @Test
    fun `the canvas frame follows each new projection and only a new one`() {
        val source = StillSource()
        val driver = LumosDriver(source, LookClock()) { false }

        driver.step(secondsToNanos(1.0))
        assertNull(driver.canvasFrame)

        driver.resize(100, 100)
        val first = assertNotNull(driver.canvasFrame)
        assertEquals(100, first.width)

        driver.step(secondsToNanos(1.016))
        assertSame(first, driver.canvasFrame)

        source.frame = frame(tick = 2L)
        driver.step(secondsToNanos(1.032))
        assertNotSame(first, driver.canvasFrame)
    }

    @Test
    fun `an unchanged frame writes no state`() {
        val source = StillSource()
        val driver = LumosDriver(source, LookClock()) { false }
        driver.resize(100, 100)
        driver.step(secondsToNanos(1.0))
        Snapshot.sendApplyNotifications()

        val changed = mutableListOf<Any>()
        val observer = Snapshot.registerApplyObserver { states, _ -> changed.addAll(states) }
        try {
            driver.step(secondsToNanos(1.016))
            driver.pollHeld()
            Snapshot.sendApplyNotifications()
            assertTrue(changed.isEmpty(), "nothing new was drawn")

            source.frame = frame(tick = 2L)
            driver.step(secondsToNanos(1.032))
            Snapshot.sendApplyNotifications()
            assertTrue(changed.isNotEmpty(), "a new frame was drawn")
        } finally {
            observer.dispose()
        }
    }

    private class StillSource(
        var frame: VoxelFrame = frame(),
    ) : LumosFrameSource {
        override fun next(dt: Float): VoxelFrame = frame

        override fun still(dt: Float): VoxelFrame = frame
    }

    private companion object {
        fun secondsToNanos(seconds: Double): Long = (seconds * 1_000_000_000.0).toLong()

        fun frame(tick: Long = 1L): VoxelFrame =
            VoxelFrame(
                tick = tick,
                timestampEpochMillis = 0L,
                resolution = 10,
                cells = listOf(VoxelCell(x = 0f, y = 0f, z = 0f, scale = 1f, red = 1f, green = 1f, blue = 1f)),
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
