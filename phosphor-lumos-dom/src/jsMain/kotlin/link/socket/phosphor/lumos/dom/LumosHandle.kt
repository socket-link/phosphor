@file:OptIn(ExperimentalJsExport::class)

package link.socket.phosphor.lumos.dom

import kotlin.math.round
import link.socket.phosphor.lumos.SurfaceStyle

/**
 * A mounted orb, as [mount] returns it. Every call after [unmount] does nothing.
 */
@JsExport
class LumosHandle internal constructor(
    internal val svg: SvgNode,
    internal val scene: MountedScene,
    internal val surface: SvgOrbSurface,
    internal val driver: DomLumosDriver,
    internal val options: MountOptions,
) {
    private var mounted = true

    /**
     * Head for the atmosphere preset [name] — `"idle"`, `"listening"`, `"thinking"`, `"uncertain"`
     * or `"ready"` — over its tabled transition; at once under reduced motion.
     */
    fun setAtmosphere(name: String) {
        if (mounted) scene.setAtmosphere(name)
    }

    /** Blend to the look [name] — `"working"`, or `"working-gyro"` for a variant — over 0.6 s. */
    fun setLook(name: String) {
        if (mounted) scene.setLook(parseLook(name))
    }

    /** Stop the clock and hold the frame; a new atmosphere set while paused still draws, once. */
    fun pause() {
        if (mounted) driver.pause()
    }

    /** Start the clock again, rejoining the look's peers. */
    fun resume() {
        if (mounted) driver.resume()
    }

    /** Stop for good: the clock, the observers, and every element and attribute the orb added. */
    fun unmount() {
        if (!mounted) return
        mounted = false
        driver.stop()
        surface.dispose()
        clearSemantics(svg)
    }

    /**
     * What the orb has done, as a plain object: `state` (`"running"`, `"paused"`, `"stopped"` or
     * `"unmounted"`), `frames` drawn, `framesSkipped` under `maxFps`, `cells` in the last frame,
     * `elements` made, `attributeWrites` and `attributeSkips`, `stepP50` / `stepP95` and `renderP50` /
     * `renderP95` over the last frames in milliseconds (`null` before one), `reducedMotion`, `width`,
     * `height`, `style` and `tint`.
     */
    fun stats(): dynamic {
        val stats: dynamic = js("({})")
        stats.state = if (mounted) driver.state else "unmounted"
        stats.frames = driver.frames.toDouble()
        stats.framesSkipped = driver.framesSkipped.toDouble()
        stats.cells = driver.cells
        stats.elements = surface.elements
        stats.attributeWrites = surface.attributeWrites.toDouble()
        stats.attributeSkips = surface.attributeSkips.toDouble()
        stats.stepP50 = millis(driver.stepPercentile(0.50))
        stats.stepP95 = millis(driver.stepPercentile(0.95))
        stats.renderP50 = millis(driver.renderPercentile(0.50))
        stats.renderP95 = millis(driver.renderPercentile(0.95))
        stats.reducedMotion = driver.reducedMotion
        val (width, height) = driver.size
        stats.width = width
        stats.height = height
        stats.style =
            when (options.style) {
                SurfaceStyle.Squares -> "squares"
                SurfaceStyle.Dots -> "dots"
                SurfaceStyle.Dashes -> "dashes"
            }
        stats.tint = if (options.tint == SvgTint.CURRENT_COLOR) "currentColor" else "cell"
        return stats
    }
}

private fun millis(value: Double): Double? = if (value.isNaN()) null else round(value * 1000.0) / 1000.0
