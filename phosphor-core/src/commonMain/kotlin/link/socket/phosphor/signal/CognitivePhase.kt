package link.socket.phosphor.signal

/**
 * Cognitive phases from the PROPEL loop.
 * Each phase maps to a distinct visual choreography.
 *
 * Canonical PROPEL order: PERCEIVE → RECALL → OBSERVE → PLAN → EXECUTE → LEARN.
 * LOOP and NONE are Phosphor-internal phases that do not map to PROPEL directly;
 * they serve the cell-based renderer's internal scheduling.
 *
 * Note: this enum is distinct from `link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase`
 * in the AMPERE project. The two enums coexist by design and are kept in sync at the vocabulary level.
 */
enum class CognitivePhase {
    /** Gathering sensory input — particles drift inward */
    PERCEIVE,

    /** Memory activation — warm embers brightening */
    RECALL,

    /** Pattern recognition — comparing input against retrieved context */
    OBSERVE,

    /** Strategy formation — tentative structures testing formations */
    PLAN,

    /** Committed action — discharge/acceleration */
    EXECUTE,

    /** Reflection — afterglow, particles slow and persist */
    LEARN,

    /** Cycle complete — brief stillness before next iteration */
    LOOP,

    /** No active cognition */
    NONE,
}
