package link.socket.phosphor.trace

import kotlin.math.PI
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelFrame

/**
 * Keeps one read head's orb rotation continuous across every seam it crosses.
 *
 * A recorded rotation only ever advances, so no loop shorter than a full turn
 * — fourteen seconds and up for the canonical presets — can wrap it: replayed
 * as recorded, every iteration drops the orb back by the loop's whole travel.
 * Rotation is also the one channel that never touches the cells.
 * `VoxelFrameBuilder` records them unrotated and puts the angles in
 * `VoxelAmbient`, which the surface adapter applies at projection. So instead of
 * being replayed it can be *carried*: each iteration, and each segment a head
 * enters, is offset so it starts where the last frame left off, one ordinary
 * step on. The cells are untouched and shared with the recorded frame.
 *
 * A carried frame is cached against its source frame and loop iteration, so a
 * held frame is the identical instance on every refresh, and a frame whose
 * offset is exactly zero is the recorded instance itself.
 *
 * Single-threaded, like the [TraceStateMachine] that owns it.
 */
internal class RotationCarry(
    private val trace: VoxelTrace,
    private val frameNanos: Long,
    private val enabled: Boolean,
) {
    private var segment: TraceSegment? = null
    private val recordedStart = FloatArray(AXES)

    /** Offset applied on entry, so the segment's first frame shows the rotation it was handed. */
    private val base = FloatArray(AXES)

    /** Offset each further loop iteration adds: last frame plus one step, minus the first. */
    private val loopCarry = FloatArray(AXES)

    /** The segment's own final step, used to carry on past a one-shot's last frame. */
    private val lastStep = FloatArray(AXES)

    private var cachedSource: VoxelFrame? = null
    private var cachedIterations: Long = -1L
    private var cached: VoxelFrame? = null

    /**
     * Start carrying [entered] so its first frame shows [rotation] (X, Y, Z in
     * radians), or exactly as recorded when [rotation] is null.
     */
    fun enter(
        entered: TraceSegment,
        rotation: FloatArray?,
    ) {
        segment = entered
        val start = trace.ambient[entered.startFrame]
        val end = trace.ambient[entered.endFrame]
        val beforeEnd = trace.ambient[(entered.endFrame - 1).coerceAtLeast(entered.startFrame)]
        for (axis in 0 until AXES) {
            recordedStart[axis] = start.axis(axis)
            lastStep[axis] = shortestArc(beforeEnd.axis(axis), end.axis(axis))
            loopCarry[axis] = shortestArc(start.axis(axis), end.axis(axis) + lastStep[axis])
            base[axis] = if (rotation == null) 0f else shortestArc(recordedStart[axis], rotation[axis])
        }
        cachedSource = null
        cachedIterations = -1L
        cached = null
    }

    /**
     * Write into [into] the rotation this head shows [nanos] into its segment.
     *
     * Past the end of a one-shot the rotation keeps moving at the segment's
     * final rate, so a head handed off exactly as a one-shot finishes passes on
     * the angle one step after its last frame — the angle the next frame needs.
     */
    fun rotationAt(
        nanos: Long,
        into: FloatArray,
    ) {
        val active = checkNotNull(segment) { "RotationCarry has not entered a segment" }
        val steps = stepsAt(nanos)
        val span = active.frameCount.toLong()
        val within: Long
        val iterations: Long
        val overrun: Long
        if (active.loop) {
            within = steps % span
            iterations = steps / span
            overrun = 0L
        } else {
            within = steps.coerceAtMost(span - 1L)
            iterations = 0L
            overrun = (steps - span + 1L).coerceAtLeast(0L)
        }
        val recorded = trace.ambient[active.startFrame + within.toInt()]
        for (axis in 0 until AXES) {
            into[axis] =
                wrap(
                    recorded.axis(axis).toDouble() +
                        offset(axis, iterations) +
                        overrun.toDouble() * lastStep[axis],
                )
        }
    }

    /** [frame], recorded [nanos] into the segment, with its rotation carried. */
    fun apply(
        frame: VoxelFrame,
        nanos: Long,
    ): VoxelFrame {
        if (!enabled) return frame
        val active = checkNotNull(segment) { "RotationCarry has not entered a segment" }
        val iterations = if (active.loop) stepsAt(nanos) / active.frameCount else 0L

        if (frame === cachedSource && iterations == cachedIterations) return checkNotNull(cached)

        val x = offset(0, iterations)
        val y = offset(1, iterations)
        val z = offset(2, iterations)
        val carried =
            if (x == 0.0 && y == 0.0 && z == 0.0) {
                frame
            } else {
                val ambient = frame.ambient
                frame.copy(
                    ambient =
                        ambient.copy(
                            orbRotationX = wrap(ambient.orbRotationX + x),
                            orbRotationY = wrap(ambient.orbRotationY + y),
                            orbRotationZ = wrap(ambient.orbRotationZ + z),
                        ),
                )
            }
        cachedSource = frame
        cachedIterations = iterations
        cached = carried
        return carried
    }

    private fun stepsAt(nanos: Long): Long = if (nanos <= 0L) 0L else nanos / frameNanos

    /**
     * Total offset on [axis] after [iterations] wraps. In doubles and reduced
     * modulo a turn, so an orb left looping for hours does not lose precision.
     */
    private fun offset(
        axis: Int,
        iterations: Long,
    ): Double = (base[axis].toDouble() + iterations.toDouble() * loopCarry[axis]) % TWO_PI

    private fun VoxelAmbient.axis(axis: Int): Float =
        when (axis) {
            0 -> orbRotationX
            1 -> orbRotationY
            else -> orbRotationZ
        }

    private companion object {
        const val AXES: Int = 3
        const val TWO_PI: Double = 2.0 * PI

        /** Signed angle from [from] to [to], in `-π..π`. */
        fun shortestArc(
            from: Float,
            to: Float,
        ): Float {
            var arc = (to.toDouble() - from) % TWO_PI
            if (arc > PI) arc -= TWO_PI
            if (arc < -PI) arc += TWO_PI
            return arc.toFloat()
        }

        /** [angle] in `0..2π`, the range `VoxelFrameBuilder` records. */
        fun wrap(angle: Double): Float {
            var wrapped = angle % TWO_PI
            if (wrapped < 0.0) wrapped += TWO_PI
            val narrowed = wrapped.toFloat()
            return if (narrowed >= TWO_PI.toFloat()) 0f else narrowed
        }
    }
}
