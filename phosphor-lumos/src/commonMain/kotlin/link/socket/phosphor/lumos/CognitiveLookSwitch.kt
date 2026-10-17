package link.socket.phosphor.lumos

import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase

/**
 * A key in a [CognitiveLookSwitch] table: a cognitive phase, an agent activity,
 * or both.
 *
 * `null` matches any value in that slot. [CognitivePhase.NONE] means "no
 * phase", not "any": a row keyed `(NONE, PROCESSING)` matches an agent that is
 * processing with no phase, and nothing else.
 *
 * @property phase The phase the row is for, or null for every phase.
 * @property activity The activity the row is for, or null for every activity,
 *  including the scene that has no agent at all.
 */
data class CognitiveKey(
    val phase: CognitivePhase? = null,
    val activity: AgentActivityState? = null,
)

/**
 * What a [CognitiveLookSwitch] picks: the [Look] to wear and, optionally, a
 * glyph to fire once when the choice arrives.
 *
 * @property look The idiom the orb should wear while the signal holds.
 * @property glyph A glyph for the moment the signal arrives, or null for none.
 *  `VoxelFrameBuilder` fires it once, when the choice changes to this one; a
 *  signal that holds does not keep firing it.
 */
data class CognitiveLook(
    val look: Look,
    val glyph: LumosGlyph? = null,
)

/**
 * Routes cognitive signal to a look: PROPEL phase and agent activity in, a
 * [CognitiveLook] out.
 *
 * A switch: it decides, it does not animate. `VoxelFrameBuilder.lookSwitch`
 * reads the scene's signal each frame, asks here, and blends the orb to the
 * answer; see [link.socket.phosphor.lumos.VoxelFrameBuilder.lookTo].
 *
 * The table is data. [DEFAULT] is the confirmed phase → look table (PHO-60,
 * decided 2026-10-17), and overriding it means passing another map: a host that
 * already fires its own CHECK on task completion passes [DEFAULT] with the
 * `COMPLETE` row's glyph dropped. [table] is resolved into a dense array at
 * construction, so [lookFor] is an index lookup and allocates nothing.
 *
 * Precedence, highest first:
 *
 * 1. `escalated` → [whenEscalated], whatever the table says.
 * 2. `unattended` → the look becomes [whenUnattended], keeping the glyph the
 *    table picked. A background run still shows its CHECK.
 * 3. The table, most specific key first: `(phase, activity)`, then
 *    `(null, activity)`, then `(phase, null)`, then `(null, null)`, then
 *    [Look] of [LumosLook.BASE] with no glyph.
 *
 * So an activity-only row is a lifecycle override — `SPAWNING` and `COMPLETE`
 * beat any phase — and a phase-only row holds across `IDLE`, `ACTIVE` and
 * `PROCESSING`.
 *
 * @property table The rows; see [CognitiveKey] for how they match.
 * @property whenEscalated The choice while an agent is escalated.
 * @property whenUnattended The look an unattended surface wears.
 */
