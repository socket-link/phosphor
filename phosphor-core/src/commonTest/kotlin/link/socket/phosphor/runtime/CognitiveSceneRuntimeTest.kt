package link.socket.phosphor.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.choreography.AgentLayoutOrientation
import link.socket.phosphor.emitter.EmitterEffect
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase

class CognitiveSceneRuntimeTest {
    @Test
    fun `update is deterministic with identical seed and dt sequence`() {
        val configuration = populatedConfiguration(seed = 4242L)
        val runtimeA = CognitiveSceneRuntime(configuration)
        val runtimeB = CognitiveSceneRuntime(configuration)

        runtimeA.emit(EmitterEffect.SparkBurst(), Vector3(14f, 0f, 9f))
        runtimeB.emit(EmitterEffect.SparkBurst(), Vector3(14f, 0f, 9f))

        var finalA = runtimeA.snapshot()
        var finalB = runtimeB.snapshot()

        repeat(60) {
            finalA = runtimeA.update(1f / 60f)
            finalB = runtimeB.update(1f / 60f)
        }

        assertEquals(finalA, finalB)
    }

    @Test
    fun `different seeds diverge for stochastic subsystems`() {
        val runtimeA = CognitiveSceneRuntime(populatedConfiguration(seed = 11L))
        val runtimeB = CognitiveSceneRuntime(populatedConfiguration(seed = 99L))

        var finalA = runtimeA.snapshot()
        var finalB = runtimeB.snapshot()

        repeat(30) {
            finalA = runtimeA.update(1f / 60f)
            finalB = runtimeB.update(1f / 60f)
        }

        assertNotEquals(finalA, finalB)
    }

    @Test
    fun `disabled subsystems are skipped and produce null or empty snapshot fields`() {
        val runtime =
            CognitiveSceneRuntime(
                SceneConfiguration(
                    width = 24,
                    height = 12,
                    agents =
                        listOf(
                            AgentDescriptor(
                                id = "spark",
                                position = Vector2(12f, 6f),
                                state = AgentActivityState.PROCESSING,
                                cognitivePhase = CognitivePhase.PERCEIVE,
                            ),
                        ),
                    enableWaveform = false,
                    enableParticles = false,
                    enableFlow = false,
                    enableEmitters = false,
                    enableCamera = false,
                ),
            )

        val snapshot = runtime.update(1f / 30f)

        assertNull(runtime.waveform)
        assertNull(runtime.particles)
        assertNull(runtime.flow)
        assertNull(runtime.emitters)
        assertNull(runtime.cameraOrbit)

        assertNull(snapshot.waveformHeightField)
        assertNull(snapshot.waveformGridWidth)
        assertNull(snapshot.waveformGridDepth)
        assertTrue(snapshot.particleStates.isEmpty())
        assertTrue(snapshot.flowConnections.isEmpty())
        assertNull(snapshot.flowField)
        assertTrue(snapshot.emitterStates.isEmpty())
        assertNull(snapshot.cameraTransform)
    }

    @Test
    fun `frame index increments each update`() {
        val runtime = CognitiveSceneRuntime(populatedConfiguration(seed = 7L))

        val first = runtime.update(0.1f)
        val second = runtime.update(0.1f)

        assertEquals(first.frameIndex + 1, second.frameIndex)
        assertTrue(second.elapsedTimeSeconds > first.elapsedTimeSeconds)
    }

    private fun populatedConfiguration(seed: Long): SceneConfiguration {
        return SceneConfiguration(
            width = 28,
            height = 18,
            seed = seed,
            agentLayout = AgentLayoutOrientation.CUSTOM,
            agents =
                listOf(
                    AgentDescriptor(
                        id = "spark",
                        name = "Spark",
                        role = "reasoning",
                        position = Vector2(8f, 9f),
                        state = AgentActivityState.PROCESSING,
                        cognitivePhase = CognitivePhase.PERCEIVE,
                    ),
                    AgentDescriptor(
                        id = "jazz",
                        name = "Jazz",
                        role = "codegen",
                        position = Vector2(20f, 9f),
                        state = AgentActivityState.PROCESSING,
                        cognitivePhase = CognitivePhase.PLAN,
                    ),
                ),
            initialConnections =
                listOf(
                    FlowConnectionDescriptor(
                        sourceAgentId = "spark",
                        targetAgentId = "jazz",
                        startHandoff = true,
                    ),
                ),
            enableWaveform = true,
            enableParticles = true,
            enableFlow = true,
            enableEmitters = true,
            enableCamera = true,
        )
    }
}
