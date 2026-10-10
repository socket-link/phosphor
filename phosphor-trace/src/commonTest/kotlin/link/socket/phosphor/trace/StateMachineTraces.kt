package link.socket.phosphor.trace

import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame

/**
 * Synthetic recordings for [TraceStateMachine], built so continuity is a
 * measurable property rather than something only an eye can judge.
 *
 * Every frame carries one designed scalar — its **signature** — in every cell's
 * `x`, and the signatures are laid out so that a pop-free playback is exactly a
 * playback whose signature never jumps:
 *
 * - A loop segment for state `k` holds the constant `k`, so its frames are
 *   identical and its seam wraps with a step of zero.
 * - A transition `a→b` of [TRANSITION_FRAMES] frames walks from `a` to `b` in
 *   equal steps across both endpoints — its first frame holds exactly `a`'s loop
 *   value and its last exactly `b`'s, the way an authored transition is recorded,
 *   so entering and leaving it both step by zero.
 * - A crossfade between two loops blends constants, so its signature is the
 *   weight itself, scaled: it steps by `|b - a| / fadeSteps` per recorded frame.
 *
 * The largest step any correct playback can produce is therefore bounded, and
 * [continuityEnvelope] is that bound. A sequencing bug — cutting a transition
 * short, switching mid-loop without a fade, blending unpaired heads — shows up
 * as a signature step past it.
 *
 * These are hand-built rather than captured: a fuzz test needs the authored
 * *topology*, not the authored assets. At resolution 10 the full five-state set
 * is over a hundred megabytes of raw float channels; the same twenty-five
 * segment graph at resolution 2 is a few hundred kilobytes and exercises the
 * blend identically.
 */
internal object StateMachineTraces {
    /** One recorded frame spans exactly 100 ms, which keeps the arithmetic readable. */
    const val FPS: Int = 10

    /** Frames in every loop segment. */
    const val LOOP_FRAMES: Int = 6

    /** Frames in every recorded transition segment. */
    const val TRANSITION_FRAMES: Int = 4

    /** Cells per frame — enough to exercise the pairwise blend, few enough to stay a unit test. */
    const val CELLS_PER_FRAME: Int = 3

    /** The minimal topology: three loops, two recorded transitions, four pairs with none. */
    val THREE_STATES: List<String> = listOf("idle", "thinking", "uncertain")

    /** The authored topology: the five Lumos atmospheres. */
    val FIVE_STATES: List<String> = listOf("idle", "listening", "thinking", "uncertain", "ready")

    /**
     * Three loops plus `idle→thinking` and `thinking→idle` only.
     *
     * Every pair touching `uncertain` has no recorded transition, so this is the
     * fixture that drives the fallback policies.
     */
    fun threeState(): VoxelTrace =
        build(
            states = THREE_STATES,
            pairs = listOf("idle" to "thinking", "thinking" to "idle"),
        )

    /**
     * Five loops plus all twenty directed pairs — the shape the authored asset
     * set has, where the explicit-segment path covers every request and the
     * policies are dead weight.
     */
    fun authoredFiveState(): VoxelTrace =
        build(
            states = FIVE_STATES,
            pairs = FIVE_STATES.flatMap { from -> FIVE_STATES.mapNotNull { to -> (from to to).takeIf { from != to } } },
        )

    /** The designed scalar [frame] carries, blended frames included. */
    fun signatureOf(frame: VoxelFrame): Float = frame.cells.first().x

    /**
     * Largest signature step a correct playback of [states] can produce.
     *
     * The widest pair of loop values divided by the shortest ramp between them —
     * a recorded transition's step count, or a crossfade's quantized step count
     * when one is configured. Comfortably below the smallest step a real pop
     * produces, which is the full gap between two neighbouring loop values.
     */
    fun continuityEnvelope(
        states: List<String>,
        fadeSteps: Int? = null,
    ): Float {
        val widestGap = (states.size - 1).toFloat()
        val transitionRamp = TRANSITION_FRAMES - 1
        val shortestRamp = minOf(transitionRamp, fadeSteps ?: transitionRamp)
        return widestGap / shortestRamp
    }

    /** How many weight steps a [TransitionPolicy.Crossfade] of [durationMs] resolves to at [FPS]. */
    fun fadeSteps(durationMs: Long): Int = (durationMs * FPS / 1_000L).toInt().coerceAtLeast(1)

    private fun build(
        states: List<String>,
        pairs: List<Pair<String, String>>,
    ): VoxelTrace {
        val frames = ArrayList<VoxelFrame>()
        val segments = ArrayList<TraceSegment>()

        states.forEachIndexed { index, name ->
            val start = frames.size
            repeat(LOOP_FRAMES) { frames += frameOf(frames.size, index.toFloat()) }
            segments += TraceSegment(name, start, frames.size - 1, loop = true)
        }

        pairs.forEach { (from, to) ->
            val a = states.indexOf(from).toFloat()
            val b = states.indexOf(to).toFloat()
            val start = frames.size
            val ramp = (TRANSITION_FRAMES - 1).toFloat()
            for (step in 0 until TRANSITION_FRAMES) {
                // Inclusive of both endpoints: frame 0 holds a, the last holds b.
                frames += frameOf(frames.size, a + (b - a) * step / ramp)
            }
            segments +=
                TraceSegment(
                    name = TraceStateMachine.transitionName(from, to),
                    startFrame = start,
                    endFrame = frames.size - 1,
                    loop = false,
                )
        }

        return VoxelTrace.fromFrames(
            frames = frames,
            fps = FPS,
            phosphorVersion = "test",
            createdAtEpochMs = 0L,
            segments = segments,
        )
    }

    private fun frameOf(
        tick: Int,
        signature: Float,
    ): VoxelFrame =
        VoxelFrame(
            tick = tick.toLong(),
            timestampEpochMillis = tick * (1_000L / FPS),
            resolution = 2,
            cells =
                List(CELLS_PER_FRAME) { cell ->
                    VoxelCell(
                        x = signature,
                        y = cell.toFloat(),
                        z = 0f,
                        scale = 1f,
                        red = 0.2f,
                        green = 0.3f,
                        blue = 0.4f,
                    )
                },
            // Rotations held at zero so the shortest-arc ambient lerp cannot
            // contribute to a signature step; FrameBlendTest covers it directly.
            ambient = VoxelAmbient(0.1f, 0.2f, 0.3f, 0.4f, 0f, 0f, 0f),
        )
}
