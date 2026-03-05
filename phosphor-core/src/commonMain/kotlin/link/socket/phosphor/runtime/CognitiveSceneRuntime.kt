package link.socket.phosphor.runtime

import kotlin.random.Random
import link.socket.phosphor.choreography.AgentLayer
import link.socket.phosphor.choreography.AgentLayoutOrientation
import link.socket.phosphor.choreography.CognitiveChoreographer
import link.socket.phosphor.emitter.EmitterEffect
import link.socket.phosphor.emitter.EmitterManager
import link.socket.phosphor.field.FlowConnection
import link.socket.phosphor.field.FlowLayer
import link.socket.phosphor.field.Particle
import link.socket.phosphor.field.ParticleSystem
import link.socket.phosphor.field.SubstrateAnimator
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.render.Camera
import link.socket.phosphor.render.CameraOrbit
import link.socket.phosphor.render.CognitiveWaveform
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.CognitivePhase

/**
 * Deterministic orchestration API for Phosphor scene subsystems.
 *
 * This runtime owns the update ordering only. It does not own threading or frame timing.
 */
class CognitiveSceneRuntime(
    val configuration: SceneConfiguration,
) {
    val agents: AgentLayer =
        AgentLayer(
            width = configuration.width,
            height = configuration.height,
            orientation = configuration.agentLayout,
        )

    val substrateAnimator: SubstrateAnimator = SubstrateAnimator(seed = configuration.seed)
    val particles: ParticleSystem? = if (configuration.enableParticles) ParticleSystem() else null
    val flow: FlowLayer? =
        if (configuration.enableFlow) {
            FlowLayer(configuration.width, configuration.height, random = seededRandom(0x5F37_59DF))
        } else {
            null
        }
    val waveform: CognitiveWaveform? =
        if (configuration.enableWaveform) {
            CognitiveWaveform(
                gridWidth = configuration.waveform.gridWidth ?: configuration.width,
                gridDepth = configuration.waveform.gridDepth ?: configuration.height,
                worldWidth = configuration.waveform.worldWidth ?: configuration.width.toFloat(),
                worldDepth = configuration.waveform.worldDepth ?: configuration.height.toFloat(),
                agentCoordinateSpace = configuration.coordinateSpace,
            )
        } else {
            null
        }
    val cameraOrbit: CameraOrbit? =
        if (configuration.enableCamera) {
            CameraOrbit(
                radius = configuration.cameraOrbit.radius,
                height = configuration.cameraOrbit.height,
                orbitSpeed = configuration.cameraOrbit.orbitSpeed,
                wobbleAmplitude = configuration.cameraOrbit.wobbleAmplitude,
                wobbleFrequency = configuration.cameraOrbit.wobbleFrequency,
            )
        } else {
            null
        }
    val emitters: EmitterManager? = if (configuration.enableEmitters) EmitterManager() else null
    val choreographer: CognitiveChoreographer? =
        particles?.let {
            CognitiveChoreographer(
                particles = it,
                substrateAnimator = substrateAnimator,
                random = seededRandom(0x1A2B_3C4D),
            )
        }

    private var substrateState: SubstrateState =
        SubstrateState.create(
            width = configuration.width,
            height = configuration.height,
        )

    private var frameIndex: Long = 0
    private var elapsedTimeSeconds: Float = 0f
    private var latestSnapshot: SceneSnapshot = captureSnapshot(camera = cameraOrbit?.currentCamera())

    private val queuedEmitterEffects = mutableListOf<QueuedEmitterEffect>()

    init {
        addInitialAgents()
        initializeConnections()
        latestSnapshot = captureSnapshot(camera = cameraOrbit?.currentCamera())
    }

    /**
     * Queue an emitter effect to be fired during the next update's emission pass.
     */
    fun emit(
        effect: EmitterEffect,
        position: Vector3,
        metadata: Map<String, Float> = emptyMap(),
    ) {
        if (emitters == null) return
        queuedEmitterEffects += QueuedEmitterEffect(effect, position, metadata)
    }

    /**
     * Snapshot current runtime state without advancing simulation.
     */
    fun snapshot(): SceneSnapshot = latestSnapshot

    /**
     * Advance the scene by [deltaTimeSeconds] and return an immutable snapshot.
     */
    fun update(deltaTimeSeconds: Float): SceneSnapshot {
        require(deltaTimeSeconds >= 0f) {
            "deltaTimeSeconds must be >= 0, got $deltaTimeSeconds"
        }

        var updatedSubstrate = substrateState

        // 1) Choreographer phase advance.
        if (choreographer != null) {
            updatedSubstrate = choreographer.update(agents, updatedSubstrate, deltaTimeSeconds)
        }

        // 2) Ambient substrate animation.
        updatedSubstrate = substrateAnimator.updateAmbient(updatedSubstrate, deltaTimeSeconds)

        // 3) Agent state update.
        agents.update(deltaTimeSeconds)

        // 4) Emitter emission pass and lifecycle update.
        if (emitters != null) {
            flushQueuedEmitterEffects()
            emitters.update(deltaTimeSeconds)
        }

        // 5) Particle simulation.
        if (particles != null) {
            particles.update(deltaTimeSeconds)
            particles.updateSubstrate(updatedSubstrate)
        }

        // 6) Flow field advection.
        if (flow != null) {
            flow.update(deltaTimeSeconds)
            updatedSubstrate = flow.updateSubstrate(updatedSubstrate)
        }

        // 7) Waveform sampling.
        waveform?.update(updatedSubstrate, agents, flow, deltaTimeSeconds)

        // 8) Camera orbit.
        val camera = cameraOrbit?.update(deltaTimeSeconds)

        substrateState = updatedSubstrate
        frameIndex += 1
        elapsedTimeSeconds += deltaTimeSeconds

        latestSnapshot = captureSnapshot(camera)
        return latestSnapshot
    }

    private fun addInitialAgents() {
        configuration.agents.forEach { descriptor ->
            agents.addAgent(descriptor.toVisualState())
            if (configuration.agentLayout == AgentLayoutOrientation.CUSTOM) {
                agents.setAgentPosition(descriptor.id, descriptor.position)
                descriptor.position3D?.let { agents.setAgentPosition3D(descriptor.id, it) }
            }
        }
    }

    private fun initializeConnections() {
        val flowLayer = flow ?: return
        configuration.initialConnections.forEach { descriptor ->
            val source = agents.getAgent(descriptor.sourceAgentId) ?: return@forEach
            val target = agents.getAgent(descriptor.targetAgentId) ?: return@forEach
            val id =
                flowLayer.createConnection(
                    sourceAgentId = source.id,
                    targetAgentId = target.id,
                    sourcePosition = source.position,
                    targetPosition = target.position,
                )
            if (descriptor.startHandoff) {
                flowLayer.startHandoff(id)
            }
        }
    }

    private fun flushQueuedEmitterEffects() {
        val manager = emitters ?: return
        if (queuedEmitterEffects.isEmpty()) return

        queuedEmitterEffects.forEach { queued ->
            manager.emit(
                effect = queued.effect,
                position = queued.position,
                currentTime = elapsedTimeSeconds,
                metadata = queued.metadata,
            )
        }
        queuedEmitterEffects.clear()
    }

    private fun captureSnapshot(camera: Camera?): SceneSnapshot {
        val sortedAgents = agents.allAgents.sortedBy(AgentVisualState::id)
        val copiedSubstrate = substrateState.deepCopy()
        val copiedParticles =
            particles?.getParticles()?.map(Particle::toParticleState)
                ?: emptyList()
        val copiedConnections =
            flow?.allConnections
                ?.sortedBy(FlowConnection::id)
                ?.map(FlowConnection::deepCopy)
                ?: emptyList()
        val flowFieldState =
            if (flow != null) {
                FlowFieldState(
                    width = copiedSubstrate.width,
                    height = copiedSubstrate.height,
                    vectors = copiedSubstrate.flowField.toList(),
                )
            } else {
                null
            }

        val waveformHeights = waveform?.heights?.copyOf()

        return SceneSnapshot(
            frameIndex = frameIndex,
            elapsedTimeSeconds = elapsedTimeSeconds,
            coordinateSpace = configuration.coordinateSpace,
            agentStates = sortedAgents,
            substrateState = copiedSubstrate,
            particleStates = copiedParticles,
            flowConnections = copiedConnections,
            flowField = flowFieldState,
            waveformHeightField = waveformHeights,
            waveformGridWidth = waveform?.gridWidth,
            waveformGridDepth = waveform?.gridDepth,
            cameraTransform = camera?.toCameraTransform(),
            emitterStates = emitters?.instances?.map { it.toEmitterState() } ?: emptyList(),
            choreographyPhase = dominantPhase(sortedAgents),
        )
    }

    private fun dominantPhase(agentStates: List<AgentVisualState>): CognitivePhase {
        if (agentStates.isEmpty()) return CognitivePhase.NONE

        return agentStates
            .asSequence()
            .map { it.cognitivePhase }
            .filter { it != CognitivePhase.NONE }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<CognitivePhase, Int>> { it.value }
                    .thenBy { it.key.ordinal },
            )
            .firstOrNull()
            ?.key
            ?: CognitivePhase.NONE
    }

    private fun seededRandom(salt: Int): Random {
        val seed = (configuration.seed xor salt.toLong()).hashCode()
        return Random(seed)
    }

    private data class QueuedEmitterEffect(
        val effect: EmitterEffect,
        val position: Vector3,
        val metadata: Map<String, Float>,
    )
}

private fun SubstrateState.deepCopy(): SubstrateState =
    copy(
        densityField = densityField.copyOf(),
        flowField = flowField.copyOf(),
        activityHotspots = activityHotspots.toList(),
    )

private fun FlowConnection.deepCopy(): FlowConnection =
    copy(
        taskToken =
            taskToken?.copy(
                trailParticles = taskToken.trailParticles.map(Particle::copy),
            ),
        path = path.toList(),
    )
