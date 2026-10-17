package link.socket.phosphor.lumos.dom

import kotlin.math.max
import kotlin.math.min
import link.socket.phosphor.lumos.LookClock
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.PlatformMotionPreference
import link.socket.phosphor.lumos.dom.frame.ComposeLattice

/**
 * The DOM counterpart of `LumosOrb` + `LumosDriver`: `requestAnimationFrame` instead of
 * `withFrameNanos`, an `IntersectionObserver` instead of `onGloballyPositioned`,
 * `visibilitychange` instead of the lifecycle, and the same rules about time —
 * [LookClock] shared across orbs, the tenth-of-a-second gap cap, speed, and
 * [PlatformMotionPreference] (which already reads `matchMedia` on js and wasmJs).
 *
 * What carried over unchanged from T1: LookClock, PlatformMotionPreference, LumosFrameSource
 * (align / still / revision), ComposeLattice. What had to be rewritten: the ~60 lines of
 * LumosDriver that are Compose state, because that class is internal to the Compose module.
 */
class DomLumosDriver(
    private val source: LumosFrameSource,
    private val surface: SvgOrbSurface,
    private val svg: WebElement,
    private val options: MountOptions,
    private val clock: LookClock = sharedClock,
) {
    private var frameId: Int = -1
    private var lastFrameMs: Double = Double.NaN
    private var lookKey: String? = null
    private var rejoining = true
    private var visible = true
    private var running = false
    private var disconnect: (() -> Unit)? = null
    private var lattice: ComposeLattice? = null
    private var seenRevision = -1L

    // Stats.
    private val renderMs = DoubleArray(options.sampleCapacity)
    private val projectMs = DoubleArray(options.sampleCapacity)
    private val frameGapMs = DoubleArray(options.sampleCapacity)
    private var samples = 0
    var frames = 0
        private set
    var firstFrameMs = Double.NaN
        private set
    var cells = 0
        private set
    val mountedAtMs: Double = nowMillis()

    val reducedMotion: Boolean get() = options.reduceMotion || PlatformMotionPreference.reduceMotion

    fun start() {
        running = true
        disconnect =
            observeIntersection(svg) { intersecting ->
                visible = intersecting
                if (intersecting) {
                    rejoining = true
                    schedule()
                }
            }
        onVisibilityChange {
            if (!documentHidden()) {
                rejoining = true
                schedule()
            }
        }
        schedule()
    }

    fun stop() {
        running = false
        if (frameId >= 0) cancelFrame(frameId)
        frameId = -1
        disconnect?.invoke()
        disconnect = null
    }

    private fun schedule() {
        if (!running || !visible || documentHidden() || frameId >= 0) return
        frameId = requestFrame(onFrame)
    }

    private val onFrame: (Double) -> Unit = { timeMs ->
        frameId = -1
        step(timeMs)
        schedule()
    }

    private fun step(timeMs: Double) {
        val speed = options.speed
        val gapSeconds =
            if (lastFrameMs.isNaN()) 0.0 else min(max(timeMs - lastFrameMs, 0.0) / 1000.0, LookClock.MAX_GAP_SECONDS)
        if (!lastFrameMs.isNaN() && samples < frameGapMs.size) frameGapMs[samples] = timeMs - lastFrameMs
        lastFrameMs = timeMs
        val dt = (gapSeconds * speed).toFloat()

        val key = source.lookKey?.let { LookClock.key(it, speed) }
        if (key != null) {
            val lookTime = clock.tick(key, timeMs / 1000.0, speed)
            if (rejoining) source.align(lookTime - dt)
        }
        rejoining = false

        val reduced = reducedMotion
        val frame =
            if (reduced) {
                // A still only changes at a state or glyph boundary; skip the DOM when it has not.
                val revision = source.revision
                val still = source.still(dt)
                if (revision == seenRevision && frames > 0) return
                seenRevision = source.revision
                still
            } else {
                source.next(dt)
            }

        val target = latticeFor(frame.resolution) ?: return
        val t0 = nowMillis()
        val projected = target.project(frame)
        val t1 = nowMillis()
        surface.render(projected)
        val t2 = nowMillis()
        if (frames == 0) firstFrameMs = t2
        cells = projected.voxels.size
        if (samples < renderMs.size) {
            projectMs[samples] = t1 - t0
            renderMs[samples] = t2 - t1
            samples++
        }
        frames++
    }

    private fun latticeFor(resolution: Int): ComposeLattice? {
        val (w, h) = svg.boundingSizePx()
        val widthPx = w.toInt()
        val heightPx = h.toInt()
        if (widthPx <= 0 || heightPx <= 0) return null
        val radius = min(widthPx, heightPx) / (4f * max(resolution, 1)) * options.dotSize
        val current = lattice
        if (current != null && current.widthPx == widthPx && current.heightPx == heightPx && current.voxelRadiusPx == radius) {
            return current
        }
        return ComposeLattice(widthPx = widthPx, heightPx = heightPx, voxelRadiusPx = radius).also { lattice = it }
    }

    fun statsJson(): String {
        val n = samples
        val render = renderMs.copyOf(n)
        val project = projectMs.copyOf(n)
        val gap = frameGapMs.copyOf(n)
        return """{"frames":$frames,"samples":$n,"cells":$cells,"elements":${surface.elements},""" +
            """"firstFrameMs":${firstFrameMs.r()},"mountedAtMs":${mountedAtMs.r()},""" +
            """"renderP50":${percentile(render, 0.50).r()},"renderP95":${percentile(render, 0.95).r()},""" +
            """"projectP50":${percentile(project, 0.50).r()},"projectP95":${percentile(project, 0.95).r()},""" +
            """"frameGapP50":${percentile(gap, 0.50).r()},"frameGapP95":${percentile(gap, 0.95).r()},""" +
            """"attributeWrites":${surface.attributeWrites},"attributeSkips":${surface.attributeSkips},""" +
            """"reducedMotion":$reducedMotion,"style":"${options.style}","tint":"${options.tint}"}"""
    }

    companion object {
        /** One clock per page, as `LocalLookClock` is one per composition. */
        val sharedClock = LookClock()

        fun percentile(
            values: DoubleArray,
            q: Double,
        ): Double {
            if (values.isEmpty()) return Double.NaN
            val sorted = values.copyOf().also { it.sort() }
            val index = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
            return sorted[index]
        }

        private fun Double.r(): String = if (isNaN()) "null" else ((this * 1000.0).toLong() / 1000.0).toString()
    }
}
