package link.socket.phosphor.lumos.dom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LookClock

/**
 * The gates and the clock around the stepper, driven through a fake page: when the orb steps, when
 * it stops, and when it touches the DOM. The rules about time are `OrbStepper`'s, and
 * `OrbStepperTest` in `phosphor-lumos` covers them.
 */
class DomLumosDriverTest {
    @Test
    fun `an orb with a size draws on its first animation frame and asks for the next`() {
        val (host, handle) = mounted()

        assertEquals(1, host.pendingFrames)
        host.frame()

        assertEquals(1L, handle.driver.frames)
        assertTrue(handle.driver.cells > 0)
        assertEquals(1, host.pendingFrames)
        assertEquals("running", handle.driver.state)
    }

    @Test
    fun `an orb without a size draws nothing until it is laid out`() {
        val host = FakeHost()
        val handle = mountOrb(FakeDocument().root(), MountOptions(resolution = 2), host, FakeDocument(), LookClock())

        host.frame()
        assertEquals(0L, handle.driver.frames)

        host.resize(100.0, 100.0)
        assertEquals(1L, handle.driver.frames, "the held frame is projected at the new size")
        assertEquals(100 to 100, handle.driver.size)
    }

    @Test
    fun `a resize re-projects the held frame even while stopped`() {
        val (host, handle) = mounted()
        host.frame()
        host.setIntersecting(false)

        host.resize(50.0, 50.0)

        assertEquals(2L, handle.driver.frames)
        assertEquals("0 0 50 50", (handle.svg as FakeNode).attributes["viewBox"])
    }

    @Test
    fun `off screen it stops, and back on screen it carries on`() {
        val (host, handle) = mounted()
        host.frame()

        host.setIntersecting(false)
        assertEquals(0, host.pendingFrames)
        assertEquals("stopped", handle.driver.state)
        host.frame()
        assertEquals(1L, handle.driver.frames)

        host.setIntersecting(true)
        assertEquals(1, host.pendingFrames)
        host.frame()
        assertEquals(2L, handle.driver.frames)
    }

    @Test
    fun `in a hidden page it stops, and when the page comes back it carries on`() {
        val (host, handle) = mounted()
        host.frame()

        host.setVisible(false)
        assertEquals(0, host.pendingFrames)
        host.frame()
        assertEquals(1L, handle.driver.frames)

        host.setVisible(true)
        host.frame()
        assertEquals(2L, handle.driver.frames)
    }

    @Test
    fun `an orb mounted in a hidden page waits for it`() {
        val host = FakeHost().sized(100.0, 100.0)
        host.hidden = true
        val handle = mountOrb(FakeDocument().root(), MountOptions(resolution = 2), host, FakeDocument(), LookClock())

        assertEquals(0, host.pendingFrames)
        host.setVisible(true)
        host.frame()
        assertEquals(1L, handle.driver.frames)
    }

    @Test
    fun `under the platform's reduced motion a still orb leaves the DOM alone until the source moves`() {
        val host = FakeHost().sized(100.0, 100.0)
        host.reduceMotion = true
        val handle = mount(host)
        val document = (handle.svg as FakeNode).document

        host.frame()
        assertEquals(1L, handle.driver.frames)
        assertTrue(handle.driver.reducedMotion)
        // The next step waits a slow beat rather than the next animation frame.
        assertEquals(0, host.pendingFrames)
        assertEquals(1, host.pendingTimers)

        val writes = document.writes
        repeat(5) {
            host.advance(64.0)
            host.frame()
        }
        assertEquals(1L, handle.driver.frames)
        assertEquals(writes, document.writes)

        handle.setAtmosphere("uncertain")
        host.advance(64.0)
        host.frame()
        assertEquals(2L, handle.driver.frames)
        assertTrue(document.writes > writes)
    }

    @Test
    fun `the reduceMotion option holds the orb still as the platform does`() {
        val host = FakeHost().sized(100.0, 100.0)
        val handle = mount(host, MountOptions(resolution = 2, reduceMotion = true))

        host.frame()
        repeat(5) {
            host.advance(64.0)
            host.frame()
        }

        assertTrue(handle.driver.reducedMotion)
        assertEquals(1L, handle.driver.frames)
    }

    @Test
    fun `animated, every frame draws something new`() {
        val (host, handle) = mounted()

        repeat(10) { host.frame() }

        assertFalse(handle.driver.reducedMotion)
        assertEquals(10L, handle.driver.frames)
    }

    @Test
    fun `max fps halves a 120 Hz display at 60, and 0 draws every frame`() {
        val (capped, cappedHandle) = mounted(MountOptions(resolution = 2, maxFps = 60.0))
        val (free, freeHandle) = mounted(MountOptions(resolution = 2, maxFps = 0.0))

        repeat(120) {
            capped.frame(1000.0 / 120.0)
            free.frame(1000.0 / 120.0)
        }

        assertEquals(60L, cappedHandle.driver.frames)
        assertEquals(60L, cappedHandle.driver.framesSkipped)
        assertEquals(120L, freeHandle.driver.frames)
    }

