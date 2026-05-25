package link.socket.phosphor.lumos.cli.renderer

/**
 * Strategy for clearing the orb region between frames.
 *
 * `FULL` rewrites every cell of the orb region every frame, which is simpler
 * to reason about and tolerant of external programs scribbling over the region
 * between renders. `DIFFERENTIAL` compares against the previous frame and only
 * emits cells that changed, which is dramatically cheaper for steady-state
 * frames but assumes nothing else writes into the orb region.
 */
enum class ClearMode {
    FULL,
    DIFFERENTIAL,
}
