package link.socket.phosphor.lumos.dom

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element

actual class WebElement(
    val element: Element,
) {
    actual fun setAttribute(
        name: String,
        value: String,
    ) = element.setAttribute(name, value)

    actual fun appendChild(child: WebElement) {
        element.appendChild(child.element)
    }

    actual fun removeChildren() {
        while (element.firstChild != null) element.removeChild(element.firstChild!!)
    }

    actual fun boundingSizePx(): Pair<Double, Double> {
        val rect = element.getBoundingClientRect()
        return rect.width to rect.height
    }
}

actual fun createSvgElement(tag: String): WebElement = WebElement(document.createElementNS("http://www.w3.org/2000/svg", tag))

actual fun nowMillis(): Double = window.performance.now()

actual fun requestFrame(callback: (Double) -> Unit): Int = window.requestAnimationFrame(callback)

actual fun cancelFrame(id: Int) = window.cancelAnimationFrame(id)

actual fun documentHidden(): Boolean = js("document.hidden") as Boolean

actual fun onVisibilityChange(callback: () -> Unit) {
    document.addEventListener("visibilitychange", { callback() })
}

actual fun observeIntersection(
    element: WebElement,
    callback: (Boolean) -> Unit,
): () -> Unit {
    val target = element.element
    val observer =
        js(
            "new IntersectionObserver(function (entries) { for (var i = 0; i < entries.length; i++) callback(entries[i].isIntersecting); })",
        )
    observer.observe(target)
    return { observer.disconnect() }
}

// window.PhosphorLumos.mount(svgElement, { style: 'dots', atmosphere: 'thinking', ... }) -> handle.
// Plain JS, no React: a React page calls mount in useEffect and unmount in its cleanup.
actual fun installGlobalApi() {
    val mount = { svg: Element, options: String -> LumosMounts.mount(WebElement(svg), options) }
    val unmount = { handle: Int -> LumosMounts.unmount(handle) }
    val stats = { handle: Int -> LumosMounts.stats(handle) }
    js(
        """
        window.PhosphorLumos = {
          target: 'js',
          mount: function (svg, options) {
            var encoded = typeof options === 'string' ? options
              : Object.keys(options || {}).map(function (k) { return k + '=' + options[k]; }).join('&');
            return mount(svg, encoded);
          },
          unmount: function (handle) { unmount(handle); },
          stats: function (handle) { return stats(handle); }
        };
        window.dispatchEvent(new Event('lumos-ready'));
        """,
    )
}
