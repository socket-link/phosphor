package link.socket.phosphor.lumos.surface

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.min
import link.socket.phosphor.field.Choreography
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LookClock
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosForm
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.OrbStepper
import link.socket.phosphor.lumos.OrbViewport
import link.socket.phosphor.lumos.PixelForm
import link.socket.phosphor.lumos.PlatformMotionPreference
import link.socket.phosphor.lumos.RectPx
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.signal.LumosInput

/**
 * Owns an assembly: the orb's source and where it sits, the pixel form the cells fly to, the
 * frame loop while they fly, measured arrival and the hand-off alphas.
 *
 * One surface, one assembly at a time. Compose it with [LumosSurface]; give the orb a place
 * with [LumosSurfaceOrb] and the control a [lumosTarget]; then call [assembleInto]
 * from anywhere. The [phase] says where things are, and every target reads [alphaFor].
 *
 * - **One driver per source.** While the phase is [SurfacePhase.Orb], [LumosSurfaceOrb] composes a
 *   `LumosOrb` on [source] and that orb steps it. It keeps it through [assembleInto] until the
 *   flight starts, and the frame it does, an [OrbStepper] of this state's own takes the source over,
 *   projecting it at the [viewport] on the surface, and the slot drops its orb once the overlay has
 *   that frame to draw: the orb never blinks out between the two. On that one frame both step the
 *   source, but the overlay's stepper is new each flight and its first step is `dt = 0`, so the
 *   orb still moves by one frame's time. On [SurfacePhase.Solid] nothing steps.
 * - **Measured arrival.** Each frame, [arrival] is the mean distance in pixels of the projected
 *   cells outside the target's rounded rectangle, through Dots' window `(8 − meanErr) / 6`,
 *   smoothstepped. Never the morph's own progress, which ignores the choreography's arc. The
 *   control's alpha is the arrival; the cells grow `× (1 + 0.6 · arrival)` and fade
 *   `× (1 − arrival)` on the same curve, and lerp toward a tint when one is given. Solid when
 *   arrival reaches 1 or the morph settles, whichever first.
 * - **Count.** By default every cell flies, however densely that packs the control; with
 *   [SurfaceSurplus.Fade] the cells the control does not need fade out under the burst and are
 *   dropped when the morph starts. A count too small to draw the control's edge puts more of its
 *   cells on the outline; see `PixelForm.outlineFraction`.
 * - **Follow.** The form reads the target's live bounds every step, so a control that scrolls
 *   mid-flight is tracked with the spring's own momentum.
 * - **Reduced motion.** `config.reducedMotion || PlatformMotionPreference.reduceMotion`, read once
 *   at [assembleInto]: the control appears at once, no cell flies, and the orb stays an orb.
 * - **Cancel.** [cancel] snaps the control on and finishes; [LumosSurface] calls it when its host's
 *   lifecycle drops below `STARTED` or the surface is resized mid-flight.
 * - **Home.** On Solid the builder's form is cut back to what it was before the assembly, so a
 *   `LumosOrb` composed on the source afterwards draws the orb, not the rectangle. Nothing draws
 *   the cells at that moment, so the cut is invisible. [release] returns the phase to Orb.
 *
 * Live only: a pixel form depends on layout, so a surface morph is never recorded to a `.vxt`.
 * Main-thread only, like the source it steps.
 *
 * @param source The live scene whose cells fly. Its builder is morphed; a trace has none.
 * @param viewport Where the orb's unit sphere sits, in the surface's pixels. Null, the default,
 *  measures it from [LumosSurfaceOrb]'s slot; a host with no slot passes one.
 * @param config How the orb is driven and drawn, shared by the slot's orb and the overlay so the
 *  cells keep their speed, size, tilt and style across the switch. [SurfaceStyle.Dots] by default,
 *  not `LumosConfig`'s Squares: at the inline orb's 20 px, Squares on a button merge into a slab
 *  with holes, and Dots stay cells (PHO-76).
 */
