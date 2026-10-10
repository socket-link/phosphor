package link.socket.phosphor.trace

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import link.socket.phosphor.choreography.AtmosphereTransitionSpec
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AtmosphereState

/**
 * Drives a live simulation and transduces each tick into a `VoxelFrame`.
 *
 * The bench instrument opposite [Oscilloscope]: the oscilloscope records a
 * signal, the generator produces one. It owns the two halves of the live Lumos
 * path — a [CognitiveSceneRuntime] and a [VoxelFrameBuilder] — and steps them
 * together, so the frames it emits are the same frames a trace is recorded from.
 * [Oscilloscope.capture] drives one of these; so does [LumosSource.Live]. That
 * shared path is what makes replay-equals-live a property of the code rather
 * than a coincidence of two implementations.
 *
 * **The timestep is fixed at `1 / fps` and never reads the wall clock.** Phase
 * accumulators inside `VoxelFrameBuilder` integrate `dt` as `Float`, so the
 * frame stream is a function of how many times it was stepped and nothing else.
 * Feeding it a measured frame delta instead would make output depend on machine
 * load, and a recording of it would never replay identically. Wall time decides
 * *when* [frames] takes a step, never *how big* the step is.
 *
 * Stateful and single-threaded, like the builder it wraps: one generator per
 * stream, driven from one coroutine.
 *
 * @param configuration Scene to simulate. Atmosphere must be enabled — it is the
 *  only subsystem a `VoxelFrame` reads.
 * @param renderConfig Embedder knobs forwarded to the frame builder.
 * @param fps Steps per simulated second; also the cadence [frames] paces at.
 * @param probe Per-phase timing sink forwarded to the builder.
 */
class SignalGenerator(
    configuration: SceneConfiguration,
    renderConfig: LumosRenderConfig = LumosRenderConfig(),
    val fps: Int = DEFAULT_FPS,
    probe: FrameProbe = FrameProbe.Disabled,
) {
    init {
        require(fps > 0) { "fps must be > 0, was $fps" }
        require(configuration.enableAtmosphere) {
            "SignalGenerator requires SceneConfiguration.enableAtmosphere = true"
        }
    }

    private val runtime = CognitiveSceneRuntime(configuration)

    private val builder =
        VoxelFrameBuilder(
            initialResolution = configuration.initialAtmosphere.resolution,
            config = renderConfig,
            probe = probe,
        )

    private val dt: Float = 1f / fps

    /** Wall-clock duration of one simulated step, `1 / fps`. */
    val frameDuration: Duration = (1.seconds / fps)

    /** How many frames this generator has produced. */
    var frameCount: Long = 0L
        private set

    /**
     * Transition the scene toward [state]. Takes effect on the next step.
     *
     * @param state New atmosphere value.
     * @param spec Overrides the tabled duration and easing; null consults the
     *  table. [AtmosphereTransitionSpec.Immediate] snaps instead of interpolating,
     *  which is how a tuning surface drives a live preview from a slider.
     */
    fun setAtmosphere(
        state: AtmosphereState,
        spec: AtmosphereTransitionSpec? = null,
    ) {
        runtime.setAtmosphere(state, spec)
    }

    /**
     * Transition the scene toward the named preset.
     *
     * @param name Registered preset name, matched case-insensitively.
     * @param spec Overrides the tabled duration and easing; null consults the table.
     * @throws IllegalArgumentException when no preset carries that name.
     */
    fun setAtmospherePreset(
        name: String,
        spec: AtmosphereTransitionSpec? = null,
    ) {
        runtime.setAtmospherePreset(name, spec)
    }

    /**
     * Fire a glyph, carved into the orb over [durationSeconds]. Replaces any
     * glyph already burning.
     */
    fun queueGlyph(
        glyph: LumosGlyph,
        durationSeconds: Float = DEFAULT_GLYPH_SECONDS,
    ) {
        builder.queueGlyph(glyph, durationSeconds)
    }

    /** Step the simulation once and transduce the snapshot into a frame. */
    fun nextFrame(): VoxelFrame {
        frameCount++
        return builder.build(runtime.update(dt), dt)
    }

    /**
     * Emit the live simulation as a frame stream paced by [timeSource].
     *
     * Exactly one fixed step per emission, with the sleep between steps
     * shortened to absorb drift — so the stream tracks wall time without ever
     * handing the simulation a variable `dt`. A collector that falls behind gets
     * frames late, never frames stepped by a different amount.
     *
     * @param timeSource Clock the pacing reads. Affects only *when* frames are
     *  emitted; the frames themselves are identical under any clock, which is
     *  why a replay can match them.
     */
    fun frames(timeSource: TimeSource = TimeSource.Monotonic): Flow<VoxelFrame> =
        flow {
            val mark = timeSource.markNow()
            var stepped = 0L
            while (true) {
                emit(nextFrame())
                stepped++
                val target = frameDuration * stepped.toDouble()
                val elapsed = mark.elapsedNow()
                if (target > elapsed) {
                    delay(target - elapsed)
                }
            }
        }

    companion object {
        /** Steps per second when the caller does not say. One frame per 60 Hz refresh. */
        const val DEFAULT_FPS: Int = 60

        /** Glyph display window when the caller does not say, in seconds. */
        const val DEFAULT_GLYPH_SECONDS: Float = 1.5f
    }
}
