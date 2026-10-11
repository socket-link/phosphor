package link.socket.phosphor.trace

import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AtmosphereState

/**
 * Headless recorder for `VoxelFrame` streams.
 *
 * An oscilloscope captures a signal over time as a trace. This one drives a
 * [SignalGenerator] with no renderer and no UI clock, stepping the simulation at
 * exactly `1f / fps` seconds per frame, and assembles the frames into a
 * [VoxelTrace]. [TracePlayer] plays the result back.
 *
 * Determinism contract: [capture] reads nothing from the environment. Equal
 * [CaptureConfig]s produce equal traces, and `TraceCodec.encode` of those traces
 * produces identical bytes. Only the atmosphere subsystem is enabled on the
 * runtime; particles, flow, waveform, emitters, and camera do not contribute to
 * a `VoxelFrame` and are switched off so capture stays cheap.
 *
 * ### Loop seams
 *
 * By default capture does not make a `loop` segment wrap. It records whatever
 * the simulation produced between the segment's two frames, and the breath
 * pulse, pattern, surface bump, and orb rotation advance at rates that share no
 * period, so the last frame of a loop is generally not one step before its
 * first. Call [seamErrors] on the result to see how far each loop jumps.
 *
 * [CaptureConfig.seamBlendFrames] bakes the seam out. Over the last *B* frames
 * of every `loop` segment, each cell fades toward the frame that preceded the
 * segment's start by the same distance, so the final frame *is* the one before
 * the first and wrapping reads as one more step. The fade reads from the
 * unblended capture, so loops whose windows touch do not feed each other. It
 * blends cells and the halo but **not** orb rotation: rotation is monotonic, a
 * short loop cannot give it back, and blending it would spin the orb backwards
 * through the window. [TraceStateMachine] carries rotation across the seam at
 * playback instead.
 *
 * The frames a loop fades toward are the *B* recorded before it. When a loop
 * starts closer than *B* frames to the start of the clip, capture first steps
 * the simulation through enough hidden warm-up frames to supply them, which
 * shifts every recorded frame's `tick` and timestamp by that many steps. When a
 * transition precedes a loop, those frames belong to the transition: give it
 * room to settle before the loop starts, or the loop's tail leans toward
 * whatever the transition was still carrying.
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
        if (config.seamBlendFrames < 0) {
            return CaptureError.InvalidConfig("seamBlendFrames must be >= 0, was ${config.seamBlendFrames}")
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
        return validateSeamBlend(config)
    }

    private fun validateSeamBlend(config: CaptureConfig): CaptureError? {
        val blendFrames = config.seamBlendFrames
        if (blendFrames == 0) return null
        if (config.params.renderConfig.omitBelowScale > 0f) {
            // FrameBlend pairs cells by index, which omitBelowScale breaks; it
            // would cut halfway through the window instead of fading.
            return CaptureError.InvalidConfig(
                "seamBlendFrames needs every frame to carry every voxel, but omitBelowScale is " +
                    "${config.params.renderConfig.omitBelowScale}",
            )
        }
        config.segments.filter(TraceSegment::loop).forEach { segment ->
            if (blendFrames >= segment.frameCount) {
                return CaptureError.InvalidConfig(
                    "seamBlendFrames $blendFrames must be shorter than loop segment '${segment.name}', " +
                        "which is ${segment.frameCount} frames",
                )
            }
        }
        return null
    }

    private fun captureFrames(config: CaptureConfig): List<VoxelFrame> {
        // The same generator LumosSource.Live drives, stepped by a loop instead
        // of a clock. Capture and live playback share this path on purpose: it
        // is what makes a replay frame-identical to the simulation it recorded
        // rather than merely similar to it.
        val generator =
            SignalGenerator(
                configuration =
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
                renderConfig = config.params.renderConfig,
                fps = config.fps,
            )
        // groupBy preserves list order within each frame bucket, so cues on the
        // same frame apply in declaration order.
        val cuesByFrame = config.cues.groupBy(AtmosphereCue::atFrame)
        val frameCount = config.frameCount
        val warmUp = warmUpFrames(config)

        // Warm-up frames come first and are never recorded; they exist only
        // as the frames a loop at the head of the clip fades toward.
        val frames = ArrayList<VoxelFrame>(warmUp + frameCount)
        repeat(warmUp) { frames += generator.nextFrame() }
        for (frameIndex in 0 until frameCount) {
            cuesByFrame[frameIndex]?.forEach { cue -> generator.setAtmosphere(cue.atmosphere) }
            frames += generator.nextFrame()
        }
        return if (config.seamBlendFrames == 0) frames else bakeSeams(frames, warmUp, config)
    }

    /**
     * Hidden steps needed before frame 0 so every `loop` segment has
     * [CaptureConfig.seamBlendFrames] frames ahead of its start to fade toward.
     */
    private fun warmUpFrames(config: CaptureConfig): Int {
        val blendFrames = config.seamBlendFrames
        if (blendFrames == 0) return 0
        val earliestLoop = config.segments.filter(TraceSegment::loop).minOfOrNull(TraceSegment::startFrame) ?: return 0
        return (blendFrames - earliestLoop).coerceAtLeast(0)
    }

    /**
     * Fade the tail of every `loop` segment toward the frames before its start.
     *
     * [captured] holds [warmUp] hidden frames followed by the clip; the result
     * is the clip alone. Reads always come from [captured], so a blend never
     * reads another blend's output.
     */
    private fun bakeSeams(
        captured: List<VoxelFrame>,
        warmUp: Int,
        config: CaptureConfig,
    ): List<VoxelFrame> {
        val blendFrames = config.seamBlendFrames
        val baked = ArrayList(captured.subList(warmUp, captured.size))
        config.segments.filter(TraceSegment::loop).forEach { segment ->
            for (step in 0 until blendFrames) {
                // The window's last frame takes weight 1 and so becomes the frame
                // recorded just before the segment's first: the seam is one step.
                val frameIndex = segment.endFrame - blendFrames + 1 + step
                baked[frameIndex] =
                    bakeFrame(
                        recorded = captured[warmUp + frameIndex],
                        target = captured[warmUp + segment.startFrame - blendFrames + step],
                        weight = (step + 1).toFloat() / blendFrames,
                    )
            }
        }
        return baked
    }

    /**
     * [recorded]'s cells and halo faded toward [target]'s by [weight], keeping
     * everything that places the frame in time — tick, timestamp, glyph — and
     * the orb's rotation, which a loop cannot wrap and playback carries instead.
     */
    private fun bakeFrame(
        recorded: VoxelFrame,
        target: VoxelFrame,
        weight: Float,
    ): VoxelFrame {
        val blended = FrameBlend(recorded, target, weight).transduce()
        return recorded.copy(
            cells = blended.cells,
            ambient =
                blended.ambient.copy(
                    orbRotationX = recorded.ambient.orbRotationX,
                    orbRotationY = recorded.ambient.orbRotationY,
                    orbRotationZ = recorded.ambient.orbRotationZ,
                ),
        )
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
        // Only when set, so a capture without seam blending keeps its bytes.
        if (config.seamBlendFrames > 0) {
            snapshot["capture.seamBlendFrames"] = config.seamBlendFrames.toString()
            snapshot["capture.warmUpFrames"] = warmUpFrames(config).toString()
        }
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
