package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.AgentActivityState.ACTIVE
import link.socket.phosphor.signal.AgentActivityState.COMPLETE
import link.socket.phosphor.signal.AgentActivityState.IDLE
import link.socket.phosphor.signal.AgentActivityState.PROCESSING
import link.socket.phosphor.signal.AgentActivityState.SPAWNING
import link.socket.phosphor.signal.CognitivePhase
import link.socket.phosphor.signal.CognitivePhase.EXECUTE
import link.socket.phosphor.signal.CognitivePhase.LEARN
import link.socket.phosphor.signal.CognitivePhase.LOOP
import link.socket.phosphor.signal.CognitivePhase.NONE
import link.socket.phosphor.signal.CognitivePhase.OBSERVE
import link.socket.phosphor.signal.CognitivePhase.PERCEIVE
import link.socket.phosphor.signal.CognitivePhase.PLAN
import link.socket.phosphor.signal.CognitivePhase.RECALL

/** PHO-77: the confirmed phase → look table and the switch's precedence. */
class CognitiveLookSwitchTest {
    private val switch = CognitiveLookSwitch()

    @Test
    fun `every phase and activity lands on the expected row`() {
        val activities: List<AgentActivityState?> = AgentActivityState.entries + null
        for (phase in CognitivePhase.entries) {
            for (activity in activities) {
                assertEquals(
                    expected(phase, activity),
                    switch.lookFor(phase, activity),
                    "($phase, $activity)",
                )
            }
        }
    }

    @Test
    fun `an activity-only row beats any phase`() {
        assertEquals(BASE, switch.lookFor(PLAN, SPAWNING))
        assertEquals(CognitiveLook(Look(LumosLook.BASE), LumosGlyph.CHECK), switch.lookFor(PLAN, COMPLETE))
    }

    @Test
    fun `a phase-only row holds across IDLE, ACTIVE and PROCESSING`() {
        val twins = CognitiveLook(Look(LumosLook.REASONING, "twins"))
        assertEquals(twins, switch.lookFor(PLAN, IDLE))
        assertEquals(twins, switch.lookFor(PLAN, ACTIVE))
        assertEquals(twins, switch.lookFor(PLAN, PROCESSING))
    }

    @Test
    fun `no agent and NONE with ACTIVE fall through to BASE`() {
        assertEquals(BASE, switch.lookFor(NONE, null))
        assertEquals(BASE, switch.lookFor(NONE, ACTIVE))
    }

    @Test
    fun `escalated wins over everything`() {
        assertSame(CognitiveLookSwitch.ESCALATED, switch.lookFor(EXECUTE, PROCESSING, escalated = true))
        assertSame(
            CognitiveLookSwitch.ESCALATED,
            switch.lookFor(PLAN, COMPLETE, escalated = true, unattended = true),
        )
        assertEquals(LumosLook.WAITING, CognitiveLookSwitch.ESCALATED.look.look)
        assertEquals(LumosGlyph.QUESTION, CognitiveLookSwitch.ESCALATED.glyph)
    }

    @Test
    fun `unattended replaces the look and keeps the table's glyph`() {
        val spiral = Look(LumosLook.BACKGROUND, "spiral")
        assertEquals(CognitiveLook(spiral, LumosGlyph.CHECK), switch.lookFor(NONE, COMPLETE, unattended = true))
        assertEquals(CognitiveLook(spiral), switch.lookFor(EXECUTE, PROCESSING, unattended = true))
        assertEquals(CognitiveLook(spiral), switch.lookFor(NONE, null, unattended = true))
    }

    @Test
    fun `lookFor returns held choices and allocates nothing`() {
        assertSame(switch.lookFor(EXECUTE, IDLE), switch.lookFor(EXECUTE, IDLE))
        assertSame(switch.lookFor(EXECUTE, IDLE, unattended = true), switch.lookFor(EXECUTE, IDLE, unattended = true))
    }

