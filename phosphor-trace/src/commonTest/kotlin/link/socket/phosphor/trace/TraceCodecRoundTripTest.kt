package link.socket.phosphor.trace

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceCodecRoundTripTest {
    @Test
    fun `random traces encode then decode identically`() {
        val random = Random(0xC0FFEE)
        repeat(50) { iteration ->
            val frames = TraceTestData.randomFrames(random)
            val trace =
                VoxelTrace.fromFrames(
                    frames = frames,
                    fps = random.nextInt(1, 121),
                    phosphorVersion = "0.7.0",
                    createdAtEpochMs = random.nextLong(0, 2_000_000_000_000),
                    seed = random.nextLong(),
                    paramSnapshot = mapOf("noise" to random.nextFloat().toString()),
                    segments = TraceTestData.randomSegments(frames.size, random),
                )

            val encoded = TraceCodec.encode(trace)
            val decoded = TraceCodec.decode(encoded).getOrThrow()

            assertEquals(trace, decoded, "VoxelTrace mismatch on iteration $iteration")
            assertEquals(frames, decoded.toFrames(), "Frame projection mismatch on iteration $iteration")
        }
    }

    @Test
    fun `degenerate frames with empty cells round-trip`() {
        val frames = TraceTestData.randomFrames(Random(1)).map { it.copy(cells = emptyList()) }
        val trace =
            VoxelTrace.fromFrames(
                frames = frames,
                fps = 30,
                phosphorVersion = "0.7.0",
                createdAtEpochMs = 0L,
            )

        val decoded = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()

        assertEquals(frames, decoded.toFrames())
        assertTrue(decoded.toFrames().all { it.cells.isEmpty() })
    }

    @Test
    fun `null alpha survives the NaN channel encoding`() {
        val random = Random(7)
        val frames = TraceTestData.randomFrames(random)
        val decoded =
            TraceCodec
                .decode(
                    TraceCodec.encode(
                        VoxelTrace.fromFrames(frames, fps = 24, phosphorVersion = "0.7.0", createdAtEpochMs = 0L),
                    ),
                ).getOrThrow()

        val originalNullAlphas = frames.flatMap { it.cells }.count { it.alpha == null }
        val decodedNullAlphas = decoded.toFrames().flatMap { it.cells }.count { it.alpha == null }
        assertEquals(originalNullAlphas, decodedNullAlphas)
    }

    @Test
    fun `single minimal trace round-trips`() {
        val trace = TraceTestData.sampleTrace(formatVersion = VoxelTrace.CURRENT_FORMAT_VERSION)

        val decoded = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()

        assertEquals(trace, decoded)
        assertNull(decoded.toFrames().single().glyph)
    }
}
