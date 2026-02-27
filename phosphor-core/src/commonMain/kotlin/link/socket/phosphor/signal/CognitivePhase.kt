package link.socket.phosphor.signal

/**
 * Cognitive phases from the PROPEL loop.
 * Each phase maps to a distinct visual choreography.
 */
enum class CognitivePhase {
    /** Gathering sensory input — particles drift inward */
    PERCEIVE,

    /** Memory activation — warm embers brightening */
    RECALL,

    /** Strategy formation — tentative structures testing formations */
    PLAN,

    /** Committed action — discharge/acceleration */
    EXECUTE,

    /** Reflection — afterglow, particles slow and persist */
    EVALUATE,

    /** Cycle complete — brief stillness before next iteration */
    LOOP,

    /** No active cognition */
    NONE,
}
