package link.socket.phosphor.lumos.dom

import kotlinx.browser.document
import kotlinx.browser.window
import link.socket.phosphor.lumos.PlatformMotionPreference
import org.w3c.dom.DOMRectReadOnly
import org.w3c.dom.Element
import org.w3c.dom.events.Event

/**
 * The browser calls [DomLumosDriver] makes, declared once so the tests can drive it with a fake:
 * a frame clock, timers, and the three things that gate an orb — whether its page is hidden,
 * whether it is on screen, and its size.
 *
 * Every `observe` and `on` call returns the function that undoes it.
 */
internal interface DomHost {
    /** `requestAnimationFrame`; the callback receives the frame time in milliseconds. */
    fun requestFrame(callback: (Double) -> Unit): Int

    fun cancelFrame(id: Int)

    fun setTimer(
        delayMs: Int,
        callback: () -> Unit,
    ): Int

    fun clearTimer(id: Int)

    /** `performance.now()`: milliseconds on the same clock frame times read. */
    fun now(): Double

    /** `document.hidden`. */
    fun documentHidden(): Boolean

    /** Listen for `visibilitychange`. */
    fun onVisibilityChange(callback: () -> Unit): () -> Unit

    /** Watch whether [target] intersects the viewport. */
    fun observeIntersection(
        target: SvgNode,
        callback: (Boolean) -> Unit,
    ): () -> Unit

    /** Report [target]'s size in CSS pixels now, and again whenever it changes. */
    fun observeSize(
        target: SvgNode,
        callback: (widthPx: Double, heightPx: Double) -> Unit,
    ): () -> Unit

    /** Whether the platform asks for reduced motion right now. */
    fun reducesMotion(): Boolean
}

/** An element of the page's DOM. */
internal class DomSvgNode(
    val element: Element,
) : SvgNode {
    override fun setAttribute(
        name: String,
        value: String,
    ) = element.setAttribute(name, value)

    override fun removeAttribute(name: String) = element.removeAttribute(name)

    override fun appendChild(child: SvgNode) {
        element.appendChild((child as DomSvgNode).element)
    }

    override fun removeChild(child: SvgNode) {
        val node = (child as DomSvgNode).element
        if (node.parentNode == element) element.removeChild(node)
    }
}

/** Makes elements in the SVG namespace of the page's document. */
internal val DomSvgFactory: SvgFactory =
    SvgFactory { tag -> DomSvgNode(document.createElementNS(SVG_NAMESPACE, tag)) }

/** The page itself. */
internal object BrowserHost : DomHost {
    override fun requestFrame(callback: (Double) -> Unit): Int = window.requestAnimationFrame(callback)

    override fun cancelFrame(id: Int) = window.cancelAnimationFrame(id)

    override fun setTimer(
        delayMs: Int,
        callback: () -> Unit,
    ): Int = window.setTimeout({ callback() }, delayMs)

    override fun clearTimer(id: Int) = window.clearTimeout(id)

    override fun now(): Double = window.performance.now()

    override fun documentHidden(): Boolean = document.asDynamic().hidden == true

    override fun onVisibilityChange(callback: () -> Unit): () -> Unit {
        val listener: (Event) -> Unit = { callback() }
        document.addEventListener("visibilitychange", listener)
        return { document.removeEventListener("visibilitychange", listener) }
    }

    override fun observeIntersection(
        target: SvgNode,
        callback: (Boolean) -> Unit,
    ): () -> Unit {
        // Entries arrive oldest first; the last one is the target's state now.
        val observer =
            IntersectionObserver { entries, _ ->
                if (entries.isNotEmpty()) callback(entries.last().isIntersecting)
            }
        observer.observe((target as DomSvgNode).element)
        return { observer.disconnect() }
    }

    override fun observeSize(
        target: SvgNode,
        callback: (widthPx: Double, heightPx: Double) -> Unit,
    ): () -> Unit {
        val element = (target as DomSvgNode).element
        // Read once now, so the first frame does not wait for the observer's first delivery.
        val rect = element.getBoundingClientRect()
        callback(rect.width, rect.height)
        val observer =
            ResizeObserver { entries, _ ->
                if (entries.isNotEmpty()) {
                    val box = entries.last().contentRect
                    callback(box.width, box.height)
                }
            }
        observer.observe(element)
        return { observer.disconnect() }
    }

    override fun reducesMotion(): Boolean = PlatformMotionPreference.reduceMotion
}

private const val SVG_NAMESPACE = "http://www.w3.org/2000/svg"

// The two observers postdate Kotlin's DOM bindings; only what the host calls is declared.

internal external class ResizeObserver(
    callback: (entries: Array<ResizeObserverEntry>, observer: ResizeObserver) -> Unit,
) {
    fun observe(target: Element)

    fun disconnect()
}

internal external interface ResizeObserverEntry {
    val contentRect: DOMRectReadOnly
}

internal external class IntersectionObserver(
    callback: (entries: Array<IntersectionObserverEntry>, observer: IntersectionObserver) -> Unit,
) {
    fun observe(target: Element)

    fun disconnect()
}

internal external interface IntersectionObserverEntry {
    val isIntersecting: Boolean
}
