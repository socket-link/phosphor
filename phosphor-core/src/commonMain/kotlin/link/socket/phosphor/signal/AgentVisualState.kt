package link.socket.phosphor.signal

import link.socket.phosphor.color.AgentColorState
import link.socket.phosphor.color.AnsiColorAdapter
import link.socket.phosphor.color.CognitiveColorModel
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.math.Vector3

/**
 * Visual representation of an agent in the TUI.
 *
 * Agents are the protagonists of the visualization - they illuminate when
 * active, dim when idle, and are connected by visible substrate flows.
 *
 * @property id Unique agent identifier
 * @property name Display name for the agent
 * @property role Agent's role (e.g., "reasoning", "codegen")
 * @property position Position in 2D space (preserved for 2D renderers)
 * @property position3D Full 3D position (x=horizontal, y=height/waveform, z=depth)
 * @property state Current activity state
 * @property statusText Status message displayed below agent
 * @property pulsePhase Phase for shimmer animation (0.0-1.0)
 */
data class AgentVisualState(
    val id: String,
    val name: String,
    val role: String,
    val position: Vector2,
    val position3D: Vector3 = Vector3(position.x, 0f, position.y),
    val state: AgentActivityState = AgentActivityState.IDLE,
    val statusText: String = "",
    val pulsePhase: Float = 0f,
    val cognitivePhase: CognitivePhase = CognitivePhase.NONE,
    val phaseProgress: Float = 0f,
) {
    /**
     * Create a copy with updated 2D position. The 3D position is updated
     * to keep X and Z in sync, preserving the current Y (height).
     */
    fun withPosition(newPosition: Vector2): AgentVisualState =
        copy(
            position = newPosition,
            position3D = Vector3(newPosition.x, position3D.y, newPosition.y),
        )

    /**
     * Create a copy with updated 3D position. The 2D position is
     * derived as the XZ projection.
     */
    fun withPosition3D(newPosition3D: Vector3): AgentVisualState =
        copy(
            position = Vector2(newPosition3D.x, newPosition3D.z),
            position3D = newPosition3D,
        )

    /**
     * Create a copy with updated state.
     */
    fun withState(newState: AgentActivityState): AgentVisualState = copy(state = newState)

    /**
     * Create a copy with updated status text.
     */
    fun withStatus(newStatus: String): AgentVisualState = copy(statusText = newStatus)

    /**
     * Create a copy with updated pulse phase.
     */
    fun withPulsePhase(phase: Float): AgentVisualState = copy(pulsePhase = phase % 1f)

    /**
     * Create a copy with updated cognitive phase and progress.
     */
    fun withCognitivePhase(
        phase: CognitivePhase,
        progress: Float = 0f,
    ): AgentVisualState = copy(cognitivePhase = phase, phaseProgress = progress.coerceIn(0f, 1f))

    /**
     * Get the primary glyph for this agent's current state.
     */
    fun getPrimaryGlyph(useUnicode: Boolean = true): Char {
        return AgentGlyphs.forState(state, useUnicode)
    }

    /**
     * Get the accent suffix (e.g., checkmark for COMPLETE).
     */
    fun getAccentSuffix(useUnicode: Boolean = true): String {
        return when (state) {
            AgentActivityState.COMPLETE -> if (useUnicode) " \u2713" else " [ok]"
            else -> ""
        }
    }
}

/**
 * Glyph definitions for agent visualization.
 */
object AgentGlyphs {
    // Unicode glyphs
    const val SPAWNING_UNICODE = '\u2591' // ░ (light shade)
    const val IDLE_UNICODE = '\u25CE' // ◎ (bullseye)
    const val ACTIVE_UNICODE = '\u25C9' // ◉ (fisheye)
    const val PROCESSING_UNICODE = '\u25C9' // ◉ (animated with shimmer)
    const val COMPLETE_UNICODE = '\u25CE' // ◎

    // ASCII fallbacks
    const val SPAWNING_ASCII = '#'
    const val IDLE_ASCII = 'o'
    const val ACTIVE_ASCII = '@'
    const val PROCESSING_ASCII = '@'
    const val COMPLETE_ASCII = 'o'

    // Spawning gradient glyphs for animation
    val SPAWNING_GRADIENT_UNICODE = charArrayOf('\u2591', '\u2592', '\u2593') // ░ ▒ ▓
    val SPAWNING_GRADIENT_ASCII = charArrayOf('.', 'o', '#')

    /**
     * Get the glyph for a given state.
     */
    fun forState(
        state: AgentActivityState,
        useUnicode: Boolean = true,
    ): Char {
        return if (useUnicode) {
            when (state) {
                AgentActivityState.SPAWNING -> SPAWNING_UNICODE
                AgentActivityState.IDLE -> IDLE_UNICODE
                AgentActivityState.ACTIVE -> ACTIVE_UNICODE
                AgentActivityState.PROCESSING -> PROCESSING_UNICODE
                AgentActivityState.COMPLETE -> COMPLETE_UNICODE
            }
        } else {
            when (state) {
                AgentActivityState.SPAWNING -> SPAWNING_ASCII
                AgentActivityState.IDLE -> IDLE_ASCII
                AgentActivityState.ACTIVE -> ACTIVE_ASCII
                AgentActivityState.PROCESSING -> PROCESSING_ASCII
                AgentActivityState.COMPLETE -> COMPLETE_ASCII
            }
        }
    }

