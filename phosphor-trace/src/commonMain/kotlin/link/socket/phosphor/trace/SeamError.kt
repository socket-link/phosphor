package link.socket.phosphor.trace

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How far one channel of a recording jumps across a loop seam, beside how far
 * it moves on an ordinary frame.
 *
 * A seam is continuous when wrapping from the last frame to the first looks like
 * one more step of the motion already on screen, so the jump is only meaningful
 * as a multiple of that motion: a pulse moving a voxel 0.01 per frame can hide a
 * 0.01 seam, and a hue that never changes cannot hide any.
 *
 * @property jump Distance from the segment's last frame to its first, in this channel's units.
 *  [Float.POSITIVE_INFINITY] when the two frames cannot be paired cell for cell.
 * @property medianStep Median distance between consecutive frames inside the segment, in the same units.
 */
data class SeamChannel(
    val jump: Float,
    val medianStep: Float,
) {
    /**
     * [jump] in units of [medianStep].
     *
     * About `1` is a seam indistinguishable from an ordinary frame step; `10` is
     * a ten-frame jump. Below `1` is a brief hold rather than a pop. A channel
     * that never moves inside the segment reads `0` when the seam holds it still
     * too, and [Float.POSITIVE_INFINITY] when the seam alone moves it.
     */
    val ratio: Float
        get() =
            when {
                jump <= 0f -> 0f
                medianStep <= 0f -> Float.POSITIVE_INFINITY
                else -> jump / medianStep
            }
}

/**
 * The discontinuity a [TraceSegment] shows when it wraps, channel by channel.
 *
 * Nothing in capture makes a `loop` segment wrap. [Oscilloscope] records
 * whatever the simulation produced between two frame indices, and the phase
 * accumulators driving that simulation — breath pulse, pattern, surface bump,
 * orb rotation — run at rates that do not share a period. [TracePlayer] wraps
 * the frame *index* with no gap; this measures what the *content* does at the
 * same moment, so a caller can pick a loop length or a transition policy from a
 * number instead of an assumption.
 *
 * Cell channels average over every voxel: a cell's distance between two frames,
 * then the mean of those across the frame. Rotations are compared on the
 * shortest arc, as [FrameBlend] interpolates them, so a rotation that wraps 2π
 * between the two frames is not counted as a full turn.
 *
 * A decoded format-v2 trace measures the same as the capture it came from.
 * Rounding to a quantum stalls a slow cell on some frames and ticks it a whole
 * quantum on others. Averaged over the frame, those come back to about the true
 * step, as long as different cells tick on different frames. For the canonical
 * presets that holds even at 60 fps, where idle's colour moves about half an
 * RGB8 quantum per frame: no loop's [contentRatio] moved by more than 0.13
 * (PHO-50). The average breaks down for a field that moves in lockstep below a
 * quantum. Every cell then ticks on the same frames, most steps change nothing,
 * and the median step reads `0`. A seam that lands on a tick then reads
 * [Float.POSITIVE_INFINITY].
 *
 * Get one from [seamError] or [seamErrors], both extensions on [VoxelTrace].
 *
 * @property segment The segment measured.
 * @property position Mean per-cell `(x, y, z)` distance.
 * @property scale Mean per-cell scale difference.
 * @property color Mean per-cell `(red, green, blue, alpha)` distance; a null alpha counts as `1`.
 * @property glow Ambient glow `(red, green, blue, intensity)` distance — the halo.
 * @property rotation Ambient orb rotation distance across X, Y, and Z, in radians on the shortest arc.
 */
data class SeamError(
    val segment: TraceSegment,
    val position: SeamChannel,
    val scale: SeamChannel,
    val color: SeamChannel,
    val glow: SeamChannel,
    val rotation: SeamChannel,
) {
    /** Every channel, named, in declaration order. */
    val channels: List<Pair<String, SeamChannel>>
        get() =
            listOf(
                "position" to position,
                "scale" to scale,
                "color" to color,
                "glow" to glow,
                "rotation" to rotation,
            )

    /** The worst channel's [SeamChannel.ratio]: the seam is as visible as its most visible channel. */
    val ratio: Float get() = maxOf(contentRatio, rotation.ratio)

    /**
     * The worst channel the recording itself can fix: every one but [rotation].
     *
     * Rotation only ever advances, so a loop shorter than a full turn always
     * jumps there, by its whole travel — `CaptureConfig.seamBlendFrames` cannot
     * give it back, and [TraceStateMachine] carries it across the seam at
     * playback instead. Behind a state machine this is the seam a viewer sees,
     * and it is the number to hold to a ceiling.
     */
    val contentRatio: Float get() = maxOf(position.ratio, scale.ratio, color.ratio, glow.ratio)
}

/**
 * Measure how far [segment] jumps when it wraps from its last frame back to its first.
 *
 * Measured whether or not [segment] loops — a one-shot has a seam too, it is just
 * never played across. Steps inside the segment whose two frames carry different
 * cell counts (a capture with `LumosRenderConfig.omitBelowScale > 0`) are left out
 * of the cell channels' medians; if the seam's own two frames differ, the cell
 * channels report an unbounded jump rather than pairing different voxels.
 *
 * @throws IllegalArgumentException when [segment] reaches past the end of the trace.
 */
