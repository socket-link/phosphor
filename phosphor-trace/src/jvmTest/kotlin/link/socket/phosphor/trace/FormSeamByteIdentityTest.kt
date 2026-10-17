package link.socket.phosphor.trace

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.LumosSequence
import link.socket.phosphor.lumos.LumosStep
import link.socket.phosphor.palette.AtmospherePresets

/**
 * PHO-55 Task D: the `LumosForm` seam moves nothing.
 *
 * A 20-frame capture — ten frames of IDLE, then a cue into THINKING so a
 * pattern crossfade, a colour crossfade and both presets' bump and noise all
 * pass through the geometry — is encoded and its CBOR payload digested. The
 * digest below was taken on the tree *before* `VoxelFrameBuilder` read its
 * positions from a form (commit `a12ba0b`), so any drift in a cell's rest
 * position, derived angle, radius or jitter changes it.
 *
 * The payload is digested after gzip is undone: the compressed framing belongs
 * to the platform's zlib, not to the capture, and `TraceCodec.encode` is
 * otherwise a pure function of the frames.
 *
 * PHO-82: an interactive orb is captured to the same bytes. The Oscilloscope's
 * scene has no pointer, so nothing parts, and the render config's `interactive`
 * is not recorded.
 *
 * PHO-65 extends it: the same clip scripted as a `LumosSequence` — an `idle`
 * loop step and an `idle→thinking` step, half a second each — must produce the
 * same frames and segments. Only `paramSnapshot` differs, by design: it records
 * the sequence's JSON instead of the cue, so the digest is taken with the cue
 * capture's snapshot put in its place.
 */
class FormSeamByteIdentityTest {
    @Test
    fun `a 20-frame capture encodes to the bytes it did before the form seam`() {
        val trace = Oscilloscope.capture(config()).getOrThrow()
        val payload = gzipDecompress(TraceCodec.encode(trace))

        assertEquals(20, trace.frameCount)
        assertEquals(EXPECTED_PAYLOAD_SHA256, sha256Hex(payload), "encoded payload changed; the form seam moved a cell")
    }

    @Test
    fun `with interactive on the capture still encodes to those bytes`() {
        val trace = Oscilloscope.capture(config(LumosRenderConfig(interactive = true))).getOrThrow()
        val payload = gzipDecompress(TraceCodec.encode(trace))

        assertEquals(EXPECTED_PAYLOAD_SHA256, sha256Hex(payload), "an interactive orb's capture moved a cell")
    }

    @Test
    fun `the same clip scripted as a sequence encodes to the same bytes`() {
        val sequence =
            LumosSequence(
                steps =
                    listOf(
                        LumosStep("idle", 0.5f, atmospherePreset = "idle", loop = true),
                        LumosStep("idle→thinking", 0.5f, atmospherePreset = "thinking"),
                    ),
            )
        val config =
            CaptureConfig.forSequence(
                sequence = sequence,
                fps = 20,
                params = CaptureParams(atmosphere = AtmospherePresets.IDLE),
            )

        val captured = Oscilloscope.capture(config).getOrThrow()
        val trace =
            VoxelTrace.fromFrames(
                frames = captured.toFrames(),
                fps = captured.fps,
                phosphorVersion = captured.phosphorVersion,
                createdAtEpochMs = captured.createdAtEpochMs,
                seed = captured.seed,
                paramSnapshot = Oscilloscope.snapshotParams(config()),
                segments = captured.segments,
            )
        val payload = gzipDecompress(TraceCodec.encode(trace))

        assertEquals(1_000L, config.durationMs)
        assertEquals(config().segments, captured.segments)
        assertEquals(
            EXPECTED_PAYLOAD_SHA256,
            sha256Hex(payload),
            "a sequence capture differs from the cue capture it restates",
        )
    }

    private fun config(renderConfig: LumosRenderConfig = LumosRenderConfig()): CaptureConfig {
        val resolution = AtmospherePresets.IDLE.resolution
        return CaptureConfig(
            params =
                CaptureParams(
                    atmosphere = AtmospherePresets.IDLE.copy(resolution = resolution),
                    renderConfig = renderConfig,
                ),
            durationMs = 1_000L,
            fps = 20,
            segments = listOf(TraceSegment("idle", 0, 9, loop = true), TraceSegment("idle→thinking", 10, 19)),
            cues =
                listOf(
                    AtmosphereCue(
                        atFrame = 10,
                        atmosphere = AtmospherePresets.THINKING.copy(resolution = resolution),
                    ),
                ),
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    private companion object {
        const val EXPECTED_PAYLOAD_SHA256: String =
            "60d713c1e5c1b4acc4b5939bd36cf848acb3027cc430b8d7ee204df2c32b4c33"
    }
}
