package link.socket.phosphor.coordinate

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import link.socket.phosphor.choreography.AgentLayer
import link.socket.phosphor.choreography.AgentLayoutOrientation
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.render.CognitiveWaveform
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.CognitivePhase

/**
 * Integration tests verifying that coordinate space unification produces
 * consistent waveform output regardless of whether agents are expressed
 * in WORLD_POSITIVE or WORLD_CENTERED coordinates.
 *
 * These tests use fixed parameters (no randomness) so results are
 * deterministic across platforms.
 */
class CoordinateSpaceIntegrationTest {
    private val worldWidth = 20f
    private val worldDepth = 15f
    private val gridWidth = 20
    private val gridDepth = 15

    private fun createSubstrate(density: Float = 0.1f): SubstrateState =
        SubstrateState.create(gridWidth, gridDepth, density)

    /**
     * Full scene: two agents placed at equivalent positions in positive vs
     * centered space. The heightfield snapshots should match.
     */
    @Test
    fun `full scene heightfield matches between positive and centered agents`() {
        val substrate = createSubstrate()

        // --- Positive-space scene ---
        val positiveWaveform =
            CognitiveWaveform(
                gridWidth = gridWidth,
                gridDepth = gridDepth,
                worldWidth = worldWidth,
                worldDepth = worldDepth,
                agentCoordinateSpace = CoordinateSpace.WORLD_POSITIVE,
            )
        val positiveAgents = AgentLayer(gridWidth, gridDepth, AgentLayoutOrientation.CUSTOM)
        positiveAgents.addAgent(
            AgentVisualState(
                id = "spark",
                name = "Spark",
                role = "reasoning",
                position = Vector2(10f, 7.5f),
                state = AgentActivityState.PROCESSING,
                cognitivePhase = CognitivePhase.EXECUTE,
            ),
        )
        positiveAgents.addAgent(
            AgentVisualState(
                id = "jazz",
                name = "Jazz",
                role = "codegen",
                position = Vector2(14f, 5f),
                state = AgentActivityState.ACTIVE,
                cognitivePhase = CognitivePhase.PLAN,
            ),
        )
        positiveWaveform.update(substrate, positiveAgents, null, dt = 1f)

        // --- Centered-space scene (same world points) ---
        val centeredWaveform =
            CognitiveWaveform(
                gridWidth = gridWidth,
                gridDepth = gridDepth,
                worldWidth = worldWidth,
                worldDepth = worldDepth,
                agentCoordinateSpace = CoordinateSpace.WORLD_CENTERED,
            )
        val centeredAgents = AgentLayer(gridWidth, gridDepth, AgentLayoutOrientation.CUSTOM)
        // Convert: centered = positive - worldSize/2
        centeredAgents.addAgent(
            AgentVisualState(
                id = "spark",
                name = "Spark",
                role = "reasoning",
                position = Vector2(10f - worldWidth / 2f, 7.5f - worldDepth / 2f),
                state = AgentActivityState.PROCESSING,
                cognitivePhase = CognitivePhase.EXECUTE,
            ),
        )
        centeredAgents.addAgent(
            AgentVisualState(
                id = "jazz",
                name = "Jazz",
                role = "codegen",
                position = Vector2(14f - worldWidth / 2f, 5f - worldDepth / 2f),
                state = AgentActivityState.ACTIVE,
                cognitivePhase = CognitivePhase.PLAN,
            ),
        )
        centeredWaveform.update(substrate, centeredAgents, null, dt = 1f)

        // --- Compare all grid heights ---
        var maxDiff = 0f
        for (gz in 0 until gridDepth) {
            for (gx in 0 until gridWidth) {
                val hPos = positiveWaveform.heightAt(gx, gz)
                val hCen = centeredWaveform.heightAt(gx, gz)
                val diff = abs(hPos - hCen)
                if (diff > maxDiff) maxDiff = diff
                assertTrue(
                    diff < 0.01f,
                    "height mismatch at ($gx,$gz): positive=$hPos, centered=$hCen, diff=$diff",
                )
            }
        }
    }

    /**
     * Verify that waveform peaks appear at the expected grid locations
     * for agents in both coordinate spaces.
     */
    @Test
    fun `agent peaks appear at correct grid locations in both spaces`() {
        val substrate = createSubstrate()

        // Agent at world center using POSITIVE coords
        val wPos =
            CognitiveWaveform(
                gridWidth = gridWidth,
                gridDepth = gridDepth,
                worldWidth = worldWidth,
                worldDepth = worldDepth,
                agentCoordinateSpace = CoordinateSpace.WORLD_POSITIVE,
            )
        val aPos = AgentLayer(gridWidth, gridDepth, AgentLayoutOrientation.CUSTOM)
        aPos.addAgent(
            AgentVisualState(
                id = "a",
                name = "A",
                role = "r",
                position = Vector2(worldWidth / 2f, worldDepth / 2f),
                state = AgentActivityState.PROCESSING,
            ),
        )
        wPos.update(substrate, aPos, null, dt = 1f)

        // Agent at world center using CENTERED coords (should be 0,0)
        val wCen =
            CognitiveWaveform(
                gridWidth = gridWidth,
                gridDepth = gridDepth,
                worldWidth = worldWidth,
                worldDepth = worldDepth,
                agentCoordinateSpace = CoordinateSpace.WORLD_CENTERED,
            )
        val aCen = AgentLayer(gridWidth, gridDepth, AgentLayoutOrientation.CUSTOM)
        aCen.addAgent(
            AgentVisualState(
                id = "a",
                name = "A",
                role = "r",
                position = Vector2(0f, 0f),
                state = AgentActivityState.PROCESSING,
            ),
        )
        wCen.update(substrate, aCen, null, dt = 1f)

        // Grid center
        val centerGx = gridWidth / 2
        val centerGz = gridDepth / 2

        // Both should have a peak at grid center higher than corners
        val peakPos = wPos.heightAt(centerGx, centerGz)
        val peakCen = wCen.heightAt(centerGx, centerGz)
        val cornerPos = wPos.heightAt(0, 0)
        val cornerCen = wCen.heightAt(0, 0)

        assertTrue(peakPos > cornerPos * 2, "positive-space peak should be well above corner")
        assertTrue(peakCen > cornerCen * 2, "centered-space peak should be well above corner")
        assertTrue(
            abs(peakPos - peakCen) < 0.01f,
            "peaks should match: positive=$peakPos, centered=$peakCen",
        )
    }

    /**
     * Regression: worldPosition() still returns centered coordinates,
     * verifying the rendering pipeline is unaffected.
     */
    @Test
    fun `worldPosition returns centered coordinates regardless of agentCoordinateSpace`() {
        val waveform =
            CognitiveWaveform(
                gridWidth = gridWidth,
                gridDepth = gridDepth,
                worldWidth = worldWidth,
                worldDepth = worldDepth,
                agentCoordinateSpace = CoordinateSpace.WORLD_CENTERED,
            )

        // Grid origin should map to negative world corner
        val origin = waveform.worldPosition(0, 0)
        assertTrue(origin.x < 0f, "grid origin X should be negative in centered world")
        assertTrue(origin.z < 0f, "grid origin Z should be negative in centered world")

        // Grid center should map near world origin
        val center = waveform.worldPosition(gridWidth / 2, gridDepth / 2)
        assertTrue(abs(center.x) < 1f, "grid center X should be near 0")
        assertTrue(abs(center.z) < 1f, "grid center Z should be near 0")
    }
}
