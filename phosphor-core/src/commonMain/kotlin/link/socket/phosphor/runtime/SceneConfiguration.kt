package link.socket.phosphor.runtime

import link.socket.phosphor.choreography.AgentLayoutOrientation
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.CognitivePhase

/**
 * Configuration for [CognitiveSceneRuntime].
 */
data class SceneConfiguration(
    val width: Int,
    val height: Int,
    val agents: List<AgentDescriptor> = emptyList(),
    val initialConnections: List<FlowConnectionDescriptor> = emptyList(),
    val enableWaveform: Boolean = true,
    val enableParticles: Boolean = true,
    val enableFlow: Boolean = true,
    val enableEmitters: Boolean = true,
    val enableCamera: Boolean = true,
    val coordinateSpace: CoordinateSpace = CoordinateSpace.WORLD_CENTERED,
    val seed: Long = 0L,
    val agentLayout: AgentLayoutOrientation = AgentLayoutOrientation.CUSTOM,
    val waveform: WaveformConfiguration = WaveformConfiguration(),
    val cameraOrbit: CameraOrbitConfiguration = CameraOrbitConfiguration(),
) {
    init {
        require(width > 0) { "width must be > 0, got $width" }
        require(height > 0) { "height must be > 0, got $height" }
    }
}

/**
 * Declarative description of an initial agent.
 */
data class AgentDescriptor(
    val id: String,
    val name: String = id,
    val role: String = "reasoning",
    val position: Vector2,
    val position3D: Vector3? = null,
    val state: AgentActivityState = AgentActivityState.IDLE,
    val statusText: String = "",
    val cognitivePhase: CognitivePhase = CognitivePhase.NONE,
    val phaseProgress: Float = 0f,
) {
    init {
        require(id.isNotBlank()) { "id cannot be blank" }
    }

    fun toVisualState(): AgentVisualState =
        AgentVisualState(
            id = id,
            name = name,
            role = role,
            position = position,
            position3D = position3D ?: Vector3(position.x, 0f, position.y),
            state = state,
            statusText = statusText,
            cognitivePhase = cognitivePhase,
            phaseProgress = phaseProgress,
        )
}

/**
 * Declarative connection graph for optional flow simulation.
 */
data class FlowConnectionDescriptor(
    val sourceAgentId: String,
    val targetAgentId: String,
    val startHandoff: Boolean = false,
) {
    init {
        require(sourceAgentId.isNotBlank()) { "sourceAgentId cannot be blank" }
        require(targetAgentId.isNotBlank()) { "targetAgentId cannot be blank" }
    }
}

/**
 * Optional waveform tuning.
 */
data class WaveformConfiguration(
    val gridWidth: Int? = null,
    val gridDepth: Int? = null,
    val worldWidth: Float? = null,
    val worldDepth: Float? = null,
) {
    init {
        require(gridWidth == null || gridWidth > 0) {
            "gridWidth must be > 0 when provided, got $gridWidth"
        }
        require(gridDepth == null || gridDepth > 0) {
            "gridDepth must be > 0 when provided, got $gridDepth"
        }
        require(worldWidth == null || worldWidth > 0f) {
            "worldWidth must be > 0 when provided, got $worldWidth"
        }
        require(worldDepth == null || worldDepth > 0f) {
            "worldDepth must be > 0 when provided, got $worldDepth"
        }
    }
}

/**
 * Optional camera orbit tuning.
 */
data class CameraOrbitConfiguration(
    val radius: Float = 15f,
    val height: Float = 8f,
    val orbitSpeed: Float = 0.1f,
    val wobbleAmplitude: Float = 0.5f,
    val wobbleFrequency: Float = 0.3f,
) {
    init {
        require(radius > 0f) { "radius must be > 0, got $radius" }
        require(height >= 0f) { "height must be >= 0, got $height" }
        require(wobbleFrequency >= 0f) { "wobbleFrequency must be >= 0, got $wobbleFrequency" }
    }
}