fun VoxelTrace.seamError(segment: TraceSegment): SeamError {
    require(segment.endFrame < frameCount) {
        "Segment '${segment.name}' endFrame ${segment.endFrame} is outside the trace's $frameCount frames"
    }

    val offsets = IntArray(frameCount)
    for (frame in 1 until frameCount) {
        offsets[frame] = offsets[frame - 1] + cellCounts[frame - 1]
    }

    val stepCount = segment.frameCount - 1
    val positionSteps = FloatArray(stepCount)
    val scaleSteps = FloatArray(stepCount)
    val colorSteps = FloatArray(stepCount)
    val glowSteps = FloatArray(stepCount)
    val rotationSteps = FloatArray(stepCount)
    var pairedSteps = 0

    for (step in 0 until stepCount) {
        val from = segment.startFrame + step
        val delta = frameDelta(from, from + 1, offsets)
        if (delta.paired) {
            positionSteps[pairedSteps] = delta.position
            scaleSteps[pairedSteps] = delta.scale
            colorSteps[pairedSteps] = delta.color
            pairedSteps++
        }
        glowSteps[step] = delta.glow
        rotationSteps[step] = delta.rotation
    }

    val seam = frameDelta(segment.endFrame, segment.startFrame, offsets)
    val unpaired = Float.POSITIVE_INFINITY
    return SeamError(
        segment = segment,
        position = SeamChannel(if (seam.paired) seam.position else unpaired, median(positionSteps, pairedSteps)),
        scale = SeamChannel(if (seam.paired) seam.scale else unpaired, median(scaleSteps, pairedSteps)),
        color = SeamChannel(if (seam.paired) seam.color else unpaired, median(colorSteps, pairedSteps)),
        glow = SeamChannel(seam.glow, median(glowSteps, stepCount)),
        rotation = SeamChannel(seam.rotation, median(rotationSteps, stepCount)),
    )
}

/** [seamError] for every `loop` segment in the trace, in declaration order. */
fun VoxelTrace.seamErrors(): List<SeamError> = segments.filter(TraceSegment::loop).map(::seamError)

/** Per-channel distance between two recorded frames. Cell channels are zero when [paired] is false. */
private class FrameDelta(
    val paired: Boolean,
    val position: Float,
    val scale: Float,
    val color: Float,
    val glow: Float,
    val rotation: Float,
)

private fun VoxelTrace.frameDelta(
    from: Int,
    to: Int,
    offsets: IntArray,
): FrameDelta {
    val count = cellCounts[from]
    val paired = count == cellCounts[to]

    var position = 0.0
    var scale = 0.0
    var color = 0.0
    if (paired && count > 0) {
        val a = offsets[from]
        val b = offsets[to]
        for (i in 0 until count) {
            position += distance(cellX[a + i] - cellX[b + i], cellY[a + i] - cellY[b + i], cellZ[a + i] - cellZ[b + i])
            scale += abs(cellScale[a + i] - cellScale[b + i])
            color +=
                distance(
                    cellRed[a + i] - cellRed[b + i],
                    cellGreen[a + i] - cellGreen[b + i],
                    cellBlue[a + i] - cellBlue[b + i],
                    alphaOf(cellAlpha[a + i]) - alphaOf(cellAlpha[b + i]),
                )
        }
        position /= count
        scale /= count
        color /= count
    }

    val ambientFrom = ambient[from]
    val ambientTo = ambient[to]
    return FrameDelta(
        paired = paired,
        position = position.toFloat(),
        scale = scale.toFloat(),
        color = color.toFloat(),
        glow =
            distance(
                ambientFrom.glowRed - ambientTo.glowRed,
                ambientFrom.glowGreen - ambientTo.glowGreen,
                ambientFrom.glowBlue - ambientTo.glowBlue,
                ambientFrom.glowIntensity - ambientTo.glowIntensity,
            ),
        rotation =
            distance(
                shortestArc(ambientFrom.orbRotationX, ambientTo.orbRotationX),
                shortestArc(ambientFrom.orbRotationY, ambientTo.orbRotationY),
                shortestArc(ambientFrom.orbRotationZ, ambientTo.orbRotationZ),
            ),
    )
}

private const val TWO_PI: Float = (2.0 * PI).toFloat()
private const val HALF_TURN: Float = PI.toFloat()

/** Signed angle from [from] to [to] in `-π..π`, the way [FrameBlend] interpolates rotations. */
private fun shortestArc(
    from: Float,
    to: Float,
): Float {
    var arc = (to - from) % TWO_PI
    if (arc > HALF_TURN) arc -= TWO_PI
    if (arc < -HALF_TURN) arc += TWO_PI
    return arc
}

/** The trace encodes a null alpha as `NaN`; a null alpha renders as `1`. */
private fun alphaOf(encoded: Float): Float = if (encoded.isNaN()) 1f else encoded

private fun distance(
    a: Float,
    b: Float,
    c: Float,
    d: Float = 0f,
): Float = sqrt(a * a + b * b + c * c + d * d)

private fun median(
    values: FloatArray,
    count: Int,
): Float {
    if (count == 0) return 0f
    val sorted = values.copyOf(count).also { it.sort() }
    val middle = count / 2
    return if (count % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
}
