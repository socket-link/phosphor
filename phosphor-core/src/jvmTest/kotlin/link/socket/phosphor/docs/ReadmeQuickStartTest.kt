package link.socket.phosphor.docs

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import link.socket.phosphor.choreography.AgentLayer
import link.socket.phosphor.choreography.CognitiveChoreographer
import link.socket.phosphor.field.ParticleSystem
import link.socket.phosphor.field.SubstrateAnimator
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.palette.AsciiLuminancePalette
import link.socket.phosphor.palette.CognitiveColorRamp
import link.socket.phosphor.render.AsciiCell
import link.socket.phosphor.render.CellBuffer
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.CognitivePhase

/**
 * Keeps the README's Quick Start honest. The snippet between the `quick-start`
 * markers below must compile and run, and [readmeShowsTheCompiledSnippet]
 * fails when the README's copy drifts from it.
 */
class ReadmeQuickStartTest {
    @Test
    fun quickStartCompilesAndRuns() {
        val buffer = quickStart()

        assertEquals(80, buffer.width)
        assertEquals(24, buffer.height)
        assertNotEquals(AsciiCell.EMPTY, buffer[12, 40])
    }

    @Test
    fun readmeShowsTheCompiledSnippet() {
        val readmeBlock = readmeQuickStartBlock()

        val imports = Regex("^import .*$", RegexOption.MULTILINE).findAll(sourceText()).map { it.value }.toList()
        imports
            .filterNot { it.startsWith("import java.") || it.startsWith("import kotlin.test.") }
            .forEach { assertTrue(readmeBlock.contains(it), "README quick-start is missing `$it`") }

        assertTrue(
            readmeBlock.contains(compiledSnippet()),
            "README quick-start no longer matches the code between the quick-start markers in ReadmeQuickStartTest.kt",
        )
    }

    // region quick-start
    private fun quickStart(): CellBuffer {
        // 1. Define an agent's visible state and add it to a layer
        val agent =
            AgentVisualState(
                id = "agent-1",
                name = "Atlas",
                role = "reasoning",
                position = Vector2(40f, 12f),
                cognitivePhase = CognitivePhase.EXECUTE,
            )
        val agentLayer = AgentLayer(width = 80, height = 24)
        agentLayer.addAgent(agent)

        // 2. Create the animation field
        val particles = ParticleSystem(maxParticles = 500)
        val substrate = SubstrateState.create(width = 80, height = 24)

        // 3. Let the choreographer respond to phase transitions
        val choreographer = CognitiveChoreographer(particles, SubstrateAnimator())
        val nextSubstrate = choreographer.update(agents = agentLayer, substrate = substrate, deltaTime = 0.016f)

        // 4. Map luminance to cells and write them to a cell buffer
        val palette = AsciiLuminancePalette.EXECUTE
        val colorRamp = CognitiveColorRamp.forPhase(CognitivePhase.EXECUTE)
        val buffer = CellBuffer(width = 80, height = 24)
        for (row in 0 until buffer.height) {
            for (col in 0 until buffer.width) {
                buffer[row, col] =
                    AsciiCell.fromSurfaceDithered(
                        luminance = 0.8f,
                        normalX = 0.5f, normalY = 0.2f,
                        screenX = col, screenY = row,
                        palette = palette,
                        colorRamp = colorRamp,
                    )
            }
        }

        // 5. Pass the buffer to your surface adapter
        return buffer
    }
    // endregion

    private fun sourceText(): String =
        File("src/jvmTest/kotlin/link/socket/phosphor/docs/ReadmeQuickStartTest.kt").readText()

    /** The snippet body, dedented to the README's indentation and without the `return`. */
    private fun compiledSnippet(): String {
        val lines = sourceText().lines()
        val begin = lines.indexOfFirst { it.trim() == "// region quick-start" } + 2
        val end = lines.indexOfFirst { it.trim() == "// endregion" } - 2
        return lines.subList(begin, end)
            .filterNot { it.trim() == "return buffer" }
            .joinToString("\n") { it.removePrefix("        ") }
            .trim()
    }

    private fun readmeQuickStartBlock(): String {
        val readme = File("../README.md").readText()
        val start = readme.indexOf("## Quick Start")
        check(start >= 0) { "README has no Quick Start section" }
        val fenceStart = readme.indexOf("import link.socket", start)
        val fenceEnd = readme.indexOf("\n```", fenceStart)
        return readme.substring(fenceStart, fenceEnd)
    }
}
