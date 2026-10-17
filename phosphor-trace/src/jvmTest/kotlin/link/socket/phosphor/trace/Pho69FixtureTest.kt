package link.socket.phosphor.trace

import java.io.File
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.palette.AtmospherePresets

// PHO-69 spike: writes the 20 s, resolution-10 v3 trace the web pages decode, plus JVM reference
// timings and checksums the browser compares against. Output: build/pho-69/idle-20s.{vxt,json}.
class Pho69FixtureTest {
    @Test
    fun `writes the 20 s resolution-10 fixture and its JVM reference numbers`() {
        val dir = File("build/pho-69").apply { mkdirs() }
        val trace =
            Oscilloscope
                .capture(
                    CaptureConfig(
                        params = CaptureParams(atmosphere = AtmospherePresets.IDLE),
                        seed = 0x10_5E55L,
                        durationMs = 20_000L,
                        fps = 20,
                    ),
                ).getOrThrow()
        val bytes = TraceCodec.encode(trace)
        File(dir, "idle-20s.vxt").writeBytes(bytes)
        assertEquals(400, trace.frameCount)
        assertEquals(4_801, trace.cellCounts[0])

        // Warm, then time five decodes as the browser does.
        TraceCodec.decode(bytes).getOrThrow()
        val decodeMs = DoubleArray(5) { measureNanoTime { TraceCodec.decode(bytes).getOrThrow() } / 1e6 }
        val decoded = TraceCodec.decode(bytes).getOrThrow()
        var frames: List<VoxelFrame> = emptyList()
        val toFramesMs = measureNanoTime { frames = decoded.toFrames() } / 1e6
        val checksums = listOf(0, 199, 399).map { checksum(frames[it]) }
        val json =
            """{"bytes":${bytes.size},"frameCount":${trace.frameCount},"cells":${trace.cellCounts[0]},""" +
                """"jvmDecodeMs":[${decodeMs.joinToString(",") { it.r() }}],"jvmDecodeMedianMs":${decodeMs.sorted()[2].r()},""" +
                """"jvmToFramesMs":${toFramesMs.r()},"checksums":[${checksums.joinToString(",") { it.r() }}]}"""
        File(dir, "idle-20s.json").writeText(json)
        println("[PHO-69] $json")
    }

    private fun checksum(frame: VoxelFrame): Double =
        frame.cells.sumOf { (it.x + it.y + it.z + it.scale + it.red + it.green + it.blue).toDouble() }

    private fun Double.r(): String = ((this * 1000.0).toLong() / 1000.0).toString()
}