    /**
     * Get spawning gradient glyph based on progress (0.0-1.0).
     */
    fun spawningGlyph(
        progress: Float,
        useUnicode: Boolean = true,
    ): Char {
        val glyphs = if (useUnicode) SPAWNING_GRADIENT_UNICODE else SPAWNING_GRADIENT_ASCII
        val index = (progress * (glyphs.size - 1)).toInt().coerceIn(0, glyphs.lastIndex)
        return glyphs[index]
    }
}

/**
 * Color scheme for agent rendering.
 */
object AgentColors {
    private val ansi = AnsiColorAdapter.DEFAULT

    // ANSI 256-color codes
    @Deprecated(
        message = "Use CognitiveColorModel.agentStateColors + AnsiColorAdapter instead.",
        replaceWith =
            ReplaceWith(
                "AnsiColorAdapter.DEFAULT.foreground(" +
                    "CognitiveColorModel.agentStateColors.getValue(AgentColorState.IDLE))",
            ),
    )
    const val IDLE = "\u001B[38;5;240m" // Gray

    @Deprecated(
        message = "Use CognitiveColorModel.agentStateColors + AnsiColorAdapter instead.",
        replaceWith =
            ReplaceWith(
                "AnsiColorAdapter.DEFAULT.foreground(" +
                    "CognitiveColorModel.agentStateColors.getValue(AgentColorState.ACTIVE))",
            ),
    )
    const val ACTIVE = "\u001B[38;5;226m" // Gold/Yellow

    @Deprecated(
        message = "Use CognitiveColorModel.agentActivityColors + AnsiColorAdapter instead.",
        replaceWith =
            ReplaceWith(
                "AnsiColorAdapter.DEFAULT.foreground(" +
                    "CognitiveColorModel.agentActivityColors.getValue(AgentActivityState.PROCESSING))",
            ),
    )
    const val PROCESSING = "\u001B[38;5;226m" // Gold with shimmer

    @Deprecated(
        message = "Use CognitiveColorModel.agentActivityColors + AnsiColorAdapter instead.",
        replaceWith =
            ReplaceWith(
                "AnsiColorAdapter.DEFAULT.foreground(" +
                    "CognitiveColorModel.agentActivityColors.getValue(AgentActivityState.SPAWNING))",
            ),
    )
    const val SPAWNING = "\u001B[38;5;240m" // Gray

    @Deprecated(
        message = "Use CognitiveColorModel.agentActivityColors + AnsiColorAdapter instead.",
        replaceWith =
            ReplaceWith(
                "AnsiColorAdapter.DEFAULT.foreground(" +
                    "CognitiveColorModel.agentActivityColors.getValue(AgentActivityState.COMPLETE))",
            ),
    )
    const val COMPLETE = "\u001B[38;5;82m" // Green

    @Deprecated(
        message = "Use AnsiColorAdapter.RESET instead.",
        replaceWith = ReplaceWith("AnsiColorAdapter.RESET"),
    )
    const val RESET = "\u001B[0m"

    // Role-based colors
    @Deprecated(
        message = "Use CognitiveColorModel.roleColorFor + AnsiColorAdapter instead.",
        replaceWith = ReplaceWith("AnsiColorAdapter.DEFAULT.foreground(CognitiveColorModel.reasoningRoleColor)"),
    )
    const val REASONING = "\u001B[38;5;213m" // Pink/Magenta (Spark)

    @Deprecated(
        message = "Use CognitiveColorModel.roleColorFor + AnsiColorAdapter instead.",
        replaceWith = ReplaceWith("AnsiColorAdapter.DEFAULT.foreground(CognitiveColorModel.codegenRoleColor)"),
    )
    const val CODEGEN = "\u001B[38;5;45m" // Cyan (Jazz)

    @Deprecated(
        message = "Use CognitiveColorModel.roleColorFor + AnsiColorAdapter instead.",
        replaceWith = ReplaceWith("AnsiColorAdapter.DEFAULT.foreground(CognitiveColorModel.coordinatorRoleColor)"),
    )
    const val COORDINATOR = "\u001B[38;5;226m" // Gold

    /**
     * Get color for agent state.
     */
    fun forState(state: AgentActivityState): String {
        val neutral = CognitiveColorModel.agentActivityColors.getValue(state)
        return ansi.foreground(neutral)
    }

    /**
     * Get color for agent role.
     */
    fun forRole(role: String): String {
        return when (role.lowercase()) {
            "reasoning", "spark" -> ansi.foreground(CognitiveColorModel.reasoningRoleColor)
            "codegen", "code", "jazz" -> ansi.foreground(CognitiveColorModel.codegenRoleColor)
            "coordinator" -> ansi.foreground(CognitiveColorModel.coordinatorRoleColor)
            else -> ansi.foreground(CognitiveColorModel.agentStateColors.getValue(AgentColorState.IDLE))
        }
    }
}
