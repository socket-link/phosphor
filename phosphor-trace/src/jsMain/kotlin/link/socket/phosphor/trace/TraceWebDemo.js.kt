package link.socket.phosphor.trace

import kotlinx.browser.document
import kotlinx.browser.window

actual fun traceNowMillis(): Double = window.performance.now()

actual fun traceParam(name: String): String? = js("new URLSearchParams(location.search).get(name)") as String?

actual fun fetchBytes(
    url: String,
    onBytes: (ByteArray, Double) -> Unit,
) {
    // Kotlin/JS's ByteArray is an Int8Array, so the fetched buffer is handed over without a copy.
    val deliver = { bytes: ByteArray -> onBytes(bytes, 0.0) }
    js("fetch(url).then(function (r) { return r.arrayBuffer(); }).then(function (b) { deliver(new Int8Array(b)); })")
}

actual fun tracePost(json: String) {
    js("fetch('/report', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: json })")
}

actual fun traceSetText(
    id: String,
    text: String,
) {
    document.getElementById(id)?.textContent = text
}

actual fun traceUserAgent(): String = window.navigator.userAgent

actual fun traceTarget(): String = "js"
