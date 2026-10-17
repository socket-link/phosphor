package link.socket.phosphor.runtime

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.choreography.AgentLayoutOrientation
import link.socket.phosphor.choreography.AtmosphereTransitionSpec
import link.socket.phosphor.emitter.EmitterEffect
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase
import link.socket.phosphor.signal.LumosInput

class CognitiveSceneRuntimeTest {
    @Test
    fun `default configuration leaves atmosphere disabled`() {
        val runtime =
            CognitiveSceneRuntime(
                SceneConfiguration(
                    width = 8,
                    height = 6,
                ),
            )

        assertNull(runtime.currentAtmosphere)
        assertNull(runtime.snapshot().atmosphere)
        assertNull(runtime.update(0.016f).atmosphere)
    }

    @Test
    fun `enabled atmosphere starts with idle preset`() {
        val runtime =
            CognitiveSceneRuntime(
                SceneConfiguration(
                    width = 8,
                    height = 6,
                    enableAtmosphere = true,
                ),
            )

        assertEquals(AtmospherePresets.IDLE, runtime.currentAtmosphere)
        assertEquals(AtmospherePresets.IDLE, runtime.snapshot().atmosphere)
        assertEquals(AtmospherePresets.IDLE, runtime.update(0.016f).atmosphere)
    }

    @Test
    fun `enabled atmosphere uses configured initial atmosphere`() {
        val runtime =
            CognitiveSceneRuntime(
                SceneConfiguration(
                    width = 8,
                    height = 6,
                    enableAtmosphere = true,
                    initialAtmosphere = AtmospherePresets.LISTENING,
                ),
            )

        assertEquals(AtmospherePresets.LISTENING, runtime.currentAtmosphere)
        assertEquals(AtmospherePresets.LISTENING, runtime.snapshot().atmosphere)
    }

    @Test
    fun `setAtmosphere drives transition to completion across updates`() {
        val runtime =
            CognitiveSceneRuntime(
                SceneConfiguration(
                    width = 8,
                    height = 6,
                    enableAtmosphere = true,
                ),
            )

        runtime.setAtmosphere(AtmospherePresets.THINKING)
        val midSnapshot = runtime.update(0.1f)
        assertTrue(midSnapshot.atmosphere != AtmospherePresets.THINKING)
        assertTrue(midSnapshot.atmosphereTransition != null)

        val finalSnapshot = runtime.update(2f)
        assertEquals(AtmospherePresets.THINKING, runtime.currentAtmosphere)
        assertEquals(AtmospherePresets.THINKING, finalSnapshot.atmosphere)
        assertNull(finalSnapshot.atmosphereTransition)
    }

    @Test
    fun `setAtmosphere with the immediate spec lands in one update and leaves no transition`() {
        val runtime =
            CognitiveSceneRuntime(
                SceneConfiguration(
                    width = 8,
                    height = 6,
                    enableAtmosphere = true,
                ),
            )
        val tuned = AtmospherePresets.IDLE.copy(pulseAmplitude = 0.28f, surfaceBump = 0.33f)

        runtime.setAtmosphere(tuned, spec = AtmosphereTransitionSpec.Immediate)
        val snapshot = runtime.update(1f / 60f)

        assertEquals(tuned, snapshot.atmosphere)
        assertEquals(tuned, runtime.currentAtmosphere)
        assertNull(
            snapshot.atmosphereTransition,
            "an immediate set must not leave a transition for the frame builder to crossfade",
        )
    }

    @Test
    fun `setAtmospherePreset forwards an explicit spec instead of the tabled one`() {
        val runtime =
            CognitiveSceneRuntime(
                SceneConfiguration(
                    width = 8,
                    height = 6,
                    enableAtmosphere = true,
                ),
            )

        runtime.setAtmospherePreset(
            "listening",
            spec = AtmosphereTransitionSpec(durationSeconds = 3f, easingName = "settled"),
        )
        val snapshot = runtime.update(0f)

        val transition = requireNotNull(snapshot.atmosphereTransition)
        assertEquals(3f, transition.durationSeconds)
        assertEquals("settled", transition.easingName)
        assertEquals("listening", transition.toPresetName)
    }

