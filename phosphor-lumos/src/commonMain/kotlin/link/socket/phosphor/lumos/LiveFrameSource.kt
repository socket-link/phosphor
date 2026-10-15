package link.socket.phosphor.lumos

import link.socket.phosphor.choreography.AtmosphereChoreographer
import link.socket.phosphor.choreography.AtmosphereTransitionSpec
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.AtmosphereTransition

/**
 * A live scene as a [LumosFrameSource]: a [CognitiveSceneRuntime] and the [VoxelFrameBuilder] that
 * transduces its snapshots into frames, stepped together by whichever driver owns the clock.
 *
 * Wrap the pair you already have. Whatever sets its atmosphere or fires its glyphs keeps doing so on
 * [runtime] and [builder] directly; this source only steps them, and reads them to answer the
 * driver:
 *
 * - [still] lands any atmosphere transition on its target at once — re-set with
 *   [AtmosphereTransitionSpec.Immediate], so switching reduced motion off cannot jump back — and
 *   draws [VoxelFrameBuilder.buildStill]: the pose at the current phases, with a glyph at full
 *   strength for its window.
 * - [revision] changes when an atmosphere is set. Firing a glyph does not change it, so a paused
 *   orb never freezes on a glyph it cannot finish.
 * - [lookKey] is the name of the preset the scene is in, or heading to, with the builder's [Look]
 *   after a slash when it is not [LumosLook.BASE]; a custom atmosphere has none.
 * - [align] puts the builder's phases where a steady scene at the clock's time would hold them.
 *
 * Stateful and single-threaded, like the pair it wraps.
 *
 * @property runtime The scene. Its atmosphere subsystem must be enabled; it is the only one a
 *  frame reads.
 * @property builder The frame builder, whose lattice and phases this source steps.
 * @throws IllegalArgumentException when [runtime] was configured without an atmosphere.
 */
class LiveFrameSource(
    val runtime: CognitiveSceneRuntime,
    val builder: VoxelFrameBuilder,
) : LumosFrameSource {
    /**
     * Build the pair from the runtime's inputs.
     *
     * @param configuration Scene to simulate; atmosphere must be enabled.
     * @param renderConfig Embedder knobs forwarded to the frame builder.
     * @param probe Per-phase timing sink forwarded to the frame builder.
     */
    constructor(
        configuration: SceneConfiguration,
        renderConfig: LumosRenderConfig = LumosRenderConfig(),
        probe: FrameProbe = FrameProbe.Disabled,
    ) : this(
        runtime = CognitiveSceneRuntime(configuration),
        builder =
            VoxelFrameBuilder(
                initialResolution = configuration.initialAtmosphere.resolution,
                config = renderConfig,
                probe = probe,
            ),
    )

    /**
     * A scene with nothing in it but an atmosphere — the one subsystem a frame reads — starting
     * at [atmosphere]: what an orb on its own needs.
     *
     * @param atmosphere Where the scene starts, such as `AtmospherePresets.THINKING`.
     * @param renderConfig Embedder knobs forwarded to the frame builder.
     * @param probe Per-phase timing sink forwarded to the frame builder.
     */
    constructor(
        atmosphere: AtmosphereState,
        renderConfig: LumosRenderConfig = LumosRenderConfig(),
        probe: FrameProbe = FrameProbe.Disabled,
    ) : this(
        configuration =
            SceneConfiguration(
                width = 1,
                height = 1,
                enableWaveform = false,
                enableParticles = false,
                enableFlow = false,
                enableEmitters = false,
                enableCamera = false,
                enableAtmosphere = true,
                initialAtmosphere = atmosphere,
            ),
        renderConfig = renderConfig,
        probe = probe,
    )

    private val choreographer: AtmosphereChoreographer =
        requireNotNull(runtime.atmosphereChoreographer) {
            "LiveFrameSource requires SceneConfiguration.enableAtmosphere = true"
        }

    private var seenTransition: AtmosphereTransition? = null
    private var seenAtmosphere: AtmosphereState? = null
    private var changes: Long = 0L

    override fun next(dt: Float): VoxelFrame = builder.build(runtime.update(dt), dt)

    override fun still(dt: Float): VoxelFrame {
        choreographer.activeTransition?.let { transition ->
            choreographer.setAtmosphere(transition.to, transition.toPresetName, AtmosphereTransitionSpec.Immediate)
        }
        return builder.buildStill(runtime.update(dt), dt)
    }

    // The choreographer allocates a transition each time an atmosphere is set, and replaces it only
    // when the runtime steps, so while a driver is paused identity alone says whether one was set.
    override val revision: Long
        get() {
            val transition = choreographer.activeTransition
            val atmosphere = choreographer.currentState
            if (transition !== seenTransition || atmosphere !== seenAtmosphere) {
                seenTransition = transition
                seenAtmosphere = atmosphere
                changes++
            }
            return changes
        }

    override val lookKey: String?
        get() {
            val heading = choreographer.activeTransition?.to ?: choreographer.currentState
            val preset = AtmospherePresets.ALL.firstOrNull { (_, preset) -> preset == heading }?.first ?: return null
            val look = builder.look
            return if (look.look == LumosLook.BASE) preset else "$preset/${look.key}"
        }

    override fun align(lookTime: Double) {
        if (choreographer.activeTransition != null) return
        builder.phases = FramePhases.at(choreographer.currentState, lookTime)
    }
}
