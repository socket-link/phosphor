package link.socket.phosphor.lumos

/**
 * Identifies a target surface for [LumosRenderer] output. Sibling to
 * [link.socket.phosphor.renderer.RenderTarget]; the two enums are
 * intentionally separate because the Lumos voxel rendering pipeline and
 * the Phosphor cell-based pipeline produce different frame shapes.
 */
enum class LumosRenderTarget {
    /** Native 3D voxel rendering via a host-provided 3D library (Three.js, Compose Canvas + custom voxel impl, etc.). */
    VOXEL_NATIVE,

    /** ANSI-projected voxel orb for terminal output. Reserved for Wave 2; declared now so consumers can match on the enum exhaustively. */
    VOXEL_TERMINAL,
}
