package link.socket.phosphor.lumos.cli.demo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame.TerminalCell
import link.socket.phosphor.lumos.cli.projection.CliLattice
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase
import link.socket.phosphor.signal.LumosInput

/**
 * PHO-80: the keys `LookDemo` reads, the table its header prints, and the look
 * the switch picks when the demo's own generator is driven round the phases.
 * PHO-82: the arrow keys, the virtual pointer they move, and where it is drawn
 * and printed.
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

    @Test
    fun `arrows arrive as escape sequences — split across reads too — and their B is not a b`() {
        val keys = KeyReader()

        assertEquals(
            listOf(Press.Arrow(0, 1), Press.Key('b'), Press.Arrow(1, 0)),
            keys.read("\u001b[Ab\u001bOC".toList()),
            "ESC [ A is up, b is base, ESC O C is right in application cursor mode",
        )
        assertEquals(emptyList(), keys.read("\u001b[".toList()), "half a sequence waits for the rest")
        assertEquals(listOf(Press.Arrow(0, -1)), keys.read("B".toList()), "and the rest is down, not b")
        assertEquals(listOf(Press.Key('q')), keys.read("\u001bq".toList()), "a lone escape is dropped")
    }

    @Test
    fun `the pointer moves in whole steps and stays on the orb's square — hidden it is NONE`() {
        val pointer = VirtualPointer()
        assertSame(LumosInput.NONE, pointer.input)

        pointer.move(2, -1)
        assertSame(LumosInput.NONE, pointer.input, "a hidden pointer moves but is handed to nobody")
        pointer.toggle()
        assertEquals(LumosInput(pointer = Vector2(2 * STEP_SIZE, -1 * STEP_SIZE)), pointer.input)

        repeat(30) { pointer.move(1, 0) }
        assertEquals(12 * STEP_SIZE, pointer.input.pointer?.x, "twelve steps is as far as it goes")
        pointer.toggle()
        assertSame(LumosInput.NONE, pointer.input)
    }

    @Test
    fun `the plus marks the pointer's cell as the lattice places a cell there`() {
        val lattice = CliLattice(width = 40, height = 40)
        val blank = LumosTerminalFrame(40, 40, List(40 * 40) { TerminalCell() }, null, null, 0L)

        val centre = marked(blank, Vector2(0f, 0f), lattice)
        val corner = marked(blank, Vector2(-0.5f, 0.5f), lattice)

        // x spans the width and y the top half of the grid, as CliLattice projects.
        assertEquals('+', centre.cells[10 * 40 + 20].char)
        assertEquals('+', corner.cells[5 * 40 + 10].char)
        assertEquals(1, centre.cells.count { it.char == '+' })
        assertSame(blank, marked(blank, Vector2(1.5f, 0f), lattice), "a pointer off the grid marks nothing")
    }

    @Test
    fun `the status line prints the pointer after the look while it is shown`() {
        val shown =
            statusLine(
                CognitivePhase.PLAN,
                AgentActivityState.IDLE,
                escalated = false,
                lookKey = "thinking",
                width = 120,
                pointer = Vector2(0.24f, -0.08f),
            )
        val hidden = statusLine(CognitivePhase.PLAN, AgentActivityState.IDLE, escalated = false, "thinking", 120)

        assertTrue(shown.startsWith("PLAN · IDLE · calm | look thinking | pointer (+0.24, -0.08) |"), shown)
        assertTrue(!hidden.contains("pointer"), hidden)
        assertEquals(119, shown.length)
    }

    private companion object {
        const val STEP = 1f / 24f
        const val STEP_SIZE = 0.08f

        // A second of steps: past the 0.6 s look blend, so the look worn is the one the switch chose.
        const val SETTLE_STEPS = 24
    }
}
