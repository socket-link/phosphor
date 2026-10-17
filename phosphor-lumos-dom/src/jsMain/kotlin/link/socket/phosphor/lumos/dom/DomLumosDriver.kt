package link.socket.phosphor.lumos.dom

import kotlin.math.roundToInt
import link.socket.phosphor.lumos.LookClock
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.OrbStepper
import link.socket.phosphor.lumos.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.probe.FrameProbe

/**
 * The page's counterpart of `LumosOrb`: the clock and the gates around an [OrbStepper], drawing
 * what it projects into an [SvgOrbSurface]. The rules about time are the stepper's, shared with
 * every pixel surface; this adds the browser's ways of saying when to step.
 *
 * - **`requestAnimationFrame` is the clock**, capped at [maxFps]: 4,600 SVG nodes do not hold
 *   120 Hz and should not try. A cap at or above the display's rate draws every frame.
 * - **Off screen, it stops.** An `IntersectionObserver` stops the clock while the `<svg>` is out of
 *   the viewport, and `visibilitychange` while the page is hidden. Coming back rejoins the look's
 *   shared clock, so a row of orbs scrolled into view breathes together.
 * - **Size follows a `ResizeObserver`**; a new size re-projects the held frame, even when stopped.
 * - **Reduced motion** — [LumosConfig.reducedMotion] or the platform's `prefers-reduced-motion` —
 *   draws stills on a slow beat; a still that did not change is not projected or drawn, so the DOM
 *   is untouched until the source moves.
 * - **Paused**, it holds its frame and polls the source's revision, redrawing once when a new
 *   atmosphere is set.
 *
 * Main-thread only, like the source it steps.
 *
 * @param maxFps The most frames a second it draws; 0 or less draws every animation frame.
 */
