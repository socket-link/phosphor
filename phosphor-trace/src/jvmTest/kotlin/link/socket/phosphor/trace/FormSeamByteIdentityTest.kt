package link.socket.phosphor.trace

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
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
 */
class FormSeamByteIdentityTest {
    @Test
    fun `a 20-frame capture encodes to the bytes it did before the form seam`() {
        val resolution = AtmospherePresets.IDLE.resolution
        val config =
            CaptureConfig(
                params = CaptureParams(atmosphere = AtmospherePresets.IDLE.copy(resolution = resolution)),
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

        val trace = Oscilloscope.capture(config).getOrThrow()
        val payload = gzipDecompress(TraceCodec.encode(trace))

        assertEquals(20, trace.frameCount)
        assertEquals(EXPECTED_PAYLOAD_SHA256, sha256Hex(payload), "encoded payload changed; the form seam moved a cell")
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
