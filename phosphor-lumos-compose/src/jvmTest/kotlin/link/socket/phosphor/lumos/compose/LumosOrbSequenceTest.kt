package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosSequence
import link.socket.phosphor.lumos.LumosSequencePlayer
import link.socket.phosphor.lumos.LumosStep
import link.socket.phosphor.palette.AtmospherePresets

/**
 * PHO-65 Task D: the orb that takes a [LumosSequencePlayer] connects to it while it runs,
 * advances it with every frame it steps, and releases it when it stops — so the sequence clock
 * is the orb's, and holds when the orb does.
 *
 * The orb's frame loop never ends, so the test clock is advanced by hand rather than until idle.
 */
@OptIn(ExperimentalTestApi::class)
class LumosOrbSequenceTest {
    @Test
    fun `a running orb connects to the player and advances it`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val player = LumosSequencePlayer(sequence())
            val source = LiveFrameSource(AtmospherePresets.IDLE).also { it.follow(player) }

            setContent { LumosOrb(source, player, LumosConfig(), Modifier.size(64.dp)) }
            repeat(4) { mainClock.advanceTimeByFrame() }

            assertEquals(1, player.connections)
            assertTrue(
                player.timeSeconds > 0f,
                "the orb's frames should have moved the sequence, was ${player.timeSeconds}",
            )
        }

    @Test
    fun `a paused orb releases the player and the sequence holds`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val player = LumosSequencePlayer(sequence())
            val source = LiveFrameSource(AtmospherePresets.IDLE).also { it.follow(player) }
            var paused by mutableStateOf(false)

            setContent { LumosOrb(source, player, LumosConfig(paused = paused), Modifier.size(64.dp)) }
            repeat(4) { mainClock.advanceTimeByFrame() }
            assertTrue(player.timeSeconds > 0f)

            paused = true
            // The frame already in flight may land; after it the clock holds.
            mainClock.advanceTimeByFrame()
            val held = player.timeSeconds
            repeat(3) { mainClock.advanceTimeByFrame() }

            assertEquals(0, player.connections)
            assertEquals(held, player.timeSeconds)
        }

    private fun sequence(): LumosSequence =
        LumosSequence(
            steps =
                listOf(
                    LumosStep("idle", 1f, atmospherePreset = "idle", loop = true),
                    LumosStep("idle→thinking", 0.8f, atmospherePreset = "thinking"),
                    LumosStep("thinking", 1f, atmospherePreset = "thinking", loop = true),
                ),
        )
}
