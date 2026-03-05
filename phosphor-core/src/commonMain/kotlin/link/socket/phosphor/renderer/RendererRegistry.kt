package link.socket.phosphor.renderer

/**
 * Registry for renderer instances with hot-swap support.
 */
class RendererRegistry {
    private val renderers = linkedMapOf<RenderTarget, PhosphorRenderer<*>>()
    private var currentTarget: RenderTarget? = null

    fun register(
        renderer: PhosphorRenderer<*>,
        activate: Boolean = currentTarget == null,
    ): RendererRegistry {
        renderers[renderer.target] = renderer

        if (activate || currentTarget == null) {
            currentTarget = renderer.target
        }

        return this
    }

    fun unregister(target: RenderTarget): PhosphorRenderer<*>? {
        val removed = renderers.remove(target)

        if (removed != null && currentTarget == target) {
            currentTarget = renderers.keys.firstOrNull()
        }

        return removed
    }

    fun clear() {
        renderers.clear()
        currentTarget = null
    }

    fun activate(target: RenderTarget) {
        require(renderers.containsKey(target)) {
            "No renderer registered for target $target"
        }
        currentTarget = target
    }

    fun isRegistered(target: RenderTarget): Boolean = renderers.containsKey(target)

    fun activeTarget(): RenderTarget? = currentTarget

    fun activeRenderer(): PhosphorRenderer<*>? = currentTarget?.let { target -> renderers[target] }

    fun availableTargets(): Set<RenderTarget> = renderers.keys.toSet()

    @Suppress("UNCHECKED_CAST")
    fun <T> render(frame: SimulationFrame): T {
        val target = currentTarget ?: error("No active renderer target is set")
        val renderer = renderers[target] ?: error("No renderer registered for target $target")
        return (renderer as PhosphorRenderer<T>).render(frame)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> render(
        target: RenderTarget,
        frame: SimulationFrame,
    ): T {
        val renderer = renderers[target] ?: error("No renderer registered for target $target")
        return (renderer as PhosphorRenderer<T>).render(frame)
    }

    @Suppress("UNCHECKED_CAST")
    fun renderAll(frame: SimulationFrame): Map<RenderTarget, Any> =
        renderers.mapValues { (_, renderer) ->
            (renderer as PhosphorRenderer<Any>).render(frame)
        }
}
