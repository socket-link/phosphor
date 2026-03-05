package link.socket.phosphor.renderer

/**
 * Platform renderer contract for a single simulation frame.
 */
interface PhosphorRenderer<out T> {
    val target: RenderTarget
    val preferredFps: Int

    fun render(frame: SimulationFrame): T
}
