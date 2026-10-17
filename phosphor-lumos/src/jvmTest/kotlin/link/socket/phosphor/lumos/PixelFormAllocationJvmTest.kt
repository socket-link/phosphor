package link.socket.phosphor.lumos

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.look.ViewRotation

/**
 * PHO-72: a turning orb costs [PixelForm.roundedRect] its rewritten positions
 * and nothing else — no new sample, no new arrays — across 100 frames.
 *
 * `FormSample.positions` is a list of immutable `Vector3`s, so rewriting one
 * is a fresh 24-byte object; that is the whole per-frame bill. A new sample
 * per frame, as the PHO-68 prototype built, costs several times that in arrays
 * and lists on top. The bound sits between the two.
 */
class PixelFormAllocationJvmTest {
    @Test
    fun `100 frames of a turning orb allocate only the rewritten positions`() {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        if (threads == null || !threads.isThreadAllocatedMemorySupported) return
        threads.isThreadAllocatedMemoryEnabled = true

        val form =
            PixelForm.roundedRect(
                RectPx(left = 120f, top = 170f, width = 160f, height = 60f, cornerRadius = 14f),
                OrbViewport(centerXPx = 200f, centerYPx = 200f, radiusPx = 160f),
            )
        val rotation = ViewRotation()
        var frame = 0

        fun turn() {
            frame++
            rotation.set(0.002f * frame, 0.01f * frame, 0.12f, 0.6f)
            form.sample(COUNT, LumosLook.BASE, frame / 60f, rotation)
        }

        // Lay out, and let the JIT settle, before measuring.
        repeat(WARM_UP_FRAMES) { turn() }

        val id = Thread.currentThread().threadId()
        val before = threads.getThreadAllocatedBytes(id)
        repeat(FRAMES) { turn() }
        val perFrame = (threads.getThreadAllocatedBytes(id) - before) / FRAMES

        assertTrue(
            perFrame <= COUNT * BYTES_PER_CELL,
            "a turning frame allocated $perFrame bytes, past ${COUNT * BYTES_PER_CELL} for $COUNT rewritten positions",
        )
    }

    private companion object {
        const val COUNT = 1141
        const val WARM_UP_FRAMES = 200
        const val FRAMES = 100

        // A Vector3 is 24 bytes with compressed oops; headroom for an uncompressed header, not for arrays.
        const val BYTES_PER_CELL = 32L
    }
}
