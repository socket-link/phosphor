package link.socket.phosphor.trace

import kotlin.math.PI
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame

/**
 * Two read heads over one recording, caught mid-crossfade, and the weight
 * between them.
 *
 * Two phosphor images summing on one screen is superposition, and this is the
 * state of that sum at one instant: [outgoing] is the frame the orb is leaving,
 * [incoming] the one it is arriving at, [weight] how far across it has travelled
 * — `0` is all outgoing, `1` all incoming.
 *
 * **The weight is linear and stays linear.** Transition easing — `eager`,
 * `overshoot`, `settled` — is applied by `AtmosphereChoreographer` at *capture*
 * time and is therefore already baked into the recorded frames. A state machine
 * selects and sequences segments; re-applying a curve here would ease an
 * already-eased motion and read as a stutter.
 *
 * [transduce] collapses the superposition into a single [VoxelFrame] upstream of
 * projection, which is the only place in the pipeline where the collapse is
 * well-defined. Both surface adapters compact their cell lists on the way to the
 * screen — `ComposeLattice` drops zero-scale and off-screen voxels per frame,
 * `CliLattice` keeps one z-winner per character cell and then quantizes scale
 * onto a ten-step luminance ramp — so neither can recover the index pairing a
 * pairwise blend needs, and the terminal ramp has no continuous interpolant at
 * all. The honest place to blend is while the channels are still parallel.
 *
 * @property outgoing Frame from the head being left behind.
 * @property incoming Frame from the head being arrived at.
 * @property weight Linear position between the two, clamped into `0..1` by [transduce].
 */
data class FrameBlend(
    val outgoing: VoxelFrame,
    val incoming: VoxelFrame,
    val weight: Float,
) {
    /**
     * Whether a pairwise blend is defined for this pair.
     *
     * Cell index `i` means the same lattice voxel in every frame of a recording
     * — the trace format requires a uniform resolution and `VoxelSphere` builds
     * its voxel list in a fixed order — *unless* the capture ran with
     * `LumosRenderConfig.omitBelowScale > 0`, which drops cells below a scale
     * threshold and so compacts each frame differently. Then cell `i` is a
     * different voxel on each side and the counts diverge.
     *
     * `paramSnapshot["render.omitBelowScale"]` carries the value a trace was
     * captured with, so a caller can check this before the first fade rather
     * than during it.
     */
    val isAligned: Boolean get() = outgoing.cells.size == incoming.cells.size

    /**
     * Collapse the superposition into one frame.
     *
     * A per-channel lerp across position, scale, and color; shortest-arc for the
     * ambient rotations, which wrap at 2π and would otherwise spin backwards
     * across the seam. The glyph name is discrete, so the heavier side wins
     * rather than interpolating.
     *
     * When [isAligned] is false this returns [outgoing] below weight `0.5` and
     * [incoming] at or above it — a cut. Blending unpaired cells would drag
     * voxels across the orb, which reads worse than the pop the crossfade exists
     * to prevent.
     */
    fun transduce(): VoxelFrame {
        val t = weight.coerceIn(0f, 1f)
        if (!isAligned) return if (t < 0.5f) outgoing else incoming

        val from = outgoing.cells
        val to = incoming.cells
        val cells = ArrayList<VoxelCell>(from.size)
        for (i in from.indices) {
            cells += blendCell(from[i], to[i], t)
        }

        return VoxelFrame(
            // A blended frame is not a recorded instant. It is stamped with the
            // incoming side so a consumer keying off tick sees time move forward.
            tick = incoming.tick,
            timestampEpochMillis = incoming.timestampEpochMillis,
            resolution = incoming.resolution,
            cells = cells,
            ambient = blendAmbient(outgoing.ambient, incoming.ambient, t),
            glyph = if (t < 0.5f) outgoing.glyph else incoming.glyph,
        )
    }

    private companion object {
        const val TWO_PI: Float = (2.0 * PI).toFloat()
        const val HALF_TURN: Float = PI.toFloat()

        fun blendCell(
            from: VoxelCell,
            to: VoxelCell,
            t: Float,
        ): VoxelCell =
            VoxelCell(
                x = lerp(from.x, to.x, t),
                y = lerp(from.y, to.y, t),
                z = lerp(from.z, to.z, t),
                scale = lerp(from.scale, to.scale, t),
                // sRGB rather than OKLab: the fade is the fallback path, its two
                // sides are a fraction of a second apart, and an OKLab round trip
                // per cell per frame costs more than the blend it improves.
                red = lerp(from.red, to.red, t),
                green = lerp(from.green, to.green, t),
                blue = lerp(from.blue, to.blue, t),
                alpha = blendAlpha(from.alpha, to.alpha, t),
            )

        fun blendAmbient(
            from: VoxelAmbient,
            to: VoxelAmbient,
            t: Float,
        ): VoxelAmbient =
            VoxelAmbient(
                glowRed = lerp(from.glowRed, to.glowRed, t),
                glowGreen = lerp(from.glowGreen, to.glowGreen, t),
                glowBlue = lerp(from.glowBlue, to.glowBlue, t),
                glowIntensity = lerp(from.glowIntensity, to.glowIntensity, t),
                orbRotationX = lerpAngle(from.orbRotationX, to.orbRotationX, t),
                orbRotationY = lerpAngle(from.orbRotationY, to.orbRotationY, t),
                orbRotationZ = lerpAngle(from.orbRotationZ, to.orbRotationZ, t),
            )

        /**
         * A null alpha means "use 1.0", so it blends as `1.0` against a value and
         * stays null only when both sides are null — which keeps a fade between
         * two alpha-free recordings allocation-identical to the frames it joins.
         */
        fun blendAlpha(
            from: Float?,
            to: Float?,
            t: Float,
        ): Float? = if (from == null && to == null) null else lerp(from ?: 1f, to ?: 1f, t)

        fun lerp(
            from: Float,
            to: Float,
            t: Float,
        ): Float = from + (to - from) * t

        /**
         * Shortest-arc interpolation for an angle in radians.
         *
         * `VoxelFrameBuilder` wraps its integrated rotations at 2π, so two heads
         * at different points in their loops can sit either side of the wrap. A
         * straight lerp from 6.2 rad to 0.1 rad takes the long way round and the
         * orb visibly spins backwards through the fade; the short way is 0.18 rad
         * forward. Mirrors `VoxelFrameBuilder.lerpHueShortest`, in radians.
         */
        fun lerpAngle(
            from: Float,
            to: Float,
            t: Float,
        ): Float {
            val raw = to - from
            val shortest =
                when {
                    raw > HALF_TURN -> raw - TWO_PI
                    raw < -HALF_TURN -> raw + TWO_PI
                    else -> raw
                }
            var angle = from + shortest * t
            while (angle < 0f) angle += TWO_PI
            while (angle >= TWO_PI) angle -= TWO_PI
            return angle
        }
    }
}
