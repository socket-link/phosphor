package link.socket.phosphor.lumos

import kotlin.math.max
import kotlin.math.min
import link.socket.phosphor.lumos.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.lumos.projection.PixelLattice

/**
 * The clock and the held frame behind a pixel-surface orb, with no framework in them, so every
 * surface keeps the same rules about time and those rules can be tested on their own.
 *
 * Each [step] turns a frame time into the step its source takes: the gap since the last step,
 * capped at [LookClock.MAX_GAP_SECONDS] so a return from the background carries on rather than
 * jumping, times [LumosConfig.speed]. A step after [rejoin] first aligns a keyed source to its
 * look's clock ([LumosFrameSource.align]); any other step on which the source's
 * [LumosFrameSource.lookKey] names a different look than the last step's aligns that look alone
 * ([LumosFrameSource.alignLook]), so a look switch takes up its siblings' phase without the spin
 * snapping. A change of speed alone moves the orb to another clock key but aligns nothing. Under
 * reduced motion the source draws stills instead. The projection is reused until the size, the
 * dot size, the tilt or the lattice's resolution changes, and a frame the source hands back
 * unchanged is not projected again.
 *
 * Every call that can draw returns the frame it projected, or null when what is drawn did not
 * change, so a surface hands only new frames on: Compose's `LumosOrb` puts them in its state.
 * [frame] is always the one drawn last.
 *
 * Main-thread only, like the source it steps.
 *
 * @param source What the orb draws.
 * @param clock The look clocks this orb shares with its peers.
 * @param platformReducesMotion Whether the platform asks for reduced motion; read once a second
 *  while stepping. Defaults to [PlatformMotionPreference.reduceMotion].
 */
