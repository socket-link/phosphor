package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.palette.AtmospherePresets

class OscilloscopeConfigTest {
    private val small = AtmospherePresets.IDLE.copy(resolution = 2)

    private fun invalid(config: CaptureConfig): CaptureError.InvalidConfig {
        val result = Oscilloscope.capture(config)
        assertTrue(result.isFailure, "expected capture to fail for $config")
        return assertIs<CaptureError.InvalidConfig>(result.exceptionOrNull())
    }

    @Test
    fun `frameCount truncates durationMs times fps over one thousand`() {
        assertEquals(150, CaptureConfig(durationMs = 5_000L, fps = 30).frameCount)
        assertEquals(1, CaptureConfig(durationMs = 50L, fps = 30).frameCount)
        assertEquals(0, CaptureConfig(durationMs = 10L, fps = 30).frameCount)
    }

    @Test
    fun `non-positive fps is rejected`() {
        invalid(CaptureConfig(params = CaptureParams(small), durationMs = 1_000L, fps = 0))
    }

    @Test
    fun `non-positive duration is rejected`() {
        invalid(CaptureConfig(params = CaptureParams(small), durationMs = 0L, fps = 30))
    }

    @Test
    fun `a duration too short for one frame is rejected`() {
        invalid(CaptureConfig(params = CaptureParams(small), durationMs = 10L, fps = 30))
    }

    @Test
    fun `segment past the end of the clip is rejected`() {
        val error =
            invalid(
                CaptureConfig(
                    params = CaptureParams(small),
                    durationMs = 1_000L,
                    fps = 10,
                    segments = listOf(TraceSegment("idle", 0, 10)),
                ),
            )
        assertTrue("idle" in error.message.orEmpty())
    }

    @Test
    fun `cue outside the clip is rejected`() {
        invalid(
            CaptureConfig(
                params = CaptureParams(small),
                durationMs = 1_000L,
                fps = 10,
                cues = listOf(AtmosphereCue(atFrame = 10, atmosphere = AtmospherePresets.THINKING)),
            ),
        )
        invalid(
            CaptureConfig(
                params = CaptureParams(small),
                durationMs = 1_000L,
                fps = 10,
                cues = listOf(AtmosphereCue(atFrame = -1, atmosphere = AtmospherePresets.THINKING)),
            ),
        )
    }

    @Test
    fun `header fields come from the config rather than the environment`() {
        val config =
            CaptureConfig(
                params = CaptureParams(small, LumosRenderConfig(omitBelowScale = 0.01f)),
                seed = 99L,
                durationMs = 200L,
                fps = 10,
                segments = listOf(TraceSegment("idle", 0, 1, loop = true)),
                phosphorVersion = "0.7.0-test",
                createdAtEpochMs = 1_234L,
            )

        val trace = Oscilloscope.capture(config).getOrThrow()

        assertEquals(99L, trace.seed)
        assertEquals("0.7.0-test", trace.phosphorVersion)
        assertEquals(1_234L, trace.createdAtEpochMs)
        assertEquals(config.segments, trace.segments)
        assertEquals(2, trace.frameCount)
    }

    @Test
    fun `paramSnapshot records tuning inputs in a fixed key order`() {
        val config =
            CaptureConfig(
                params = CaptureParams(small, LumosRenderConfig(globalYSquashOverride = 0.5f)),
                durationMs = 100L,
                fps = 10,
                cues = listOf(AtmosphereCue(atFrame = 0, atmosphere = AtmospherePresets.READY)),
            )

        val snapshot = Oscilloscope.capture(config).getOrThrow().paramSnapshot

        assertEquals(Oscilloscope.snapshotParams(config).keys.toList(), snapshot.keys.toList())
        assertEquals("10", snapshot["capture.fps"])
        assertEquals("1", snapshot["capture.frameCount"])
        assertEquals("2", snapshot["atmosphere.resolution"])
        assertEquals("LONGITUDE", snapshot["atmosphere.pattern"])
        assertEquals("0.5", snapshot["render.globalYSquashOverride"])
        assertEquals("0", snapshot["cue.0.atFrame"])
        assertEquals(AtmospherePresets.READY.resolution.toString(), snapshot["cue.0.atmosphere.resolution"])
    }
}
