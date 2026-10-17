package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PHO-65 Task B: the clock. Time moves only while connected, uncontrolled and playing; a step is
 * capped at a tenth of a second; a controller owns time through `seek`; a finite sequence ends.
 */
class LumosSequencePlayerTest {
    @Test
    fun `nothing moves until a consumer connects`() {
        val player = LumosSequencePlayer(sequence())

        player.advance(0.05f)

        assertEquals(0f, player.timeSeconds)
        assertFalse(player.ticking)
    }

    @Test
    fun `a connected playing player advances by dt`() {
        val player = LumosSequencePlayer(sequence())
        player.connect()

        player.advance(0.05f)
        player.advance(0.05f)

        assertEquals(0.1f, player.timeSeconds, 1e-6f)
        assertTrue(player.ticking)
    }

    @Test
    fun `a step is capped at a tenth of a second`() {
        val player = LumosSequencePlayer(sequence())
        player.connect()

        player.advance(5f)

        assertEquals(LumosSequencePlayer.MAX_STEP_SECONDS, player.timeSeconds)
    }

    @Test
    fun `pause holds and play resumes`() {
        val player = LumosSequencePlayer(sequence())
        player.connect()
        player.advance(0.05f)

        player.pause()
        player.advance(0.05f)
        assertEquals(0.05f, player.timeSeconds, 1e-6f)
        assertFalse(player.playing)

        player.play()
        player.advance(0.05f)
        assertEquals(0.1f, player.timeSeconds, 1e-6f)
    }

    @Test
    fun `autoplay false starts paused`() {
        val player = LumosSequencePlayer(sequence(), autoplay = false)
        player.connect()

        player.advance(0.05f)

        assertEquals(0f, player.timeSeconds)
    }

    @Test
    fun `a controller owns time and seek is its clock`() {
        val player = LumosSequencePlayer(sequence())
        player.connect()
        val release = player.control()

        player.advance(0.05f)
        assertEquals(0f, player.timeSeconds)
        assertEquals(1, player.controllers)
        assertFalse(player.ticking)

        player.seek(1.5f)
        assertEquals(1.5f, player.timeSeconds)
        assertEquals(1, player.snapshot.stepIndex)

        release()
        release()
        assertEquals(0, player.controllers)
        player.advance(0.05f)
        assertEquals(1.55f, player.timeSeconds, 1e-6f)
    }

    @Test
    fun `the clock stops when the last consumer releases`() {
        val player = LumosSequencePlayer(sequence())
        val first = player.connect()
        val second = player.connect()
        assertEquals(2, player.connections)

        first()
        first()
        player.advance(0.05f)
        assertEquals(0.05f, player.timeSeconds, 1e-6f)

        second()
        player.advance(0.05f)
        assertEquals(0.05f, player.timeSeconds, 1e-6f)
        assertEquals(0, player.connections)
    }

    @Test
    fun `a finite sequence ends and pauses at its end`() {
        val player = LumosSequencePlayer(sequence().copy(loop = false))
        player.connect()

        repeat(25) { player.advance(0.1f) }

        assertEquals(2f, player.timeSeconds)
        assertFalse(player.playing)
        assertTrue(player.snapshot.ended)

        player.play()
        assertEquals(0f, player.timeSeconds)
        assertTrue(player.playing)
    }

    @Test
    fun `a looping sequence wraps`() {
        val player = LumosSequencePlayer(sequence())
        player.connect()

        repeat(25) { player.advance(0.1f) }

        assertEquals(2.5f, player.timeSeconds, 1e-5f)
        assertEquals(1, player.snapshot.cycle)
        assertEquals(0, player.snapshot.stepIndex)
        assertTrue(player.playing)
    }

    @Test
    fun `restart goes back to the start playing`() {
        val player = LumosSequencePlayer(sequence())
        player.connect()
        player.advance(0.1f)
        player.pause()

        player.restart()

        assertEquals(0f, player.timeSeconds)
        assertTrue(player.playing)
    }

    @Test
    fun `seek clamps negative and NaN time to the start`() {
        val player = LumosSequencePlayer(sequence())

        player.seek(-1f)
        assertEquals(0f, player.timeSeconds)
        player.seek(Float.NaN)
        assertEquals(0f, player.timeSeconds)
    }

    @Test
    fun `a non-positive or NaN step moves nothing`() {
        val player = LumosSequencePlayer(sequence())
        player.connect()

        player.advance(0f)
        player.advance(-1f)
        player.advance(Float.NaN)

        assertEquals(0f, player.timeSeconds)
    }

    private fun sequence(): LumosSequence =
        LumosSequence(
            steps =
                listOf(
                    LumosStep("idle", 1f, atmospherePreset = "idle", loop = true),
                    LumosStep("thinking", 1f, atmospherePreset = "thinking", loop = true),
                ),
        )
}