    @Test
    fun `setAtmospherePreset resolves by name and triggers transition`() {
        val runtime =
            CognitiveSceneRuntime(
                SceneConfiguration(
                    width = 8,
                    height = 6,
                    enableAtmosphere = true,
                ),
            )

        runtime.setAtmospherePreset("listening")
        val snapshot = runtime.update(0.0f)

        val transition =
            requireNotNull(snapshot.atmosphereTransition) {
                "atmosphereTransition should be populated immediately after setAtmospherePreset"
            }
        assertEquals("idle", transition.fromPresetName)
        assertEquals("listening", transition.toPresetName)
        assertEquals("eager", transition.easingName)
    }

    @Test
    fun `setAtmospherePreset throws on unknown name`() {
        val runtime =
            CognitiveSceneRuntime(
                SceneConfiguration(
                    width = 8,
                    height = 6,
                    enableAtmosphere = true,
                ),
            )

        val failure =
            assertFailsWith<IllegalArgumentException> {
                runtime.setAtmospherePreset("nonexistent")
            }
        assertEquals("Unknown atmosphere preset: 'nonexistent'", failure.message)
    }

    @Test
    fun `setAtmosphere throws when atmosphere is disabled`() {
        val runtime =
            CognitiveSceneRuntime(
                SceneConfiguration(
                    width = 8,
                    height = 6,
                ),
            )

        val failure =
            assertFailsWith<IllegalStateException> {
                runtime.setAtmosphere(AtmospherePresets.THINKING)
            }

        assertEquals(
            "Atmosphere subsystem not enabled in SceneConfiguration. " +
                "Set enableAtmosphere = true to use setAtmosphere.",
            failure.message,
        )
    }

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
        assertNull(runtime.currentAtmosphere)

        assertNull(snapshot.waveformHeightField)
        assertNull(snapshot.waveformGridWidth)
        assertNull(snapshot.waveformGridDepth)
        assertTrue(snapshot.particleStates.isEmpty())
        assertTrue(snapshot.flowConnections.isEmpty())
        assertNull(snapshot.flowField)
        assertTrue(snapshot.emitterStates.isEmpty())
        assertNull(snapshot.cameraTransform)
        assertNull(snapshot.atmosphere)
    }

    @Test
    fun `input is NONE by default`() {
        val runtime = CognitiveSceneRuntime(populatedConfiguration(seed = 7L))

        assertEquals(LumosInput.NONE, runtime.snapshot().input)
        assertEquals(LumosInput.NONE, runtime.update(1f / 60f).input)
    }

    @Test
    fun `setInput lands on the next update and not before`() {
        val runtime = CognitiveSceneRuntime(populatedConfiguration(seed = 7L))
        runtime.update(1f / 60f)
        val input = LumosInput(pointer = Vector2(0.3f, 0.6f), pressed = true)

        runtime.setInput(input)
        assertEquals(LumosInput.NONE, runtime.snapshot().input, "snapshot() is unchanged until the next update")

        val next = runtime.update(1f / 60f)
        assertSame(input, next.input, "the snapshot carries the reference it was given")
        assertSame(input, runtime.snapshot().input)
        assertSame(input, runtime.update(1f / 60f).input, "the input stays until it is replaced")

        runtime.setInput(LumosInput.NONE)
        assertEquals(LumosInput.NONE, runtime.update(1f / 60f).input)
    }

    @Test
    fun `input changes nothing else in the scene`() {
        val configuration = populatedConfiguration(seed = 4242L).copy(enableAtmosphere = true)
        val plain = CognitiveSceneRuntime(configuration)
        val pointed = CognitiveSceneRuntime(configuration)
        plain.setAtmosphere(AtmospherePresets.THINKING)
        pointed.setAtmosphere(AtmospherePresets.THINKING)

        repeat(60) { frame ->
            val angle = frame * 0.1f
            pointed.setInput(LumosInput(pointer = Vector2(cos(angle), sin(angle)), pressed = frame % 2 == 0))
            val expected = plain.update(1f / 60f)
            val actual = pointed.update(1f / 60f)

            assertEquals(expected.atmosphere, actual.atmosphere, "frame $frame atmosphere")
            assertEquals(expected.atmosphereTransition, actual.atmosphereTransition, "frame $frame transition")
            assertEquals(expected.substrateState, actual.substrateState, "frame $frame substrate")
            assertEquals(expected, actual.copy(input = LumosInput.NONE), "frame $frame")
        }
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
