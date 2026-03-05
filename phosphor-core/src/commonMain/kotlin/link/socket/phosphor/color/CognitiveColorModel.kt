package link.socket.phosphor.color

import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase

/**
 * Semantic states for agent-level color signaling.
 */
enum class AgentColorState {
    IDLE,
    ACTIVE,
    ESCALATING,
    ERROR,
}

/**
 * Semantic states for flow-connection coloring.
 */
enum class FlowColorState {
    DORMANT,
    ACTIVATING,
    TRANSMITTING,
    RECEIVED,
}

/**
 * Semantic particle classes for terminal renderers.
 */
enum class ParticleColorKind {
    MOTE,
    SPARK,
    TRAIL,
    RIPPLE,
}

/**
 * Platform-neutral semantic color definitions for cognitive visualization.
 */
object CognitiveColorModel {
    private fun fromAnsi(index: Int): NeutralColor = AnsiColorAdapter.ansi256ToNeutral(index)

    private fun phaseRamp(stops: List<Int>): ColorRamp = ColorRamp(stops = stops.map(::fromAnsi))

    val phaseRamps: Map<CognitivePhase, ColorRamp> =
        mapOf(
            CognitivePhase.PERCEIVE to phaseRamp(listOf(17, 18, 24, 31, 38, 74, 110, 117, 153, 189, 231)),
            CognitivePhase.RECALL to phaseRamp(listOf(52, 94, 130, 136, 172, 178, 214, 220, 221)),
            CognitivePhase.PLAN to phaseRamp(listOf(23, 29, 30, 36, 37, 43, 79, 115, 159)),
            CognitivePhase.EXECUTE to phaseRamp(listOf(52, 88, 124, 160, 196, 202, 208, 214, 220, 226, 231)),
            CognitivePhase.EVALUATE to phaseRamp(listOf(53, 54, 91, 97, 134, 140, 141, 183, 189)),
            CognitivePhase.LOOP to phaseRamp(listOf(232, 236, 240, 244, 248, 252, 255)),
            CognitivePhase.NONE to phaseRamp(listOf(232, 236, 240, 244, 248, 252, 255)),
        )

    /**
     * Representative phase hues sampled from each phase ramp midpoint.
     */
    val phaseColors: Map<CognitivePhase, NeutralColor> =
        phaseRamps.mapValues { (_, ramp) -> ramp.sample(0.5f) }

    val agentStateColors: Map<AgentColorState, NeutralColor> =
        mapOf(
            AgentColorState.IDLE to fromAnsi(240),
            AgentColorState.ACTIVE to fromAnsi(226),
            AgentColorState.ESCALATING to fromAnsi(208),
            AgentColorState.ERROR to fromAnsi(196),
        )

    val agentActivityColors: Map<AgentActivityState, NeutralColor> =
        mapOf(
            AgentActivityState.SPAWNING to agentStateColors.getValue(AgentColorState.IDLE),
            AgentActivityState.IDLE to agentStateColors.getValue(AgentColorState.IDLE),
            AgentActivityState.ACTIVE to agentStateColors.getValue(AgentColorState.ACTIVE),
            AgentActivityState.PROCESSING to agentStateColors.getValue(AgentColorState.ACTIVE),
            AgentActivityState.COMPLETE to fromAnsi(82),
        )

    val flowStateColors: Map<FlowColorState, NeutralColor> =
        mapOf(
            FlowColorState.DORMANT to fromAnsi(240),
            FlowColorState.ACTIVATING to fromAnsi(226),
            FlowColorState.TRANSMITTING to fromAnsi(45),
            FlowColorState.RECEIVED to fromAnsi(46),
        )

    val particleColors: Map<ParticleColorKind, NeutralColor> =
        mapOf(
            ParticleColorKind.MOTE to fromAnsi(240),
            ParticleColorKind.SPARK to fromAnsi(226),
            ParticleColorKind.TRAIL to fromAnsi(45),
            ParticleColorKind.RIPPLE to fromAnsi(51),
        )

    val confidenceRamp: ColorRamp =
        ColorRamp(
            stops =
                listOf(
                    fromAnsi(239),
                    fromAnsi(242),
                    fromAnsi(245),
                    fromAnsi(81),
                    fromAnsi(51),
                    fromAnsi(45),
                    fromAnsi(82),
                ),
        )

    val flowIntensityRamp: ColorRamp =
        ColorRamp(
            stops =
                listOf(
                    fromAnsi(236),
                    fromAnsi(240),
                    fromAnsi(31),
                    fromAnsi(45),
                    fromAnsi(51),
                ),
        )

    val reasoningRoleColor: NeutralColor = fromAnsi(213)
    val codegenRoleColor: NeutralColor = fromAnsi(45)
    val coordinatorRoleColor: NeutralColor = fromAnsi(226)

    fun phaseRampFor(phase: CognitivePhase): ColorRamp = phaseRamps.getValue(phase)

    fun phaseColorFor(phase: CognitivePhase): NeutralColor = phaseColors.getValue(phase)

    fun roleColorFor(role: String): NeutralColor {
        return when (role.lowercase()) {
            "reasoning", "spark" -> reasoningRoleColor
            "codegen", "code", "jazz" -> codegenRoleColor
            "coordinator" -> coordinatorRoleColor
            else -> agentStateColors.getValue(AgentColorState.IDLE)
        }
    }
}