    @Test
    fun `a custom table can drop CHECK without touching DEFAULT`() {
        val quiet =
            CognitiveLookSwitch(
                table = CognitiveLookSwitch.DEFAULT + (CognitiveKey(activity = COMPLETE) to BASE),
            )

        assertNull(quiet.lookFor(PLAN, COMPLETE).glyph)
        assertEquals(LumosGlyph.CHECK, switch.lookFor(PLAN, COMPLETE).glyph, "the default switch still fires it")
        assertEquals(
            LumosGlyph.CHECK,
            CognitiveLookSwitch.DEFAULT.getValue(CognitiveKey(activity = COMPLETE)).glyph,
            "DEFAULT is untouched",
        )
    }

    @Test
    fun `a custom table can rekey escalation and the unattended look`() {
        val custom =
            CognitiveLookSwitch(
                table = emptyMap(),
                whenEscalated = CognitiveLook(Look(LumosLook.RETRYING)),
                whenUnattended = Look(LumosLook.COMPACTING, "squeeze"),
            )

        assertEquals(BASE, custom.lookFor(EXECUTE, PROCESSING), "an empty table is all BASE")
        assertEquals(CognitiveLook(Look(LumosLook.RETRYING)), custom.lookFor(NONE, null, escalated = true))
        assertEquals(
            CognitiveLook(Look(LumosLook.COMPACTING, "squeeze")),
            custom.lookFor(EXECUTE, PROCESSING, unattended = true),
        )
    }

    @Test
    fun `the most specific key wins`() {
        val table =
            mapOf(
                CognitiveKey() to CognitiveLook(Look(LumosLook.WAITING)),
                CognitiveKey(phase = EXECUTE) to CognitiveLook(Look(LumosLook.WORKING)),
                CognitiveKey(activity = IDLE) to CognitiveLook(Look(LumosLook.COMPACTING)),
                CognitiveKey(EXECUTE, IDLE) to CognitiveLook(Look(LumosLook.SEARCHING)),
            )
        val custom = CognitiveLookSwitch(table)

        assertEquals(LumosLook.SEARCHING, custom.lookFor(EXECUTE, IDLE).look.look, "(phase, activity)")
        assertEquals(LumosLook.COMPACTING, custom.lookFor(PLAN, IDLE).look.look, "(null, activity)")
        assertEquals(LumosLook.WORKING, custom.lookFor(EXECUTE, ACTIVE).look.look, "(phase, null)")
        assertEquals(LumosLook.WAITING, custom.lookFor(PLAN, ACTIVE).look.look, "(null, null)")
        assertEquals(LumosLook.WAITING, custom.lookFor(NONE, null).look.look, "no agent")
    }

    /** The confirmed table, written out longhand. */
    private fun expected(
        phase: CognitivePhase,
        activity: AgentActivityState?,
    ): CognitiveLook =
        when (activity) {
            SPAWNING -> BASE
            COMPLETE -> CognitiveLook(Look(LumosLook.BASE), LumosGlyph.CHECK)
            else ->
                when (phase) {
                    PERCEIVE -> CognitiveLook(Look(LumosLook.SEARCHING))
                    RECALL -> CognitiveLook(Look(LumosLook.REASONING))
                    OBSERVE -> CognitiveLook(Look(LumosLook.SEARCHING, "lighthouse"))
                    PLAN -> CognitiveLook(Look(LumosLook.REASONING, "twins"))
                    EXECUTE -> CognitiveLook(Look(LumosLook.WORKING))
                    LEARN -> CognitiveLook(Look(LumosLook.COMPACTING, "fuse"))
                    LOOP -> BASE
                    NONE ->
                        when (activity) {
                            PROCESSING -> CognitiveLook(Look(LumosLook.WORKING, "gyro"))
                            IDLE, ACTIVE, null -> BASE
                            SPAWNING, COMPLETE -> error("handled above")
                        }
                }
        }

    private companion object {
        val BASE = CognitiveLook(Look(LumosLook.BASE))
    }
}
