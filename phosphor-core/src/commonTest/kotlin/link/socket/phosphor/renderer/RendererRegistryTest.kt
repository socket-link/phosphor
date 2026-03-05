package link.socket.phosphor.renderer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RendererRegistryTest {
    @Test
    fun `register sets active target and supports hot swap`() {
        val registry = RendererRegistry()

        registry.register(FakeRenderer(RenderTarget.TERMINAL, "terminal"))
        registry.register(FakeRenderer(RenderTarget.COMPOSE, "compose"), activate = false)

        assertEquals(RenderTarget.TERMINAL, registry.activeTarget())

        registry.activate(RenderTarget.COMPOSE)

        assertEquals(RenderTarget.COMPOSE, registry.activeTarget())
    }

    @Test
    fun `render uses currently active renderer`() {
        val registry = RendererRegistry()
        registry.register(FakeRenderer(RenderTarget.TERMINAL, "terminal"))
        registry.register(FakeRenderer(RenderTarget.COMPOSE, "compose"), activate = false)

        val frame = sampleFrame()

        val defaultOutput: String = registry.render(frame)
        assertEquals("terminal:3", defaultOutput)

        registry.activate(RenderTarget.COMPOSE)

        val swappedOutput: String = registry.render(frame)
        assertEquals("compose:3", swappedOutput)
    }

    @Test
    fun `renderAll renders to every registered target`() {
        val registry = RendererRegistry()
        registry.register(FakeRenderer(RenderTarget.TERMINAL, "terminal"))
        registry.register(FakeRenderer(RenderTarget.COMPOSE, "compose"), activate = false)

        val outputs = registry.renderAll(sampleFrame())

        assertEquals(2, outputs.size)
        assertEquals("terminal:3", outputs[RenderTarget.TERMINAL])
        assertEquals("compose:3", outputs[RenderTarget.COMPOSE])
    }

    @Test
    fun `unregister drops target and reassigns active renderer`() {
        val registry = RendererRegistry()
        registry.register(FakeRenderer(RenderTarget.TERMINAL, "terminal"))
        registry.register(FakeRenderer(RenderTarget.COMPOSE, "compose"), activate = false)

        val removed = registry.unregister(RenderTarget.TERMINAL)

        assertNotNull(removed)
        assertFalse(registry.isRegistered(RenderTarget.TERMINAL))
        assertTrue(registry.isRegistered(RenderTarget.COMPOSE))
        assertEquals(RenderTarget.COMPOSE, registry.activeTarget())
    }

    private fun sampleFrame(): SimulationFrame =
        SimulationFrame(
            tick = 3L,
            timestampEpochMillis = 50L,
            width = 1,
            height = 1,
            cells = listOf(FrameCell(char = 'x', fgColor = 10)),
        )

    private class FakeRenderer(
        override val target: RenderTarget,
        private val name: String,
    ) : PhosphorRenderer<String> {
        override val preferredFps: Int = 60

        override fun render(frame: SimulationFrame): String = "$name:${frame.tick}"
    }
}
