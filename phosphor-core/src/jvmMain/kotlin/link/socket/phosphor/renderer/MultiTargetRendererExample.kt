package link.socket.phosphor.renderer

/**
 * Example output showing one simulation frame rendered to terminal and compose targets.
 */
data class MultiTargetRenderOutput(
    val terminal: TerminalRenderFrame,
    val compose: ComposeRenderFrame,
)

object MultiTargetRendererExample {
    fun renderSimultaneously(
        frame: SimulationFrame,
        terminalRenderer: TerminalRenderer = TerminalRenderer(),
        composeRenderer: ComposeRenderer = ComposeRenderer(),
    ): MultiTargetRenderOutput =
        MultiTargetRenderOutput(
            terminal = terminalRenderer.render(frame),
            compose = composeRenderer.render(frame),
        )
}
