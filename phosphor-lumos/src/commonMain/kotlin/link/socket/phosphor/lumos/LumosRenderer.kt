package link.socket.phosphor.lumos

/**
 * Renders a [VoxelFrame] into a target-specific output type [T].
 *
 * Sibling to [link.socket.phosphor.renderer.PhosphorRenderer]; both abstractions
 * coexist because the Lumos voxel pipeline and the Phosphor cell-based pipeline
 * produce different geometric primitives. Consumers binding to Lumos do not need
 * to interact with [link.socket.phosphor.renderer.PhosphorRenderer], and vice versa.
 *
 * Implementations are framework-free in the sense that they should not depend on
 * a specific UI toolkit. Concrete output types (Compose draw commands, JSON for
 * web bridge, ANSI text in Wave 2) live in consuming modules or platforms.
 */
interface LumosRenderer<out T> {
    val target: LumosRenderTarget
    val preferredFps: Int

    fun render(frame: VoxelFrame): T
}
