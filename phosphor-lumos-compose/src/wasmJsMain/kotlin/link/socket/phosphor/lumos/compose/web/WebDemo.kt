@file:OptIn(ExperimentalWasmJsInterop::class)

package link.socket.phosphor.lumos.compose.web

import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.compose.LumosOrb
import link.socket.phosphor.lumos.probe.FramePhase
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.lumos.probe.FrameProbeSummary
import link.socket.phosphor.lumos.probe.RingBufferFrameProbe
import link.socket.phosphor.palette.AtmospherePresets

/*
 * PHO-69 spike: Compose for Web (wasmJs) host for LumosOrb, unchanged. Reads the same query
 * parameters as the DOM prototype's page and posts the same shape of result to /report.
 * Throwaway; nothing here merges.
 */

private fun jsNow(): Double = js("performance.now()")

private fun jsParam(name: String): String? = js("new URLSearchParams(location.search).get(name)")

private fun jsUserAgent(): String = js("navigator.userAgent")

private fun jsPost(body: String) {
    js("{ fetch('/report', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: body }); }")
}

private fun jsSetText(
    id: String,
    text: String,
) {
    js("{ var e = document.getElementById(id); if (e) e.textContent = text; }")
}

/** Records DRAW and PROJECT like RingBufferFrameProbe, plus the wall time of the first DRAW and frame-to-frame gaps. */
private class BenchProbe(
    private val targetFrames: Int,
    private val onDone: (BenchProbe) -> Unit,
) : FrameProbe {
    private val ring = RingBufferFrameProbe(capacity = 4_096)
    val gaps = DoubleArray(4_096)
    var frames = 0
    var firstFrameMs = Double.NaN
    private var lastDrawMs = Double.NaN
    private var done = false

    override val isEnabled: Boolean = true

    override fun record(
        phase: FramePhase,
        nanos: Long,
    ) {
        ring.record(phase, nanos)
        if (phase != FramePhase.DRAW) return
        val now = jsNow()
        if (frames == 0) firstFrameMs = now
        if (!lastDrawMs.isNaN() && frames < gaps.size) gaps[frames] = now - lastDrawMs
        lastDrawMs = now
        frames++
        if (frames >= targetFrames && !done) {
            done = true
            onDone(this)
        }
    }

    override fun summary(): FrameProbeSummary = ring.summary()

    override fun reset() = ring.reset()
}

private fun percentile(
    values: DoubleArray,
    q: Double,
): Double {
    if (values.isEmpty()) return Double.NaN
    val sorted = values.copyOf().also { it.sort() }
    return sorted[((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)]
}

private fun Double.r(): String = if (isNaN()) "null" else ((this * 1000.0).toLong() / 1000.0).toString()

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val mainStartedMs = jsNow()
    val resolution = jsParam("res")?.toIntOrNull() ?: 10
    val size = jsParam("size")?.toIntOrNull() ?: 200
    val frames = jsParam("frames")?.toIntOrNull() ?: 300
    val styleName = jsParam("style") ?: "squares"
    val atmosphereName = jsParam("atmosphere") ?: "idle"
    val run = jsParam("run") ?: ""
    val style =
        when (styleName.lowercase()) {
            "dots" -> SurfaceStyle.Dots
            "dashes" -> SurfaceStyle.Dashes
            else -> SurfaceStyle.Squares
        }
    val preset = AtmospherePresets.byName(atmosphereName) ?: AtmospherePresets.IDLE
    val source = LiveFrameSource(preset.copy(resolution = resolution))

    val probe =
        BenchProbe(frames) { p ->
            val summary = p.summary()
            val draw = summary.forPhase(FramePhase.DRAW)
            val project = summary.forPhase(FramePhase.PROJECT)
            val gaps = p.gaps.copyOf(minOf(p.frames, p.gaps.size)).let { if (it.size > 1) it.copyOfRange(1, it.size) else it }
            val json =
                """{"page":"compose-wasm","run":"$run","size":$size,"resolution":$resolution,"atmosphere":"$atmosphereName",""" +
                    """"style":"$styleName","frames":${p.frames},"samples":${draw.sampleCount},""" +
                    """"firstFrameMs":${p.firstFrameMs.r()},"mainStartedMs":${mainStartedMs.r()},""" +
                    """"renderP50":${(draw.p50Nanos / 1e6).r()},"renderP95":${(draw.p95Nanos / 1e6).r()},""" +
                    """"projectP50":${(project.p50Nanos / 1e6).r()},"projectP95":${(project.p95Nanos / 1e6).r()},""" +
                    """"frameGapP50":${percentile(gaps, 0.5).r()},"frameGapP95":${percentile(gaps, 0.95).r()},""" +
                    """"ua":"${jsUserAgent().replace("\"", "'")}"}"""
            jsSetText("result", json)
            jsPost(json)
        }

    val host = document.getElementById("orb") ?: document.body!!
    ComposeViewport(host) {
        LumosOrb(
            source = source,
            config = LumosConfig(style = style),
            modifier = Modifier.size(size.dp),
            probe = probe,
        )
    }
}
