package link.socket.phosphor.trace

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame

class TraceCodecRoundTripTest {
    @Test
    fun `random traces round-trip within the v2 tolerance`() {
        val random = Random(0xC0FFEE)
        repeat(50) {
            val trace = randomTrace(random)

            val decoded = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()

            TraceTestData.assertV2Equivalent(trace, decoded)
        }
    }

    @Test
    fun `a decoded v2 or v3 trace re-encodes to identical bytes`() {
        val random = Random(0xBEEF)
        for (version in 2..3) {
            repeat(20) { iteration ->
                val encoded = TraceCodec.encode(TraceTestData.withFormatVersion(randomTrace(random), version))
                val decoded = TraceCodec.decode(encoded).getOrThrow()

                assertEquals(version, decoded.formatVersion)
                assertContentEquals(
                    encoded,
                    TraceCodec.encode(decoded),
                    "v$version re-encode drifted on iteration $iteration",
                )
                assertEquals(decoded, TraceCodec.decode(TraceCodec.encode(decoded)).getOrThrow())
            }
        }
    }

    @Test
    fun `v1 traces still round-trip losslessly through the v1 writer`() {
        val random = Random(0xC0FFEE)
        repeat(20) { iteration ->
            val v1 = TraceTestData.withFormatVersion(randomTrace(random), formatVersion = 1)

            val decoded = TraceCodec.decode(TraceCodec.encode(v1)).getOrThrow()

            assertEquals(v1, decoded, "v1 trace changed on iteration $iteration")
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
    fun `null alpha survives the v2 alpha channel`() {
        val random = Random(7)
        val frames = TraceTestData.randomFrames(random)
        val decoded =
            TraceCodec
                .decode(
                    TraceCodec.encode(
                        VoxelTrace.fromFrames(frames, fps = 24, phosphorVersion = "0.7.0", createdAtEpochMs = 0L),
                    ),
                ).getOrThrow()

        val originalNullAlphas = frames.flatMap { it.cells }.map { it.alpha == null }
        val decodedNullAlphas = decoded.toFrames().flatMap { it.cells }.map { it.alpha == null }
        assertEquals(originalNullAlphas, decodedNullAlphas)
    }

    @Test
    fun `single minimal trace round-trips`() {
        val trace = TraceTestData.sampleTrace(formatVersion = VoxelTrace.CURRENT_FORMAT_VERSION)

        val decoded = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()

        TraceTestData.assertV2Equivalent(trace, decoded)
        assertNull(decoded.toFrames().single().glyph)
        assertNull(decoded.toFrames().single().cells.single().alpha)
    }

    @Test
    fun `quantized scale never changes the luminance ramp character near a threshold`() {
        // CliLattice's ramp switches character at scale = (17 - 2k) / 18. Every
        // voxel of a Lumos orb shares one scale per frame, so a flip here would
        // flip the whole orb's glyph — v2's grid puts each threshold on a cell edge.
        val offsets = listOf(-1e-3f, -1e-4f, -1e-5f, -1e-6f, 1e-6f, 1e-5f, 1e-4f, 1e-3f)
        val scales =
            (0..8).flatMap { k ->
                val threshold = (17 - 2 * k) / 18f
                offsets.map { threshold + it }
            }
        val trace = singleFrameTrace(scales.map { cellWithScale(it) })

        val decoded = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()

        scales.forEachIndexed { i, scale ->
            assertEquals(
                TraceTestData.rampIndex(scale),
                TraceTestData.rampIndex(decoded.cellScale[i]),
                "scale $scale crossed a ramp threshold after quantization",
            )
        }
    }

    @Test
    fun `a tiny positive scale stays visible and a non-positive one stays hidden`() {
        val trace = singleFrameTrace(listOf(1e-6f, 0f, -0.25f).map { cellWithScale(it) })

        val decoded = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()

        assertTrue(decoded.cellScale[0] > 0f)
        assertEquals(0f, decoded.cellScale[1])
        assertEquals(0f, decoded.cellScale[2])
    }

    @Test
    fun `positions beyond the default range widen the step instead of overflowing`() {
        val trace = singleFrameTrace(listOf(cellWithScale(1f).copy(x = 100f, y = -0.3f)))

        val decoded = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()

        // ±8 lattice units fit the default 2⁻¹² step; 100 needs 2⁻⁸.
        assertEquals(1f / 256f, TraceTestData.positionStepOf(trace))
        assertTrue(abs(decoded.cellX[0] - 100f) <= 1f / 512f)
        TraceTestData.assertV2Equivalent(trace, decoded)
    }

    @Test
    fun `encode rejects cell values v2 cannot represent`() {
        val nanPosition = singleFrameTrace(listOf(cellWithScale(1f).copy(z = Float.NaN)))
        val infiniteColour = singleFrameTrace(listOf(cellWithScale(1f).copy(green = Float.POSITIVE_INFINITY)))
        val hugeScale = singleFrameTrace(listOf(cellWithScale(TraceQuantization.MAX_SCALE * 2f)))

        assertFailsWith<IllegalArgumentException> { TraceCodec.encode(nanPosition) }
        assertFailsWith<IllegalArgumentException> { TraceCodec.encode(infiniteColour) }
        assertFailsWith<IllegalArgumentException> { TraceCodec.encode(hugeScale) }
    }

    private fun randomTrace(random: Random): VoxelTrace {
        val frames = TraceTestData.randomFrames(random)
        return VoxelTrace.fromFrames(
            frames = frames,
            fps = random.nextInt(1, 121),
            phosphorVersion = "0.7.0",
            createdAtEpochMs = random.nextLong(0, 2_000_000_000_000),
            seed = random.nextLong(),
            paramSnapshot = mapOf("noise" to random.nextFloat().toString()),
            segments = TraceTestData.randomSegments(frames.size, random),
        )
    }

    private fun cellWithScale(scale: Float): VoxelCell =
        VoxelCell(x = 0.1f, y = 0.2f, z = 0.3f, scale = scale, red = 0.4f, green = 0.5f, blue = 0.6f)

    private fun singleFrameTrace(cells: List<VoxelCell>): VoxelTrace =
        VoxelTrace.fromFrames(
            frames =
                listOf(
                    VoxelFrame(
                        tick = 0L,
                        timestampEpochMillis = 0L,
                        resolution = 4,
                        cells = cells,
                        ambient = VoxelAmbient(0f, 0f, 0f, 0f, 0f, 0f, 0f),
                    ),
                ),
            fps = 20,
            phosphorVersion = "0.8.0",
            createdAtEpochMs = 0L,
        )
}