class CognitiveLookSwitch(
    val table: Map<CognitiveKey, CognitiveLook> = DEFAULT,
    val whenEscalated: CognitiveLook = ESCALATED,
    val whenUnattended: Look = UNATTENDED,
) {
    // One slot per (phase, activity-or-none), resolved through the precedence once.
    private val attended: Array<CognitiveLook>
    private val unattended: Array<CognitiveLook>

    init {
        val phases = CognitivePhase.entries
        val activities = AgentActivityState.entries
        attended =
            Array(phases.size * SLOTS) { slot ->
                val phase = phases[slot / SLOTS]
                val activity = activities.getOrNull(slot % SLOTS)
                resolve(phase, activity)
            }
        unattended =
            Array(attended.size) { slot ->
                val picked = attended[slot]
                if (picked.look == whenUnattended) picked else CognitiveLook(whenUnattended, picked.glyph)
            }
    }

    private fun resolve(
        phase: CognitivePhase,
        activity: AgentActivityState?,
    ): CognitiveLook =
        table[CognitiveKey(phase, activity)]
            ?: table[CognitiveKey(null, activity)]
            ?: table[CognitiveKey(phase, null)]
            ?: table[CognitiveKey(null, null)]
            ?: FALLBACK

    /**
     * The choice for this signal.
     *
     * @param phase The agent's phase, or the scene's dominant one; [CognitivePhase.NONE]
     *  when there is none.
     * @param activity The agent's activity, or null when the scene has no agent.
     * @param escalated Whether the agent is holding for a human; see
     *  [link.socket.phosphor.signal.AgentVisualState.escalated].
     * @param unattended Whether the surface is one nobody is watching, such as
     *  a "running now" list; a mount-time fact, `LumosRenderConfig.unattended`.
     */
    fun lookFor(
        phase: CognitivePhase,
        activity: AgentActivityState?,
        escalated: Boolean = false,
        unattended: Boolean = false,
    ): CognitiveLook {
        if (escalated) return whenEscalated
        val slot = phase.ordinal * SLOTS + (activity?.ordinal ?: NO_AGENT)
        return if (unattended) this.unattended[slot] else attended[slot]
    }

    companion object {
        // Activity slots per phase: every AgentActivityState, and one more for "no agent".
        private val NO_AGENT: Int = AgentActivityState.entries.size
        private val SLOTS: Int = NO_AGENT + 1

        /** What the table falls through to: [LumosLook.BASE], no glyph. */
        private val FALLBACK: CognitiveLook = CognitiveLook(Look(LumosLook.BASE))

        /** The choice while escalated: [LumosLook.WAITING], with [LumosGlyph.QUESTION] on arrival. */
        val ESCALATED: CognitiveLook = CognitiveLook(Look(LumosLook.WAITING), LumosGlyph.QUESTION)

        /** The look an unattended surface wears: [LumosLook.BACKGROUND] · `spiral`. */
        val UNATTENDED: Look = Look(LumosLook.BACKGROUND, "spiral")

        /**
         * The confirmed phase → look table.
         *
         * | Signal | Look | Glyph |
         * |---|---|---|
         * | PERCEIVE | SEARCHING | |
         * | RECALL | REASONING | |
         * | OBSERVE | SEARCHING · lighthouse | |
         * | PLAN | REASONING · twins | |
         * | EXECUTE | WORKING | |
         * | LEARN | COMPACTING · fuse | |
         * | LOOP | BASE | |
         * | NONE + IDLE | BASE | |
         * | NONE + PROCESSING | WORKING · gyro | |
         * | any + SPAWNING | BASE | |
         * | any + COMPLETE | BASE | CHECK |
         *
         * `NONE + ACTIVE`, and a scene with no agent, fall through to BASE.
         */
        val DEFAULT: Map<CognitiveKey, CognitiveLook> =
            mapOf(
                CognitiveKey(phase = CognitivePhase.PERCEIVE) to CognitiveLook(Look(LumosLook.SEARCHING)),
                CognitiveKey(phase = CognitivePhase.RECALL) to CognitiveLook(Look(LumosLook.REASONING)),
                CognitiveKey(phase = CognitivePhase.OBSERVE) to
                    CognitiveLook(Look(LumosLook.SEARCHING, "lighthouse")),
                CognitiveKey(phase = CognitivePhase.PLAN) to CognitiveLook(Look(LumosLook.REASONING, "twins")),
                CognitiveKey(phase = CognitivePhase.EXECUTE) to CognitiveLook(Look(LumosLook.WORKING)),
                CognitiveKey(phase = CognitivePhase.LEARN) to CognitiveLook(Look(LumosLook.COMPACTING, "fuse")),
                CognitiveKey(phase = CognitivePhase.LOOP) to CognitiveLook(Look(LumosLook.BASE)),
                CognitiveKey(CognitivePhase.NONE, AgentActivityState.IDLE) to CognitiveLook(Look(LumosLook.BASE)),
                CognitiveKey(CognitivePhase.NONE, AgentActivityState.PROCESSING) to
                    CognitiveLook(Look(LumosLook.WORKING, "gyro")),
                CognitiveKey(activity = AgentActivityState.SPAWNING) to CognitiveLook(Look(LumosLook.BASE)),
                CognitiveKey(activity = AgentActivityState.COMPLETE) to
                    CognitiveLook(Look(LumosLook.BASE), LumosGlyph.CHECK),
            )
    }
}
