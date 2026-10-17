@file:OptIn(ExperimentalWasmJsInterop::class)

package link.socket.phosphor.trace

import kotlinx.browser.document

private fun jsNow(): Double = js("performance.now()")

actual fun traceNowMillis(): Double = jsNow()

private fun jsParam(name: String): String? = js("new URLSearchParams(location.search).get(name)")

actual fun traceParam(name: String): String? = jsParam(name)

private fun jsFetch(
    url: String,
    onBuffer: (JsAny) -> Unit,
) {
    js("{ fetch(url).then(function (r) { return r.arrayBuffer(); }).then(function (b) { onBuffer(new Int8Array(b)); }); }")
}

private fun jsLength(array: JsAny): Int = js("array.length")

private fun jsByteAt(
    array: JsAny,
    index: Int,
): Int = js("array[index]")

actual fun fetchBytes(
    url: String,
    onBytes: (ByteArray, Double) -> Unit,
) {
    jsFetch(url) { array ->
        // Wasm has no shared view of a JS buffer: the bytes are copied in one interop call each.
        val t0 = jsNow()
        val n = jsLength(array)
        val bytes = ByteArray(n)
        for (i in 0 until n) bytes[i] = jsByteAt(array, i).toByte()
        onBytes(bytes, jsNow() - t0)
    }
}

private fun jsPost(json: String) {
    js("{ fetch('/report', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: json }); }")
}

actual fun tracePost(json: String) = jsPost(json)

actual fun traceSetText(
    id: String,
    text: String,
) {
    document.getElementById(id)?.textContent = text
}

private fun jsUserAgent(): String = js("navigator.userAgent")

actual fun traceUserAgent(): String = jsUserAgent()

actual fun traceTarget(): String = "wasm"