    @Test
    fun `max fps keeps every frame of a display no faster than the cap, jitter and all`() {
        val (host, handle) = mounted(MountOptions(resolution = 2, maxFps = 60.0))

        repeat(60) { i -> host.frame(if (i % 2 == 0) 16.2 else 17.1) }

        assertEquals(60L, handle.driver.frames)
    }

    @Test
    fun `paused, it holds its frame and draws once when a new atmosphere is set`() {
        val (host, handle) = mounted()
        host.frame()

        handle.pause()
        assertEquals("paused", handle.driver.state)
        assertEquals(0, host.pendingFrames)
        host.advance(1000.0)
        assertEquals(1L, handle.driver.frames)

        handle.setAtmosphere("ready")
        host.advance(250.0)
        assertEquals(2L, handle.driver.frames)
        host.advance(1000.0)
        assertEquals(2L, handle.driver.frames)

        handle.resume()
        assertEquals("running", handle.driver.state)
        assertEquals(0, host.pendingTimers)
        host.frame()
        assertEquals(3L, handle.driver.frames)
    }

    @Test
    fun `mounted paused, it draws one still once it has a size`() {
        val host = FakeHost().sized(100.0, 100.0)
        val handle = mount(host, MountOptions(resolution = 2, paused = true))

        assertEquals(1L, handle.driver.frames)
        assertEquals(0, host.pendingFrames)
        host.advance(1000.0)
        assertEquals(1L, handle.driver.frames)
    }

    @Test
    fun `unmount stops the clock and the observers and takes out what the orb added`() {
        val (host, handle) = mounted(MountOptions(resolution = 2, label = "Lumos"))
        host.frame()
        val svg = handle.svg as FakeNode

        handle.unmount()

        assertEquals(0, host.pendingFrames)
        assertEquals(0, host.pendingTimers)
        assertEquals(0, host.observers)
        assertTrue(svg.children.isEmpty())
        assertEquals(emptyMap(), svg.attributes)
        assertEquals("unmounted", handle.stats().state as String)

        // Every call after unmount does nothing.
        handle.resume()
        handle.setAtmosphere("ready")
        assertEquals(0, host.pendingFrames)
    }

    @Test
    fun `with a label the orb is one image, without one it is hidden from screen readers`() {
        val (_, labelled) = mounted(MountOptions(resolution = 2, label = "Lumos is thinking"))
        val labelledSvg = labelled.svg as FakeNode
        assertEquals("img", labelledSvg.attributes["role"])
        assertEquals("Lumos is thinking", labelledSvg.attributes["aria-label"])
        assertNull(labelledSvg.attributes["aria-hidden"])

        val (_, silent) = mounted()
        val silentSvg = silent.svg as FakeNode
        assertEquals("true", silentSvg.attributes["aria-hidden"])
        assertNull(silentSvg.attributes["role"])
    }

    @Test
    fun `stats report what the orb did`() {
        val (host, handle) = mounted(MountOptions(resolution = 2))
        repeat(3) { host.frame() }

        val stats = handle.stats()

        assertEquals("running", stats.state as String)
        assertEquals(3.0, stats.frames as Double)
        assertEquals(handle.driver.cells, stats.cells as Int)
        assertEquals("squares", stats.style as String)
        assertEquals("cell", stats.tint as String)
        assertEquals(100, stats.width as Int)
        assertTrue((stats.attributeWrites as Double) > 0.0)
    }

    @Test
    fun `orbs of one look share its clock, at any resolution`() {
        val clock = LookClock()
        val host = FakeHost().sized(100.0, 100.0)
        val a = mountOrb(FakeDocument().root(), MountOptions(resolution = 2), host, FakeDocument(), clock)
        val b = mountOrb(FakeDocument().root(), MountOptions(), host, FakeDocument(), clock)

        assertEquals("idle", a.scene.lookKey)
        assertEquals("idle", b.scene.lookKey)

        a.setLook("working")
        a.setAtmosphere("thinking")
        assertEquals("thinking/working", a.scene.lookKey)
    }

    private fun mounted(options: MountOptions = MountOptions(resolution = 2)): Pair<FakeHost, LumosHandle> {
        val host = FakeHost().sized(100.0, 100.0)
        return host to mount(host, options)
    }

    private fun mount(
        host: FakeHost,
        options: MountOptions = MountOptions(resolution = 2),
    ): LumosHandle {
        val document = FakeDocument()
        return mountOrb(document.root(), options, host, document, LookClock())
    }
}
