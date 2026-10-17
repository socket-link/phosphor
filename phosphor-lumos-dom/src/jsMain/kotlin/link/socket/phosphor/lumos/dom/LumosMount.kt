@file:OptIn(ExperimentalJsExport::class)

package link.socket.phosphor.lumos.dom

import link.socket.phosphor.lumos.LookClock
import org.w3c.dom.svg.SVGElement

/**
 * Mount a live Lumos orb in [svg] and keep it drawing until [LumosHandle.unmount].
 *
 * The module's one entry point, for any page — plain HTML, React, anything that hands over an
 * element:
 *
 * ```js
 * import { mount } from './phosphor-lumos-dom.mjs';
 *
 * const orb = mount(document.querySelector('svg.orb'), { style: 'dots', atmosphere: 'thinking' });
 * orb.setAtmosphere('ready');
 * orb.unmount();
 * ```
 *
 * Size the `<svg>` with CSS; the orb follows its size. It stops while scrolled out of view or while
 * the page is hidden, holds still when the platform asks for reduced motion, and is one labelled
 * image to a screen reader with a `label`, or hidden from one without. Orbs of one look on a page
 * share a clock and breathe together.
 *
 * The orb adds its elements after anything the `<svg>` already holds and sets its `viewBox`.
 *
 * @param svg The `<svg>` to draw into.
 * @param options A plain object, every field optional; see [LumosMountOptions].
 * @throws IllegalArgumentException for an option it does not take, naming it.
 */
@JsExport
fun mount(
    svg: SVGElement,
    options: LumosMountOptions?,
): LumosHandle = mountOrb(DomSvgNode(svg), MountOptions.from(options), BrowserHost, DomSvgFactory)

/** [mount] against any host and element factory: the page's in [mount], fakes in the tests. */
internal fun mountOrb(
    svg: SvgNode,
    options: MountOptions,
    host: DomHost,
    factory: SvgFactory,
    clock: LookClock = PageLookClock,
): LumosHandle {
    val scene = MountedScene(options.atmosphere, options.resolution)
    // The first look is worn outright; only a later setLook blends.
    scene.live.builder.look = options.look
    val surface = SvgOrbSurface(svg, factory, options.style, options.tint, options.halo, options.glyph)
    applySemantics(svg, options.label)
    val driver = DomLumosDriver(scene, surface, svg, host, options.config, options.maxFps, clock)
    driver.start()
    return LumosHandle(svg, scene, surface, driver, options)
}

// LumosOrb's semantics: with a label, one image described by it; without one, nothing a screen reader stops on.
private fun applySemantics(
    svg: SvgNode,
    label: String?,
) {
    if (label != null) {
        svg.setAttribute("role", "img")
        svg.setAttribute("aria-label", label)
    } else {
        svg.setAttribute("aria-hidden", "true")
    }
}

internal fun clearSemantics(svg: SvgNode) {
    svg.removeAttribute("role")
    svg.removeAttribute("aria-label")
    svg.removeAttribute("aria-hidden")
}
