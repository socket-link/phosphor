package link.socket.phosphor.trace

import kotlin.time.Duration.Companion.milliseconds
import link.socket.phosphor.lumos.VoxelFrame

/*
 * PHO-69 spike: fetch a JVM-encoded .vxt, decode it in the browser, time it, and post the numbers.
 * Shared over js and wasmJs; the five expects below are the only browser calls. Throwaway.
 */

expect fun traceNowMillis(): Double

expect fun traceParam(name: String): String?

expect fun fetchBytes(
    url: String,
    onBytes: (ByteArray, Double) -> Unit,
)

expect fun tracePost(json: String)

expect fun traceSetText(
    id: String,
    text: String,
)

expect fun traceUserAgent(): String

internal fun frameChecksum(frame: VoxelFrame): Double =
    frame.cells.sumOf { (it.x + it.y + it.z + it.scale + it.red + it.green + it.blue).toDouble() }

private fun Double.r(): String = if (isNaN()) "null" else ((this * 1000.0).toLong() / 1000.0).toString()

private fun median(values: DoubleArray): Double = values.copyOf().also { it.sort() }[values.size / 2]

fun main() {
    val fixture = traceParam("fixture") ?: "idle-20s"
    val run = traceParam("run") ?: ""
    val startedMs = traceNowMillis()
    traceSetText("info", "fetching $fixture.vxt …")
    fetchBytes("/fixtures/$fixture.vxt") { bytes, copyMs ->
        val fetchedMs = traceNowMillis()
        val decodeMs = DoubleArray(5)
        var trace: VoxelTrace? = null
        for (i in decodeMs.indices) {
            val a = traceNowMillis()
            val result = TraceCodec.decode(bytes)
            decodeMs[i] = traceNowMillis() - a
            trace =
                result.getOrElse { error ->
                    val json = """{"page":"trace-${traceTarget()}","run":"$run","error":"${error.message?.replace("\"", "'")}"}"""
                    traceSetText("result", json)
                    tracePost(json)
                    return@fetchBytes
                }
        }
        val decoded = trace!!
        val g0 = traceNowMillis()
        val payload = PureGzip.gunzip(bytes)
        val gunzipMs = traceNowMillis() - g0

        val player = TracePlayer(decoded)
        val p0 = traceNowMillis()
        var stepped = 0
        for (i in 0 until 200) {
            player.frameAt((i * 100).milliseconds)
            stepped++
        }
        val stepMs = (traceNowMillis() - p0) / stepped

        val f0 = traceNowMillis()
        val frames = decoded.toFrames()
        val toFramesMs = traceNowMillis() - f0
        val checks = listOf(0, 199, 399).filter { it < frames.size }.map { frameChecksum(frames[it]) }

        val json =
            """{"page":"trace-${traceTarget()}","run":"$run","fixture":"$fixture","bytes":${bytes.size},""" +
                """"payloadBytes":${payload.size},"frameCount":${decoded.frameCount},"cells":${decoded.cellCounts[0]},""" +
                """"fetchMs":${(fetchedMs - startedMs - copyMs).r()},"copyMs":${copyMs.r()},""" +
                """"decodeMs":[${decodeMs.joinToString(",") { it.r() }}],"decodeMedianMs":${median(decodeMs).r()},""" +
                """"decodeFirstMs":${decodeMs[0].r()},"gunzipMs":${gunzipMs.r()},"frameAtMs":${stepMs.r()},""" +
                """"toFramesMs":${toFramesMs.r()},"checksums":[${checks.joinToString(",") { it.r() }}],""" +
                """"ua":"${traceUserAgent().replace("\"", "'")}"}"""
        traceSetText("info", "decoded ${decoded.frameCount} frames in ${median(decodeMs).r()} ms (median of 5)")
        traceSetText("result", json)
        tracePost(json)
    }
}

expect fun traceTarget(): String
