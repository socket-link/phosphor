package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.CognitiveLookSwitch
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.AgentDescriptor
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.CognitivePhase

/**
 * PHO-78: the generator drives the body. The signal setters write through to one agent in the
 * scene and install a look switch on first use; [SignalGenerator.setLook] blends by hand; a
 * generator that never calls them is the generator it was.
 */
class SignalGeneratorTest {
    @Test
    fun `setPhase turns the next frame's look to the phase's`() {
        val generator = SignalGenerator(scene(), fps = FPS)
        generator.nextFrame()

        generator.setPhase(CognitivePhase.PLAN)
        generator.nextFrame()

        assertEquals("thinking/reasoning-twins", generator.asFrameSource().lookKey)
    }

    @Test
    fun `setEscalated after a baseline wears waiting and fires the question`() {
        val generator = SignalGenerator(scene(), fps = FPS)
        // The first frame after the switch is installed is its baseline and fires nothing.
        generator.setEscalated(false)
        assertNull(generator.nextFrame().glyph)

        generator.setEscalated(true)
        val frame = generator.nextFrame()

        assertEquals("thinking/waiting", generator.asFrameSource().lookKey)
        assertEquals("QUESTION", frame.glyph?.glyphName)
    }

    @Test
    fun `setLook blends with no switch installed and the look holds`() {
        val generator = SignalGenerator(scene(), fps = FPS)
        val builder = (generator.asFrameSource() as LiveFrameSource).builder
        generator.nextFrame()

        generator.setLook(Look(LumosLook.WORKING))
        generator.nextFrame()

        assertNull(builder.lookSwitch)
        assertTrue(builder.isLookBlending)
        assertEquals("thinking/working", generator.asFrameSource().lookKey)

        repeat(FPS) { generator.nextFrame() }
        assertFalse(builder.isLookBlending)
        assertEquals(Look(LumosLook.WORKING), builder.look)
    }

    @Test
    fun `a generator that never calls a setter produces the frames it always did`() {
        val today = LiveFrameSource(scene(), LumosRenderConfig())
        val generator = SignalGenerator(scene(), fps = FPS)
        val withSwitch = SignalGenerator(scene(), fps = FPS, lookSwitch = CognitiveLookSwitch())

        repeat(FRAMES) {
            val expected = today.next(1f / FPS)
            assertEquals(expected, generator.nextFrame())
            assertEquals(expected, withSwitch.nextFrame())
        }
        assertNull((withSwitch.asFrameSource() as LiveFrameSource).builder.lookSwitch)
    }

    @Test
    fun `the setters write to the scene's first agent by id, the one the builder reads`() {
        val scene =
            scene().copy(
                agents =
                    listOf(
                        AgentDescriptor(id = "b", position = Vector2.ZERO),
                        AgentDescriptor(id = "a", position = Vector2.ZERO),
                    ),
            )
        val generator = SignalGenerator(scene, fps = FPS)
        generator.setEscalated(false)
        generator.nextFrame()

        generator.setEscalated(true)
        generator.nextFrame()

        assertEquals("thinking/waiting", generator.asFrameSource().lookKey)
        val agents = (generator.asFrameSource() as LiveFrameSource).runtime.agents
        assertEquals(2, agents.agentCount)
        assertTrue(agents.getAgent("a")!!.escalated)
    }

    @Test
    fun `an agentId the scene lacks is added, and with none named the generator adds its own`() {
        val named = SignalGenerator(scene(), LumosRenderConfig(agentId = "run-7"), fps = FPS)
        named.setPhase(CognitivePhase.EXECUTE)
        named.nextFrame()
        val namedAgents = (named.asFrameSource() as LiveFrameSource).runtime.agents
        assertEquals(CognitivePhase.EXECUTE, namedAgents.getAgent("run-7")?.cognitivePhase)
        assertEquals("thinking/working", named.asFrameSource().lookKey)

        val unnamed = SignalGenerator(scene(), fps = FPS)
        unnamed.setPhase(CognitivePhase.EXECUTE)
        val agents = (unnamed.asFrameSource() as LiveFrameSource).runtime.agents
        assertEquals(listOf(SignalGenerator.AGENT_ID), agents.allAgents.map { it.id })
    }

    @Test
    fun `a setter takes effect on the next step, not the frame already built`() {
        val generator = SignalGenerator(scene(), fps = FPS)
        val reference = SignalGenerator(scene(), fps = FPS)
        repeat(3) { assertEquals(reference.nextFrame(), generator.nextFrame()) }

        generator.setPhase(CognitivePhase.EXECUTE)
        assertNotEquals(reference.nextFrame(), generator.nextFrame())
    }

    private fun scene(): SceneConfiguration =
        SceneConfiguration(
            width = 8,
            height = 8,
            enableWaveform = false,
            enableParticles = false,
            enableFlow = false,
            enableEmitters = false,
            enableCamera = false,
            enableAtmosphere = true,
            // The preset itself, not a copy at a lower resolution: lookKey names a registered preset.
            initialAtmosphere = AtmospherePresets.THINKING,
        )

    private companion object {
        const val FPS = 24
        const val FRAMES = 12
    }
}
