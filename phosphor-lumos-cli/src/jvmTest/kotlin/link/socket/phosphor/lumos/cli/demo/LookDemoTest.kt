package link.socket.phosphor.lumos.cli.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase

/**
 * PHO-80: the keys `LookDemo` reads, the table its header prints, and the look
 * the switch picks when the demo's own generator is driven round the phases.
 */
class LookDemoTest {
    @Test
    fun `p walks the seven phases and wraps from LOOP to PERCEIVE`() {
        val walked = generateSequence(nextPhase(CognitivePhase.NONE), ::nextPhase).take(8).toList()

        assertEquals(PHASE_CYCLE + CognitivePhase.PERCEIVE, walked)
        assertEquals(
            listOf("PERCEIVE", "RECALL", "OBSERVE", "PLAN", "EXECUTE", "LEARN", "LOOP"),
            PHASE_CYCLE.map { it.name },
        )
    }

    @Test
    fun `a cycles idle — processing — complete — spawning and back to idle`() {
        val walked = generateSequence(AgentActivityState.IDLE, ::nextActivity).take(5).toList()

        assertEquals(
            listOf(
                AgentActivityState.IDLE,
                AgentActivityState.PROCESSING,
                AgentActivityState.COMPLETE,
                AgentActivityState.SPAWNING,
                AgentActivityState.IDLE,
            ),
            walked,
        )
    }

    @Test
    fun `the header prints the confirmed table one line per phase`() {
        assertEquals(
            listOf(
                "  PERCEIVE  searching",
                "  RECALL    reasoning",
                "  OBSERVE   searching · lighthouse",
                "  PLAN      reasoning · twins",
                "  EXECUTE   working",
                "  LEARN     compacting · fuse",
                "  LOOP      base",
            ),
            phaseTable(),
        )
    }

    @Test
    fun `a full turn of p wears each phase's look and the status line names it`() {
        val generator = lookDemoGenerator()
        val source = generator.asFrameSource()
        source.next(STEP)
        assertEquals("thinking", source.lookKey)

        var phase = CognitivePhase.NONE
        val worn =
            PHASE_CYCLE.map {
                phase = nextPhase(phase)
                generator.setPhase(phase)
                repeat(SETTLE_STEPS) { source.next(STEP) }
                source.lookKey
            }

        assertEquals(
            listOf(
                "thinking/searching",
                "thinking/reasoning",
                "thinking/searching-lighthouse",
                "thinking/reasoning-twins",
                "thinking/working",
                "thinking/compacting-fuse",
                "thinking",
            ),
            worn,
        )
        val status = statusLine(phase, AgentActivityState.IDLE, escalated = false, lookKey = source.lookKey, width = 80)
        assertEquals(79, status.length)
        assertTrue(status.startsWith("LOOP · IDLE · calm | look thinking |"), status)
    }

    @Test
    fun `escalation wins over the phase and a set look holds until the signal next changes`() {
        val generator = lookDemoGenerator()
        val source = generator.asFrameSource()
        source.next(STEP)

        generator.setPhase(CognitivePhase.EXECUTE)
        repeat(SETTLE_STEPS) { source.next(STEP) }
        generator.setLook(Look(LumosLook.REASONING))
        repeat(SETTLE_STEPS) { source.next(STEP) }
        assertEquals("thinking/reasoning", source.lookKey)

        generator.setEscalated(true)
        repeat(SETTLE_STEPS) { source.next(STEP) }
        assertEquals("thinking/waiting", source.lookKey)

        generator.setEscalated(false)
        repeat(SETTLE_STEPS) { source.next(STEP) }
        assertEquals("thinking/working", source.lookKey)
    }

    @Test
    fun `the status line never runs past the terminal`() {
        val status =
            statusLine(
                CognitivePhase.PERCEIVE,
                AgentActivityState.PROCESSING,
                escalated = true,
                lookKey = "uncertain/searching-lighthouse",
                width = 40,
            )

        assertEquals(39, status.length)
        assertTrue(status.startsWith("PERCEIVE · PROCESSING · ESCALATED |"), status)
    }

    private companion object {
        const val STEP = 1f / 24f

        // A second of steps: past the 0.6 s look blend, so the look worn is the one the switch chose.
        const val SETTLE_STEPS = 24
    }
}