class OrbStepper(
    private val source: LumosFrameSource,
    private val clock: LookClock,
    private val platformReducesMotion: () -> Boolean = { PlatformMotionPreference.reduceMotion },
) {
    /** The frame drawn last; null until a frame has been projected. */
    var frame: LumosCanvasFrame? = null
        private set

    private var config: LumosConfig = LumosConfig()
    private var probe: FrameProbe = FrameProbe.Disabled
    private var widthPx: Int = 0
    private var heightPx: Int = 0

    private var heldFrame: VoxelFrame? = null
    private var lattice: PixelLattice? = null
    private var latticeProbe: FrameProbe? = null
    private var projectedWith: PixelLattice? = null

    private var lastFrameNanos: Long = NEVER
    private var rejoining: Boolean = true
    private var seenRevision: Long = 0L

    private var steppedLook: String? = null
    private var keyLook: String? = null
    private var keySpeed: Float = Float.NaN
    private var key: String? = null

    private var platformReduced: Boolean = platformReducesMotion()
    private var platformCheckedNanos: Long = NEVER

    /** Whether the orb holds still: its config asks, or the platform does. */
    val reducedMotion: Boolean get() = config.reducedMotion || platformReduced

    /**
     * Take a new config and probe; a change to how the orb is drawn re-projects the held frame.
     *
     * @return The re-projected frame, or null if nothing was drawn again.
     */
    fun update(
        config: LumosConfig,
        probe: FrameProbe,
    ): LumosCanvasFrame? {
        this.config = config
        this.probe = probe
        return reproject()
    }

    /**
     * The surface was laid out at [widthPx] × [heightPx] pixels.
     *
     * @return The held frame projected at the new size, or null if nothing was drawn again.
     */
    fun resize(
        widthPx: Int,
        heightPx: Int,
    ): LumosCanvasFrame? {
        this.widthPx = widthPx
        this.heightPx = heightPx
        return reproject()
    }

    /** The orb starts or resumes stepping: the next step aligns its source to its look's clock. */
    fun rejoin() {
        rejoining = true
    }

    /**
     * Step the source to [frameTimeNanos] and project what it hands back.
     *
     * @return The new projection, or null if the source handed back the frame already drawn or
     *  the surface has no size yet.
     */
    fun step(frameTimeNanos: Long): LumosCanvasFrame? {
        refreshPlatformPreference(frameTimeNanos)

        val speed = config.speed
        val gapSeconds =
            if (lastFrameNanos == NEVER) {
                0.0
            } else {
                ((frameTimeNanos - lastFrameNanos).coerceAtLeast(0L) / NANOS_PER_SECOND)
                    .coerceAtMost(LookClock.MAX_GAP_SECONDS)
            }
        lastFrameNanos = frameTimeNanos
        val dt = (gapSeconds * speed).toFloat()

        val look = source.lookKey
        val lookKey = keyFor(look, speed)
        if (lookKey != null) {
            val lookTime = clock.tick(lookKey, frameTimeNanos / NANOS_PER_SECOND, speed)
            // Aligned a step behind, so the step itself lands the orb where its peers are.
            if (rejoining) {
                source.align(lookTime - dt)
            } else if (look != steppedLook) {
                source.alignLook(lookTime - dt)
            }
        }
        steppedLook = look
        rejoining = false

        return show(if (reducedMotion) source.still(dt) else source.next(dt))
    }

    /**
     * Paused: draw a still if nothing has been drawn yet, and remember the source's revision.
     *
     * @return The still, if one was projected; otherwise null.
     */
    fun hold(): LumosCanvasFrame? {
        val drawn = if (heldFrame == null) show(source.still(0f)) else null
        seenRevision = source.revision
        return drawn
    }

    /**
     * Paused: redraw, once, if the source changed since it was last drawn.
     *
     * @return The redrawn still, or null if the source had not changed.
     */
    fun pollHeld(): LumosCanvasFrame? {
        if (source.revision == seenRevision) return null
        val drawn = show(source.still(0f))
        // Read after the still, which may itself move the revision by landing a transition.
        seenRevision = source.revision
        return drawn
    }

    private fun show(voxels: VoxelFrame): LumosCanvasFrame? {
        val target = latticeFor(voxels.resolution)
        if (voxels === heldFrame && target === projectedWith) return null
        heldFrame = voxels
        if (target == null) return null
        return projectWith(target, voxels)
    }

    private fun reproject(): LumosCanvasFrame? {
        val voxels = heldFrame ?: return null
        val target = latticeFor(voxels.resolution) ?: return null
        if (target === projectedWith) return null
        return projectWith(target, voxels)
    }

    private fun projectWith(
        target: PixelLattice,
        voxels: VoxelFrame,
    ): LumosCanvasFrame {
        val projected = target.project(voxels)
        frame = projected
        projectedWith = target
        return projected
    }

    // Half a lattice cell, as Socket and the trace equivalence tests size it: neighbouring voxels
    // sit 1 / resolution apart in [-1, 1], which is min(w, h) / (2 · resolution) pixels.
    private fun latticeFor(resolution: Int): PixelLattice? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val radius = min(widthPx, heightPx) / (4f * max(resolution, 1)) * config.dotSize
        val current = lattice
        if (current != null &&
            current.widthPx == widthPx &&
            current.heightPx == heightPx &&
            current.voxelRadiusPx == radius &&
            current.tiltDegrees == config.tiltDegrees &&
            latticeProbe === probe
        ) {
            return current
        }
        latticeProbe = probe
        return PixelLattice(
            widthPx = widthPx,
            heightPx = heightPx,
            voxelRadiusPx = radius,
            probe = probe,
            tiltDegrees = config.tiltDegrees,
        ).also { lattice = it }
    }

    private fun keyFor(
        look: String?,
        speed: Float,
    ): String? {
        if (look == null) return null
        if (look != keyLook || speed != keySpeed) {
            keyLook = look
            keySpeed = speed
            key = LookClock.key(look, speed)
        }
        return key
    }

    // The platform setting can change while the app runs; reading it once a second is plenty.
    private fun refreshPlatformPreference(frameTimeNanos: Long) {
        if (platformCheckedNanos != NEVER && frameTimeNanos - platformCheckedNanos < NANOS_PER_SECOND_LONG) return
        platformCheckedNanos = frameTimeNanos
        platformReduced = platformReducesMotion()
    }

    private companion object {
        const val NEVER: Long = Long.MIN_VALUE
        const val NANOS_PER_SECOND: Double = 1_000_000_000.0
        const val NANOS_PER_SECOND_LONG: Long = 1_000_000_000L
    }
}
