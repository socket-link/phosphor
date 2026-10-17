@file:OptIn(ExperimentalWasmJsInterop::class)

package link.socket.phosphor.lumos.dom

import kotlinx.browser.document
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

private fun jsNow(): Double = js("performance.now()")

actual fun nowMillis(): Double = jsNow()

private fun jsRequestFrame(callback: (Double) -> Unit): Int = js("requestAnimationFrame(callback)")

actual fun requestFrame(callback: (Double) -> Unit): Int = jsRequestFrame(callback)

private fun jsCancelFrame(id: Int) {
    js("cancelAnimationFrame(id)")
}

actual fun cancelFrame(id: Int) = jsCancelFrame(id)

private fun jsHidden(): Boolean = js("document.hidden")

actual fun documentHidden(): Boolean = jsHidden()

private fun jsOnVisibilityChange(callback: () -> Unit) {
    js("document.addEventListener('visibilitychange', function () { callback(); })")
}

actual fun onVisibilityChange(callback: () -> Unit) = jsOnVisibilityChange(callback)

private fun jsObserve(
    element: Element,
    callback: (Boolean) -> Unit,
): JsAny =
    js(
        """{
        var o = new IntersectionObserver(function (entries) { for (var i = 0; i < entries.length; i++) callback(entries[i].isIntersecting); });
        o.observe(element);
        return o;
    }""",
    )

private fun jsDisconnect(observer: JsAny) {
    js("observer.disconnect()")
}

actual fun observeIntersection(
    element: WebElement,
    callback: (Boolean) -> Unit,
): () -> Unit {
    val observer = jsObserve(element.element, callback)
    return { jsDisconnect(observer) }
}

private fun jsInstall(
    mount: (Element, String) -> Int,
    unmount: (Int) -> Unit,
    stats: (Int) -> String,
) {
    js(
        """{
        window.PhosphorLumos = {
          target: 'wasm',
          mount: function (svg, options) {
            var encoded = typeof options === 'string' ? options
              : Object.keys(options || {}).map(function (k) { return k + '=' + options[k]; }).join('&');
            return mount(svg, encoded);
          },
          unmount: function (handle) { unmount(handle); },
          stats: function (handle) { return stats(handle); }
        };
        window.dispatchEvent(new Event('lumos-ready'));
    }""",
    )
}

actual fun installGlobalApi() {
    jsInstall(
        { svg, options -> LumosMounts.mount(WebElement(svg), options) },
        { handle -> LumosMounts.unmount(handle) },
        { handle -> LumosMounts.stats(handle) },
    )
}