class LumosSurfaceState(
    val source: LiveFrameSource,
    viewport: OrbViewport? = null,
    val config: LumosConfig = LumosConfig(style = SurfaceStyle.Dots),
) {
    /** Where the surface is. Compose state. */
    var phase: SurfacePhase by mutableStateOf(SurfacePhase.Orb)
        private set

    /**
     * How far the cells have arrived at the target being assembled, `0..1`: 0 while they are out
     * on the arc, 1 when the control is solid. Compose state.
     */
    var arrival: Float by mutableFloatStateOf(0f)
        private set

    /** The frame the overlay draws while [phase] is [SurfacePhase.Assembling]; otherwise null. Compose state. */
    var canvasFrame: LumosCanvasFrame? by mutableStateOf(null)
        private set

    /** The colour the cells lerp toward on arrival, if [assembleInto] was given one. Compose state. */
    var tint: Color? by mutableStateOf(null)
        private set

    private var explicitViewport: OrbViewport? by mutableStateOf(viewport)
    private var slotBounds: Rect? by mutableStateOf(null)

    /**
     * Where the orb's unit sphere sits, in the surface's pixels: the one given to the constructor
     * or set here, else the centre and half the shorter side of [LumosSurfaceOrb]'s slot, else
     * null until the slot is laid out. Setting it overrides the slot.
     */
    var viewport: OrbViewport?
        get() {
            explicitViewport?.let { return it }
            val slot = slotBounds ?: return null
            if (slot.isEmpty) return null
            return OrbViewport(
                centerXPx = slot.center.x - originX,
                centerYPx = slot.center.y - originY,
                radiusPx = min(slot.width, slot.height) / 2f,
            )
        }
        set(value) {
            explicitViewport = value
        }

    /** The frame callback [LumosSurface]'s loop hands to `withFrameNanos`, allocated once. */
    val onFrame: (Long) -> Unit = { frameTimeNanos -> step(frameTimeNanos) }

    /** The platform's reduced-motion request; a test replaces it. */
    internal var platformReducesMotion: () -> Boolean = { PlatformMotionPreference.reduceMotion }

    /** The share of a count's cells the form puts on the control's outline; a test replaces it. */
    internal var outlineFraction: (RectPx, Int) -> Float = PixelForm::outlineFraction

    /** The form the cells are flying to, from the frame the flight starts until Solid. */
    internal var form: PixelForm.RoundedRect? = null
        private set

    /**
     * Under [SurfaceSurplus.Fade], which of the orb's cells are fading, by index, until the morph
     * drops them; otherwise null.
     */
    internal var surplus: BooleanArray? = null
        private set

    /** How far [surplus] has faded, `0..1`. */
    internal var surplusFade: Float = 0f
        private set

    // What this state's stepper steps: the source, with the surplus faded while it is. The
    // projection sorts by depth, so the fade is applied here, where cell i is still cell i.
    private val fadingSource = FadingSource()

    private var solidIds: Set<String> by mutableStateOf(emptySet())
    private val targets = HashMap<String, Target>()
    private var stepper: OrbStepper? = null
    private var clock: LookClock? = null
    private var originX = 0f
    private var originY = 0f
    private var widthPx = 0
    private var heightPx = 0
    private var request: Request? = null
    private var homeForm: LumosForm? = null

    // A fade in flight: the cells that stay, out of the surplus mask's count, and when the fade began.
    private var survivors: IntArray? = null
    private var fadeStartNanos: Long = 0L

    /**
     * Target [id]'s alpha: 1 once the cells have become it, the [arrival] while they are on
     * their way, and 0 otherwise. A target is invisible until it is assembled; it is laid out and
     * clickable throughout. Compose state.
     */
    fun alphaFor(id: String): Float =
        when {
            id in solidIds -> 1f
            (phase as? SurfacePhase.Assembling)?.id == id -> arrival
            else -> 0f
        }

    /**
     * Burst, then morph the cells into target [id] over [durationSeconds], the control's alpha
     * following the measured arrival. The flight starts on the first frame on which the target's
     * bounds and the [viewport] are both known. Under reduced motion the control appears at once
     * instead and the orb stays where it is. Ignored unless the phase is [SurfacePhase.Orb]:
     * [cancel] or [release] first.
     *
     * @param id The target, as given to [lumosTarget].
     * @param burst Outward speed at the moment of firing, in orb radii per second; see
     *  `VoxelFrameBuilder.burst`. [DEFAULT_BURST] is Dots'.
     * @param durationSeconds How long the morph's spring takes to settle. The hand-off usually
     *  completes before it does.
     * @param tint A colour the cells lerp toward on the arrival curve, so the hand-off reads as
     *  one material changing state; null leaves their colours alone.
     * @param surplus What to do with the cells the control does not need; every cell flies by
     *  default. Under [SurfaceSurplus.Fade] the morph starts once the surplus has faded.
     * @throws IllegalArgumentException when [burst] is not finite or [durationSeconds] is not
     *  positive and finite.
     */
    fun assembleInto(
        id: String,
        burst: Float = DEFAULT_BURST,
        durationSeconds: Float = DEFAULT_DURATION_SECONDS,
        tint: Color? = null,
        surplus: SurfaceSurplus = SurfaceSurplus.Keep,
    ) {
        require(burst.isFinite()) { "burst must be finite, was $burst" }
        require(durationSeconds > 0f && durationSeconds.isFinite()) {
            "durationSeconds must be > 0 and finite, was $durationSeconds"
        }
        if (phase !is SurfacePhase.Orb) return
        if (config.reducedMotion || platformReducesMotion()) {
            solidIds = solidIds + id
            arrival = 1f
            phase = SurfacePhase.Solid(id)
            return
        }
        this.tint = tint
        arrival = 0f
        request = Request(id, burst, durationSeconds, surplus)
        phase = SurfacePhase.Assembling(id)
    }

    /** Mid-flight: snap the control on and finish. Does nothing in any other phase. */
    fun cancel() {
        val assembling = phase as? SurfacePhase.Assembling ?: return
        finish(assembling.id)
    }

    /**
     * Solid: give the surface back to the orb as a cut. The control turns invisible again and
     * [LumosSurfaceOrb] composes its orb once more. Does nothing in any other phase.
     */
    fun release() {
        val solid = phase as? SurfacePhase.Solid ?: return
        solidIds = solidIds - solid.id
        arrival = 0f
        tint = null
        phase = SurfacePhase.Orb
    }

    /**
     * Whether [LumosSurfaceOrb]'s slot draws the orb: as an orb, and through an assembly until the
     * overlay has the flight's first frame to draw, so nothing blinks out between the two. Compose state.
     */
    internal val orbInSlot: Boolean
        get() = phase is SurfacePhase.Orb || (phase is SurfacePhase.Assembling && canvasFrame == null)

    /** The surface composed under [clock]: the stepper that takes the source over steps on it. */
    internal fun attach(clock: LookClock) {
        this.clock = clock
    }

    /** Where the surface's box sits in the composition root; targets and the slot are measured relative to it. */
    internal fun surfacePositioned(positionInRoot: Offset) {
        if (positionInRoot.x == originX && positionInRoot.y == originY) return
        originX = positionInRoot.x
        originY = positionInRoot.y
        for (target in targets.values) target.dirty = true
    }

    /** The surface was laid out at [widthPx] × [heightPx]. A change mid-flight cancels. */
    internal fun surfaceSized(
        widthPx: Int,
        heightPx: Int,
    ) {
        if (widthPx == this.widthPx && heightPx == this.heightPx) return
        val hadSize = this.widthPx > 0 && this.heightPx > 0
        this.widthPx = widthPx
        this.heightPx = heightPx
        stepper?.resize(widthPx, heightPx)
        if (hadSize && form != null) cancel()
    }

    /** [LumosSurfaceOrb]'s slot was laid out at [boundsInRoot]. */
    internal fun slotPositioned(boundsInRoot: Rect) {
        slotBounds = boundsInRoot
    }

    /** Target [id] was laid out at [boundsInRoot] with corners of [cornerRadiusPx]. */
    internal fun targetPositioned(
        id: String,
        boundsInRoot: Rect,
        cornerRadiusPx: Float,
    ) {
        val target = targets.getOrPut(id) { Target() }
        if (target.bounds == boundsInRoot && target.cornerRadiusPx == cornerRadiusPx) return
        target.bounds = boundsInRoot
        target.cornerRadiusPx = cornerRadiusPx
        target.dirty = true
    }

    /**
     * One frame of flight: start the flight if the target and viewport have just become known,
     * fade any surplus and then start the morph, follow the target, step and project the source,
     * measure arrival, and finish when the cells have arrived or the spring has settled.
     */
    internal fun step(frameTimeNanos: Long) {
        val assembling = phase as? SurfacePhase.Assembling ?: return
        val request = request ?: return
        val clock = clock ?: return
        val target = targets[assembling.id] ?: return
        if (widthPx <= 0 || heightPx <= 0) return
        val builder = source.builder

        val flying =
            form ?: run {
                val viewport = viewport ?: return
                val rect = target.rectPx(originX, originY) ?: return
                target.dirty = false
                val count = builder.currentCount
                val keep = (request.surplus as? SurfaceSurplus.Fade)?.keep(rect, count) ?: count
                PixelForm.roundedRect(rect, viewport, outlineFraction(rect, keep)).also { started ->
                    form = started
                    homeForm = builder.form
                    // New each flight, so its first step is dt = 0: the slot's orb has stepped this frame.
                    stepper =
                        OrbStepper(fadingSource, clock, platformReducesMotion).also {
                            it.update(config, FrameProbe.Disabled)
                            it.resize(widthPx, heightPx)
                            it.place(viewport)
                            it.rejoin()
                        }
                    builder.burst(request.burst)
                    if (keep < count) {
                        val kept = evenSurvivors(count, keep)
                        survivors = kept
                        surplus = BooleanArray(count) { true }.also { mask -> for (i in kept) mask[i] = false }
                        surplusFade = 0f
                        fadeStartNanos = frameTimeNanos
                    } else {
                        morph(started, count, request)
                    }
                }
            }
        if (target.dirty) {
            target.rectPx(originX, originY)?.let { flying.rect = it }
            target.dirty = false
        }

        val fading = survivors
        if (fading != null) {
            val fade = request.surplus as SurfaceSurplus.Fade
            val elapsedNanos = (frameTimeNanos - fadeStartNanos).coerceAtLeast(0L)
            surplusFade = (elapsedNanos / fade.duration.inWholeNanoseconds.toDouble()).toFloat().coerceAtMost(1f)
            if (surplusFade >= 1f) {
                // Faded out under the burst: the morph drops them, and nothing visible is cut. A
                // resolution change mid-fade was a cut already, so the survivors no longer apply.
                val stillCounted = builder.currentCount == surplus?.size
                morph(flying, fading.size, request, fading.takeIf { stillCounted })
                survivors = null
                surplus = null
            }
        }

        val stepper = stepper ?: return
        stepper.step(frameTimeNanos)?.let { canvasFrame = it }
        val frame = stepper.frame ?: return
        val arrived = arrivalOf(frame, flying.rect)
        arrival = arrived
        if (survivors == null && (arrived >= 1f || !builder.isMorphing)) finish(assembling.id)
    }

    private fun morph(
        form: PixelForm.RoundedRect,
        count: Int,
        request: Request,
        survivors: IntArray? = null,
    ) = source.builder.morphTo(
        form = form,
        count = count,
        choreography = Choreography.FLOW,
        durationSeconds = request.durationSeconds,
        survivors = survivors,
    )

    private fun finish(id: String) {
        // A cut, back to the form the orb wore: nothing draws the cells now, so nothing jumps.
        homeForm?.let { source.builder.form = it }
        homeForm = null
        form = null
        survivors = null
        surplus = null
        surplusFade = 0f
        request = null
        stepper = null
        canvasFrame = null
        arrival = 1f
        solidIds = solidIds + id
        phase = SurfacePhase.Solid(id)
    }

    // Dots' arrival: the mean of each cell's distance outside the target's rounded edge, in pixels,
    // 0 at ARRIVAL_FAR_PX and 1 at ARRIVAL_NEAR_PX. No pairing of cells to positions: a cell inside
    // the edge has arrived wherever it sits. One pass, no allocation.
    private fun arrivalOf(
        frame: LumosCanvasFrame,
        rect: RectPx,
    ): Float {
        val voxels = frame.voxels
        if (voxels.isEmpty()) return 0f
        var sum = 0f
        for (i in voxels.indices) {
            val voxel = voxels[i]
            val outside = rect.signedDistance(voxel.screenX, voxel.screenY)
            if (outside > 0f) sum += outside
        }
        val meanErr = sum / voxels.size
        return smoothstep((ARRIVAL_FAR_PX - meanErr) / (ARRIVAL_FAR_PX - ARRIVAL_NEAR_PX))
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private inner class FadingSource : LumosFrameSource {
        override val revision: Long get() = source.revision
        override val lookKey: String? get() = source.lookKey

        override fun next(dt: Float): VoxelFrame = faded(source.next(dt))

        override fun still(dt: Float): VoxelFrame = faded(source.still(dt))

        override fun align(lookTime: Double) = source.align(lookTime)

        override fun alignLook(lookTime: Double) = source.alignLook(lookTime)

        override fun setInput(input: LumosInput) = source.setInput(input)

        private fun faded(frame: VoxelFrame): VoxelFrame {
            val mask = surplus ?: return frame
            if (mask.size != frame.cells.size) return frame
            val keep = 1f - surplusFade
            return frame.copy(
                cells =
                    frame.cells.mapIndexed { i, cell ->
                        if (mask[i]) cell.copy(alpha = (cell.alpha ?: 1f) * keep) else cell
                    },
            )
        }
    }

    private class Request(
        val id: String,
        val burst: Float,
        val durationSeconds: Float,
        val surplus: SurfaceSurplus,
    )

    // A target's last bounds, in root pixels, updated in place so a scrolling target allocates
    // nothing per frame; `dirty` says the form has not read them yet.
    private class Target {
        var bounds: Rect = Rect.Zero
        var cornerRadiusPx: Float = 0f
        var dirty: Boolean = true

        fun rectPx(
            originX: Float,
            originY: Float,
        ): RectPx? {
            if (bounds.width <= 0f || bounds.height <= 0f) return null
            return RectPx(
                left = bounds.left - originX,
                top = bounds.top - originY,
                width = bounds.width,
                height = bounds.height,
                cornerRadius = cornerRadiusPx,
            )
        }
    }

    companion object {
        /** Dots' burst before a morph, in orb radii per second. */
        const val DEFAULT_BURST: Float = 6f

        /** How long the morph's spring takes to settle by default. */
        const val DEFAULT_DURATION_SECONDS: Float = 2.4f

        /** Mean pixel error at which arrival is 0: Dots' `(8 − meanErr) / 6`. */
        const val ARRIVAL_FAR_PX: Float = 8f

        /** Mean pixel error at which arrival is 1. */
        const val ARRIVAL_NEAR_PX: Float = 2f

        /** How much a cell grows by full arrival: radius `× (1 + HAND_OFF_GROW)`. */
        const val HAND_OFF_GROW: Float = 0.6f
    }
}