internal class DomLumosDriver(
    source: LumosFrameSource,
    private val surface: SvgOrbSurface,
    private val svg: SvgNode,
    private val host: DomHost,
    config: LumosConfig,
    private val maxFps: Double,
    clock: LookClock = PageLookClock,
) {
    private val stepper = OrbStepper(source, clock, platformReducesMotion = host::reducesMotion)
    private val minFrameMs = if (maxFps > 0.0) 1000.0 / maxFps else 0.0

    private var started = false
    private var paused = config.paused
    private var intersecting = true
    private var mode = Mode.STOPPED
    private var frameId = NONE
    private var timerId = NONE
    private var lastDrawnMs = Double.NaN
    private val undo = ArrayList<() -> Unit>()

    private var widthPx = 0
    private var heightPx = 0

    /** Frames drawn into the surface. */
    var frames: Long = 0L
        private set

    /** Animation frames passed over to hold [maxFps]. */
    var framesSkipped: Long = 0L
        private set

    /** Cells in the frame drawn last. */
    var cells: Int = 0
        private set

    private val stepMs = Samples()
    private val renderMs = Samples()

    init {
        stepper.update(config, FrameProbe.Disabled)
    }

    /** Whether the orb holds still: its options ask, or the platform does. */
    val reducedMotion: Boolean get() = stepper.reducedMotion

    /** `"running"`, `"paused"` or `"stopped"` (unmounted, off screen, or in a hidden page). */
    val state: String
        get() =
            when (mode) {
                Mode.RUNNING -> "running"
                Mode.HOLDING -> "paused"
                Mode.STOPPED -> "stopped"
            }

    val size: Pair<Int, Int> get() = widthPx to heightPx

    /** Begin: watch the size, the viewport and the page, and start the clock if they allow it. */
    fun start() {
        if (started) return
        started = true
        undo += host.observeSize(svg) { width, height -> resize(width, height) }
        undo +=
            host.observeIntersection(svg) { visible ->
                intersecting = visible
                refresh()
            }
        undo += host.onVisibilityChange { refresh() }
        refresh()
    }

    /** End: stop the clock and every observer. The surface is the caller's to dispose. */
    fun stop() {
        started = false
        refresh()
        for (undoIt in undo) undoIt()
        undo.clear()
    }

    fun pause() {
        paused = true
        refresh()
    }

    fun resume() {
        paused = false
        refresh()
    }

    /** The [q] percentile of the last frames' steps — the source and the projection — in ms; NaN before one. */
    fun stepPercentile(q: Double): Double = stepMs.percentile(q)

    /** The [q] percentile of the last frames' DOM writes, in ms; NaN before one. */
    fun renderPercentile(q: Double): Double = renderMs.percentile(q)

    private fun resize(
        width: Double,
        height: Double,
    ) {
        widthPx = width.roundToInt()
        heightPx = height.roundToInt()
        draw(stepper.resize(widthPx, heightPx))
    }

    // Stopped, running or holding, from the gates; entering a mode starts its loop.
    private fun refresh() {
        val visible = intersecting && !host.documentHidden()
        val next =
            when {
                !started || !visible -> Mode.STOPPED
                paused -> Mode.HOLDING
                else -> Mode.RUNNING
            }
        if (next == mode) return
        cancel()
        mode = next
        when (next) {
            Mode.RUNNING -> {
                stepper.rejoin()
                lastDrawnMs = Double.NaN
                frameId = host.requestFrame(onFrame)
            }
            Mode.HOLDING -> {
                draw(stepper.hold())
                timerId = host.setTimer(PAUSED_POLL_MS, onPoll)
            }
            Mode.STOPPED -> Unit
        }
    }

    private val onFrame: (Double) -> Unit = { timeMs ->
        frameId = NONE
        if (mode == Mode.RUNNING) {
            if (due(timeMs)) step(timeMs) else framesSkipped++
            next()
        }
    }

    private val onBeat: () -> Unit = {
        timerId = NONE
        if (mode == Mode.RUNNING) frameId = host.requestFrame(onFrame)
    }

    private val onPoll: () -> Unit = {
        timerId = NONE
        if (mode == Mode.HOLDING) {
            draw(stepper.pollHeld())
            timerId = host.setTimer(PAUSED_POLL_MS, onPoll)
        }
    }

    // A held pose only changes at a state or glyph boundary; a slower beat spares a device whose
    // owner asked it for less motion, as LumosOrb's does.
    private fun next() {
        if (stepper.reducedMotion) {
            timerId = host.setTimer(REDUCED_MOTION_INTERVAL_MS, onBeat)
        } else {
            frameId = host.requestFrame(onFrame)
        }
    }

    // Whether a frame at timeMs is drawn under the cap. rAF times jitter, so a frame within a
    // millisecond of the interval counts, or a 60 Hz display under a 60 fps cap would drop every
    // other frame; the remainder carries, so a 144 Hz display averages near the cap.
    private fun due(timeMs: Double): Boolean {
        val last = lastDrawnMs
        if (minFrameMs <= 0.0 || last.isNaN()) {
            lastDrawnMs = timeMs
            return true
        }
        val elapsed = timeMs - last
        if (elapsed < minFrameMs - FRAME_SLACK_MS) return false
        lastDrawnMs = if (elapsed >= minFrameMs) timeMs - (elapsed % minFrameMs) else timeMs
        return true
    }

    private fun step(timeMs: Double) {
        val t0 = host.now()
        val frame = stepper.step((timeMs * NANOS_PER_MS).toLong())
        stepMs.add(host.now() - t0)
        draw(frame)
    }

    private fun draw(frame: LumosCanvasFrame?) {
        if (frame == null) return
        val t0 = host.now()
        surface.render(frame)
        renderMs.add(host.now() - t0)
        cells = frame.voxels.size
        frames++
    }

    private fun cancel() {
        if (frameId != NONE) host.cancelFrame(frameId)
        if (timerId != NONE) host.clearTimer(timerId)
        frameId = NONE
        timerId = NONE
    }

    private enum class Mode { STOPPED, RUNNING, HOLDING }

    // The last SAMPLES durations, for percentiles in stats().
    private class Samples {
        private val values = DoubleArray(SAMPLES)
        private var count = 0

        fun add(ms: Double) {
            values[count % SAMPLES] = ms
            count++
        }

        fun percentile(q: Double): Double {
            val n = minOf(count, SAMPLES)
            if (n == 0) return Double.NaN
            val sorted = values.copyOf(n).also { it.sort() }
            return sorted[((n - 1) * q).toInt()]
        }
    }

    private companion object {
        const val NONE = -1
        const val NANOS_PER_MS = 1_000_000.0
        const val FRAME_SLACK_MS = 1.0
        const val REDUCED_MOTION_INTERVAL_MS = 64
        const val PAUSED_POLL_MS = 250
        const val SAMPLES = 240
    }
}

/** One clock per page, as `LocalLookClock` is one per composition: every orb on it shares it. */
internal val PageLookClock: LookClock = LookClock()
