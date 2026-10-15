package link.socket.phosphor.lumos.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.max
import kotlin.math.min
import link.socket.phosphor.lumos.LookClock
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.PlatformMotionPreference
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.compose.projection.ComposeLattice
import link.socket.phosphor.lumos.probe.FrameProbe

/**
 * The clock and the held frame behind [LumosOrb], kept apart from Compose's frame loop so its
 * rules about time can be tested on their own.
 *
 * Each [step] turns a frame time into the step its source takes: the gap since the last step,
 * capped at [LookClock.MAX_GAP_SECONDS] so a return from the background carries on rather than
 * jumping, times [LumosConfig.speed]. A step after [rejoin] first aligns a keyed source to its
 * look's clock. Under reduced motion the source draws stills instead. The projection is reused
 * until the size, the dot size, the tilt or the lattice's resolution changes, and a frame the
 * source hands back unchanged is not projected again.
 *
 * Main-thread only, like the source it steps.
 */
internal class LumosDriver(
    private val source: LumosFrameSource,
    private val clock: LookClock,
    private val platformReducesMotion: () -> Boolean = { PlatformMotionPreference.reduceMotion },
) {
    /** What the canvas draws; null until a frame has been projected. Compose state, so the canvas follows it. */
    var canvasFrame: LumosCanvasFrame? by mutableStateOf(null)
        private set

    private var config: LumosConfig = LumosConfig()
    private var probe: FrameProbe = FrameProbe.Disabled
    private var widthPx: Int = 0
    private var heightPx: Int = 0

    private var heldFrame: VoxelFrame? = null
    private var lattice: ComposeLattice? = null
    private var latticeProbe: FrameProbe? = null
    private var projectedWith: ComposeLattice? = null

    private var lastFrameNanos: Long = NEVER
    private var rejoining: Boolean = true
    private var seenRevision: Long = 0L

    private var keyLook: String? = null
    private var keySpeed: Float = Float.NaN
    private var key: String? = null

    private var platformReduced: Boolean = platformReducesMotion()
    private var platformCheckedNanos: Long = NEVER

    /** Whether the orb holds still: its config asks, or the platform does. */
    val reducedMotion: Boolean get() = config.reducedMotion || platformReduced

    /** The frame callback a frame loop hands to `withFrameNanos`, allocated once. */
    val onFrame: (Long) -> Unit = { frameTimeNanos -> step(frameTimeNanos) }

    /** Take a new config and probe; a change to how the orb is drawn re-projects the held frame. */
    fun update(
        config: LumosConfig,
        probe: FrameProbe,
    ) {
        this.config = config
        this.probe = probe
        reproject()
    }

    /** The canvas was laid out at [widthPx] × [heightPx] pixels. */
    fun resize(
        widthPx: Int,
        heightPx: Int,
    ) {
        this.widthPx = widthPx
        this.heightPx = heightPx
        reproject()
    }

    /** The orb starts or resumes stepping: the next step aligns its source to its look's clock. */
    fun rejoin() {
        rejoining = true
    }

    /** Step the source to [frameTimeNanos] and project what it hands back. */
    fun step(frameTimeNanos: Long) {
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

        val lookKey = keyFor(source.lookKey, speed)
        if (lookKey != null) {
            val lookTime = clock.tick(lookKey, frameTimeNanos / NANOS_PER_SECOND, speed)
            // Aligned a step behind, so the step itself lands the orb where its peers are.
            if (rejoining) source.align(lookTime - dt)
        }
        rejoining = false

        show(if (reducedMotion) source.still(dt) else source.next(dt))
    }

    /** Paused: draw a still if nothing has been drawn yet, and remember the source's revision. */
    fun hold() {
        if (heldFrame == null) show(source.still(0f))
        seenRevision = source.revision
    }

    /** Paused: redraw, once, if the source changed since it was last drawn. */
    fun pollHeld() {
        if (source.revision == seenRevision) return
        show(source.still(0f))
        // Read after the still, which may itself move the revision by landing a transition.
        seenRevision = source.revision
    }

    private fun show(frame: VoxelFrame) {
        val target = latticeFor(frame.resolution)
        if (frame === heldFrame && target === projectedWith) return
        heldFrame = frame
        if (target != null) {
            canvasFrame = target.project(frame)
            projectedWith = target
        }
    }

    private fun reproject() {
        val frame = heldFrame ?: return
        val target = latticeFor(frame.resolution) ?: return
        if (target === projectedWith) return
        canvasFrame = target.project(frame)
        projectedWith = target
    }

    // Half a lattice cell, as Socket and the trace equivalence tests size it: neighbouring voxels
    // sit 1 / resolution apart in [-1, 1], which is min(w, h) / (2 · resolution) pixels.
    private fun latticeFor(resolution: Int): ComposeLattice? {
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
        return ComposeLattice(
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
