package link.socket.phosphor.signal

/**
 * Activity states for agents in the visualization.
 */
enum class AgentActivityState {
    /** Agent is coalescing from particles */
    SPAWNING,

    /** Agent is hollow/inactive (◎) */
    IDLE,

    /** Agent is filled/active (◉) */
    ACTIVE,

    /** Agent is processing with shimmer (◉ animated) */
    PROCESSING,

    /** Agent completed work (◎ with checkmark) */
    COMPLETE,
}
