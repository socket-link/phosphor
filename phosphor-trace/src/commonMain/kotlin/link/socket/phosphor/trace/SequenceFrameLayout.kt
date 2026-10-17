package link.socket.phosphor.trace

import link.socket.phosphor.lumos.LumosSequence
import link.socket.phosphor.lumos.LumosSequencePlayer

/**
 * Where each step of a `LumosSequence` falls in a fixed-step capture.
 *
 * @property frameCount Frames in one pass of the sequence at the rate it was laid out for.
 * @property segments One [TraceSegment] per step, named as the step is and carrying its `loop`,
 *  or null for a step so short no frame falls in it.
 */
class SequenceFrameLayout(
    val frameCount: Int,
    val segments: List<TraceSegment?>,
)

/**
 * Lay this sequence out at [fps]: the frames of one pass and the segment each step becomes.
 *
 * Frame `k` is the sequence at `k / fps`, found the way `SignalGenerator.play` finds it — a
 * [LumosSequencePlayer] advanced by `1 / fps` once per frame, so the layout and the capture
 * cannot disagree about which frame a step starts on, `Float` accumulation included. The
 * sequence's own `loop` is ignored: this is one pass.
 *
 * @throws IllegalArgumentException when [fps] is not positive or `1 / fps` exceeds the player's
 *  step cap ([CaptureConfig.MIN_SEQUENCE_FPS]).
 */
fun LumosSequence.frameLayout(fps: Int): SequenceFrameLayout {
    require(fps >= CaptureConfig.MIN_SEQUENCE_FPS) {
        "A sequence lays out at fps >= ${CaptureConfig.MIN_SEQUENCE_FPS}, was $fps"
    }
    val dt = 1f / fps
    val onePass = if (loop) copy(loop = false) else this
    val player = LumosSequencePlayer(onePass)
    player.connect()
    val starts = IntArray(steps.size) { -1 }
    var frame = 0
    while (true) {
        val snapshot = player.snapshot
        if (snapshot.ended) break
        if (starts[snapshot.stepIndex] < 0) starts[snapshot.stepIndex] = frame
        frame++
        player.advance(dt)
    }
    val segments =
        List(steps.size) { index ->
            val start = starts[index]
            if (start < 0) {
                null
            } else {
                val next = (index + 1 until steps.size).firstOrNull { starts[it] >= 0 }
                val end = if (next == null) frame - 1 else starts[next] - 1
                TraceSegment(steps[index].name, start, end, steps[index].loop)
            }
        }
    return SequenceFrameLayout(frameCount = frame, segments = segments)
}
