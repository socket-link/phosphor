package link.socket.phosphor.signal

import kotlinx.serialization.Serializable

/**
 * Scene-global pattern families used by [AtmosphereState].
 *
 * Patterns describe spatial variation in the renderer's atmosphere. Surface
 * adapters can interpret each family with renderer-specific math while keeping
 * a stable signal contract in common code.
 */
@Serializable
enum class AtmospherePattern {
    /** Horizontal-banded sine pattern around the vertical axis. */
    LONGITUDE,

    /** Vertical-banded sine pattern around the polar axis. */
    LATITUDE,

    /** Combined theta and phi sweep. */
    SPIRAL,

    /** Linear sweep along the Y axis. */
    SCAN,

    /** Three-axis noise sum. */
    PLASMA,

    /** Radial concentric rings from center. */
    PULSE,

    /** No pattern variation. */
    SOLID,
}
