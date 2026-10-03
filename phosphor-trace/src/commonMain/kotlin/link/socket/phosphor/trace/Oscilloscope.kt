package link.socket.phosphor.trace

import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AtmosphereState

/**
 * Headless recorder for `VoxelFrame` streams.
 *
 * An oscilloscope captures a signal over time as a trace. This one drives
 * [CognitiveSceneRuntime] and [VoxelFrameBuilder] with no renderer and no UI
 * clock, stepping the simulation at exactly `1f / fps` seconds per frame, and
 * assembles the frames into a [VoxelTrace].
 *
 * Determinism contract: [capture] reads nothing from the environment. Equal
 * [CaptureConfig]s produce equal traces, and `TraceCodec.encode` of those traces
 * produces identical bytes. Only the atmosphere subsystem is enabled on the
 * runtime; particles, flow, waveform, emitters, and camera do not contribute to
 * a `VoxelFrame` and are switched off so capture stays cheap.
 */
object Oscilloscope {
    /**
     * Capture [config] into a [VoxelTrace].
     *
     * @return [Result.success] with the trace, or [Result.failure] carrying a
     *  [CaptureError]: [CaptureError.InvalidConfig] when the config cannot
     *  describe a clip, or [CaptureError.SimulationFailure] when the runtime or
     *  trace assembly throws.
     */
    fun capture(config: CaptureConfig): Result<VoxelTrace> {
        validate(config)?.let { return Result.failure(it) }

        val frames =
            try {
                captureFrames(config)
            } catch (error: Throwable) {
                return Result.failure(
                    CaptureError.SimulationFailure("Simulation failed during capture", error),
                )
            }

        return try {
            Result.success(
                VoxelTrace.fromFrames(
                    frames = frames,
                    fps = config.fps,
                    phosphorVersion = config.phosphorVersion,
                    createdAtEpochMs = config.createdAtEpochMs,
                    seed = config.seed,
                    paramSnapshot = snapshotParams(config),
                    segments = config.segments,
                ),
            )
        } catch (error: Throwable) {
            Result.failure(CaptureError.SimulationFailure("Trace assembly failed", error))
        }
    }

    private fun validate(config: CaptureConfig): CaptureError? {
        if (config.fps <= 0) {
            return CaptureError.InvalidConfig("fps must be > 0, was ${config.fps}")
        }
        if (config.durationMs <= 0L) {
            return CaptureError.InvalidConfig("durationMs must be > 0, was ${config.durationMs}")
        }
        val frameCount = config.frameCount
        if (frameCount <= 0) {
            return CaptureError.InvalidConfig(
                "durationMs=${config.durationMs} at fps=${config.fps} yields no frames",
            )
        }
        config.segments.forEach { segment ->
            if (segment.endFrame >= frameCount) {
                return CaptureError.InvalidConfig(
                    "Segment '${segment.name}' endFrame ${segment.endFrame} is outside the " +
                        "$frameCount-frame clip",
                )
            }
        }
        config.cues.forEachIndexed { index, cue ->
            if (cue.atFrame < 0 || cue.atFrame >= frameCount) {
                return CaptureError.InvalidConfig(
                    "Cue $index atFrame ${cue.atFrame} is outside the $frameCount-frame clip",
                )
            }
        }
        return null
    }

    private fun captureFrames(config: CaptureConfig): List<VoxelFrame> {
        val runtime =
            CognitiveSceneRuntime(
                SceneConfiguration(
                    width = HEADLESS_SUBSTRATE_SIZE,
                    height = HEADLESS_SUBSTRATE_SIZE,
                    enableWaveform = false,
                    enableParticles = false,
                    enableFlow = false,
                    enableEmitters = false,
                    enableCamera = false,
                    enableAtmosphere = true,
                    initialAtmosphere = config.params.atmosphere,
                    seed = config.seed,
                ),
            )
        val builder =
            VoxelFrameBuilder(
                initialResolution = config.params.atmosphere.resolution,
                config = config.params.renderConfig,
            )
        // groupBy preserves list order within each frame bucket, so cues on the
        // same frame apply in declaration order.
        val cuesByFrame = config.cues.groupBy(AtmosphereCue::atFrame)
        val dt = 1f / config.fps
        val frameCount = config.frameCount

        val frames = ArrayList<VoxelFrame>(frameCount)
        for (frameIndex in 0 until frameCount) {
            cuesByFrame[frameIndex]?.forEach { cue -> runtime.setAtmosphere(cue.atmosphere) }
            val snapshot = runtime.update(dt)
            frames += builder.build(snapshot, dt)
        }
        return frames
    }

    /**
     * Flatten the tuning inputs into a key/value map with a fixed insertion
     * order, so the CBOR encoding of [VoxelTrace.paramSnapshot] is stable.
     */
    internal fun snapshotParams(config: CaptureConfig): Map<String, String> {
        val snapshot = linkedMapOf<String, String>()
        snapshot["capture.fps"] = config.fps.toString()
        snapshot["capture.durationMs"] = config.durationMs.toString()
        snapshot["capture.frameCount"] = config.frameCount.toString()
        putAtmosphere(snapshot, "atmosphere", config.params.atmosphere)
        putRenderConfig(snapshot, "render", config.params.renderConfig)
        config.cues.forEachIndexed { index, cue ->
            snapshot["cue.$index.atFrame"] = cue.atFrame.toString()
            putAtmosphere(snapshot, "cue.$index.atmosphere", cue.atmosphere)
        }
        return snapshot
    }

    private fun putAtmosphere(
        into: MutableMap<String, String>,
        prefix: String,
        state: AtmosphereState,
    ) {
        into["$prefix.primaryHue"] = state.primaryHue.toString()
        into["$prefix.secondaryHue"] = state.secondaryHue.toString()
        into["$prefix.saturation"] = state.saturation.toString()
        into["$prefix.lightness"] = state.lightness.toString()
        into["$prefix.bipolarStrength"] = state.bipolarStrength.toString()
        into["$prefix.pattern"] = state.pattern.name
        into["$prefix.patternSpeed"] = state.patternSpeed.toString()
        into["$prefix.pulseAmplitude"] = state.pulseAmplitude.toString()
        into["$prefix.pulseFrequency"] = state.pulseFrequency.toString()
        into["$prefix.rotationY"] = state.rotationY.toString()
        into["$prefix.rotationX"] = state.rotationX.toString()
        into["$prefix.surfaceBump"] = state.surfaceBump.toString()
        into["$prefix.noise"] = state.noise.toString()
        into["$prefix.voxelGap"] = state.voxelGap.toString()
        into["$prefix.ySquash"] = state.ySquash.toString()
        into["$prefix.resolution"] = state.resolution.toString()
        into["$prefix.glow"] = state.glow.toString()
    }

    private fun putRenderConfig(
        into: MutableMap<String, String>,
        prefix: String,
        renderConfig: LumosRenderConfig,
    ) {
        into["$prefix.globalYSquashOverride"] = renderConfig.globalYSquashOverride?.toString() ?: "null"
        into["$prefix.enableGlyphCarving"] = renderConfig.enableGlyphCarving.toString()
        into["$prefix.omitBelowScale"] = renderConfig.omitBelowScale.toString()
    }

    /**
     * Substrate grid size for the headless runtime. The substrate never reaches a
     * `VoxelFrame`, so the smallest legal grid keeps per-frame cost minimal.
     */
    private const val HEADLESS_SUBSTRATE_SIZE: Int = 1
}
