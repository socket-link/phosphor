package link.socket.phosphor.runtime

import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.emitter.EmitterInstance
import link.socket.phosphor.field.FlowConnection
import link.socket.phosphor.field.Particle
import link.socket.phosphor.field.ParticleAttractor
import link.socket.phosphor.field.ParticleType
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.render.Camera
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.CognitivePhase

/**
 * Immutable scene output after one [CognitiveSceneRuntime.update] tick.
 */
data class SceneSnapshot(
    val frameIndex: Long,
    val elapsedTimeSeconds: Float,
    val coordinateSpace: CoordinateSpace,
    val agentStates: List<AgentVisualState>,
    val substrateState: SubstrateState,
    val particleStates: List<ParticleState>,
    val flowConnections: List<FlowConnection>,
    val flowField: FlowFieldState?,
    val waveformHeightField: FloatArray?,
    val waveformGridWidth: Int?,
    val waveformGridDepth: Int?,
    val cameraTransform: CameraTransform?,
    val emitterStates: List<EmitterState>,
    val choreographyPhase: CognitivePhase,
) {
    init {
        if (waveformHeightField != null) {
            require(waveformGridWidth != null && waveformGridDepth != null) {
                "waveform grid dimensions are required when waveformHeightField is present"
            }
            require(waveformHeightField.size == waveformGridWidth * waveformGridDepth) {
                "waveformHeightField size (${waveformHeightField.size}) must equal gridWidth * gridDepth " +
                    "(${waveformGridWidth * waveformGridDepth})"
            }
        } else {
            require(waveformGridWidth == null && waveformGridDepth == null) {
                "waveform grid dimensions must be null when waveformHeightField is null"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SceneSnapshot) return false

        if (frameIndex != other.frameIndex) return false
        if (elapsedTimeSeconds != other.elapsedTimeSeconds) return false
        if (coordinateSpace != other.coordinateSpace) return false
        if (agentStates != other.agentStates) return false
        if (substrateState != other.substrateState) return false
        if (particleStates != other.particleStates) return false
        if (flowConnections != other.flowConnections) return false
        if (flowField != other.flowField) return false
        if (!(waveformHeightField?.contentEquals(other.waveformHeightField) ?: (other.waveformHeightField == null))) {
            return false
        }
        if (waveformGridWidth != other.waveformGridWidth) return false
        if (waveformGridDepth != other.waveformGridDepth) return false
        if (cameraTransform != other.cameraTransform) return false
        if (emitterStates != other.emitterStates) return false
        if (choreographyPhase != other.choreographyPhase) return false

        return true
    }

    override fun hashCode(): Int {
        var result = frameIndex.hashCode()
        result = 31 * result + elapsedTimeSeconds.hashCode()
        result = 31 * result + coordinateSpace.hashCode()
        result = 31 * result + agentStates.hashCode()
        result = 31 * result + substrateState.hashCode()
        result = 31 * result + particleStates.hashCode()
        result = 31 * result + flowConnections.hashCode()
        result = 31 * result + (flowField?.hashCode() ?: 0)
        result = 31 * result + (waveformHeightField?.contentHashCode() ?: 0)
        result = 31 * result + (waveformGridWidth ?: 0)
        result = 31 * result + (waveformGridDepth ?: 0)
        result = 31 * result + (cameraTransform?.hashCode() ?: 0)
        result = 31 * result + emitterStates.hashCode()
        result = 31 * result + choreographyPhase.hashCode()
        return result
    }
}

/**
 * Snapshot of the substrate flow vectors.
 */
data class FlowFieldState(
    val width: Int,
    val height: Int,
    val vectors: List<Vector2>,
) {
    init {
        require(width > 0) { "width must be > 0, got $width" }
        require(height > 0) { "height must be > 0, got $height" }
        require(vectors.size == width * height) {
            "vectors size (${vectors.size}) must equal width * height (${width * height})"
        }
    }
}

/**
 * Immutable particle payload for snapshot consumers.
 */
data class ParticleState(
    val position: Vector2,
    val velocity: Vector2,
    val life: Float,
    val type: ParticleType,
    val glyph: Char,
    val age: Float,
    val attractor: ParticleAttractor? = null,
)

/**
 * Snapshot of an active emitter instance.
 */
data class EmitterState(
    val effectName: String,
    val position: Vector3,
    val activatedAt: Float,
    val age: Float,
    val duration: Float,
    val radius: Float,
    val metadata: Map<String, Float>,
)

/**
 * Camera state after an update tick.
 */
data class CameraTransform(
    val position: Vector3,
    val target: Vector3,
    val up: Vector3,
    val fovY: Float,
    val near: Float,
    val far: Float,
    val projectionType: Camera.ProjectionType,
)

internal fun Particle.toParticleState(): ParticleState =
    ParticleState(
        position = position,
        velocity = velocity,
        life = life,
        type = type,
        glyph = glyph,
        age = age,
        attractor = attractor,
    )

internal fun EmitterInstance.toEmitterState(): EmitterState =
    EmitterState(
        effectName = effect.name,
        position = position,
        activatedAt = activatedAt,
        age = age,
        duration = effect.activeDuration(metadata),
        radius = effect.radius,
        metadata = metadata,
    )

internal fun Camera.toCameraTransform(): CameraTransform =
    CameraTransform(
        position = position,
        target = target,
        up = up,
        fovY = fovY,
        near = near,
        far = far,
        projectionType = projectionType,
    )
