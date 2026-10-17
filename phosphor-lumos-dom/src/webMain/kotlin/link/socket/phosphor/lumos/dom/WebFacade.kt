package link.socket.phosphor.lumos.dom

/**
 * The handful of browser calls the adapter needs, declared once so the adapter and the driver
 * are shared Kotlin over js and wasmJs. Each target's actual is a thin wrapper; nothing else in
 * the module touches the DOM directly.
 */
expect class WebElement {
    fun setAttribute(
        name: String,
        value: String,
    )

    fun appendChild(child: WebElement)

    fun removeChildren()

    /** CSS-pixel size from `getBoundingClientRect`. */
    fun boundingSizePx(): Pair<Double, Double>
}

expect fun createSvgElement(tag: String): WebElement

/** `performance.now()`: milliseconds since the page's time origin. */
expect fun nowMillis(): Double

expect fun requestFrame(callback: (Double) -> Unit): Int

expect fun cancelFrame(id: Int)

expect fun documentHidden(): Boolean

expect fun onVisibilityChange(callback: () -> Unit)

/** Observe [element] with an `IntersectionObserver`; returns the disconnect. */
expect fun observeIntersection(
    element: WebElement,
    callback: (Boolean) -> Unit,
): () -> Unit

/** Install `window.PhosphorLumos = { mount, unmount, stats }` and dispatch `lumos-ready`. */
expect fun installGlobalApi()
