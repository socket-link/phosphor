package link.socket.phosphor.trace

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.palette.AtmospherePresets

/**
 * PHO-44: v3 delta-codes position codes against the previous frame. The deltas
 * are between integers, so a v3 payload must decode to exactly the columns a v2
 * payload of the same trace does — anything else would be accumulated error.
 */
class TraceV3TemporalTest {
    @Test
    fun `v3 decodes to exactly the columns v2 decodes to`() {
        val random = Random(0x44)
        val traces = List(50) { randomTrace(random) } + realCapture()
        for ((index, trace) in traces.withIndex()) {
            val viaV2 = roundTrip(TraceTestData.withFormatVersion(trace, formatVersion = 2))
            val viaV3 = roundTrip(TraceTestData.withFormatVersion(trace, formatVersion = 3))

            assertEquals(TraceTestData.withFormatVersion(viaV2, formatVersion = 3), viaV3, "trace $index diverged")
        }
    }

    @Test
    fun `v2 stores absolute position codes and v3 stores previous-frame deltas`() {
        // Cell counts 2, 2, 3, 3: frame 0 is absolute, frame 1 a delta, frame 2
        // falls back to absolute because its count changed, frame 3 a delta.
        val xs = listOf(listOf(1, 2), listOf(4, -1), listOf(5, 6, 7), listOf(5, 9, 3))
        val trace = traceOfXCodes(xs)

        val v2 = TraceQuantization.toWire(TraceTestData.withFormatVersion(trace, formatVersion = 2))
        val v3 = TraceQuantization.toWire(TraceTestData.withFormatVersion(trace, formatVersion = 3))

        assertContentEquals(intArrayOf(1, 2, 4, -1, 5, 6, 7, 5, 9, 3), signedCodes(v2.cellX))
        assertContentEquals(intArrayOf(1, 2, 3, -3, 5, 6, 7, 0, 3, -4), signedCodes(v3.cellX))
        // Only positions change; every other channel is byte-identical to v2.
        assertContentEquals(v2.cellScale, v3.cellScale)
        assertContentEquals(v2.cellRed, v3.cellRed)
        assertContentEquals(v2.cellAlpha, v3.cellAlpha)
    }

    @Test
    fun `a jump wider than a 16-bit delta still decodes exactly`() {
        // Just inside ±8 lattice units keeps the 2⁻¹² step, so the codes reach
        // ±32767 and their difference overflows a Short. The delta wraps and must unwrap.
        val step = TraceQuantization.DEFAULT_POSITION_STEP
        val trace = traceOfXCodes(listOf(listOf(-32_358, 32_767), listOf(32_358, -32_767), listOf(-32_358, 0)))
        assertEquals(step, TraceTestData.positionStepOf(trace))

        val decoded = roundTrip(trace)

        assertContentEquals(trace.cellX, decoded.cellX)
    }

    @Test
    fun `temporal deltas invert for any 16-bit codes and cell counts`() {
        val random = Random(0x16)
        repeat(200) { iteration ->
            val cellCounts = IntArray(random.nextInt(0, 8)) { random.nextInt(0, 4) }
            // Random bytes in both planes: every signed 16-bit code is reachable.
            val planes = random.nextBytes(2 * cellCounts.sum())
            val wire = planes.copyOf()

            TraceQuantization.toTemporalDeltas(wire, cellCounts)
            TraceQuantization.fromTemporalDeltas(wire, cellCounts)

            assertContentEquals(planes, wire, "codes changed on iteration $iteration")
        }
    }

    @Test
    fun `a decoded v3 trace holds absolute codes resident exactly as v2 does`() {
        // PHO-45 keeps decoded channels quantized and reads any cell on its own,
        // so v3 must undo its deltas on decode rather than leave them resident.
        val trace = realCapture()
        val viaV2 = roundTrip(TraceTestData.withFormatVersion(trace, formatVersion = 2))
        val viaV3 = roundTrip(trace)

        val v2Cells = assertIs<CellChannels.Quantized>(viaV2.cells)
        val v3Cells = assertIs<CellChannels.Quantized>(viaV3.cells)
        assertContentEquals(v2Cells.cellX, v3Cells.cellX)
        assertContentEquals(v2Cells.cellY, v3Cells.cellY)
        assertContentEquals(v2Cells.cellZ, v3Cells.cellZ)
        assertEquals(TraceTestData.residentCellBytes(viaV2), TraceTestData.residentCellBytes(viaV3))
    }

    @Test
    fun `encoding a decoded v3 trace leaves its resident positions absolute`() {
        val decoded = roundTrip(realCapture())
        val cells = assertIs<CellChannels.Quantized>(decoded.cells)
        val before = listOf(cells.cellX, cells.cellY, cells.cellZ).map { it.copyOf() }

        val first = TraceCodec.encode(decoded)
        val second = TraceCodec.encode(decoded)

        assertContentEquals(first, second, "a second encode saw different positions")
        listOf(cells.cellX, cells.cellY, cells.cellZ).zip(before).forEach { (after, original) ->
            assertContentEquals(original, after, "encode rewrote the resident planes")
        }
    }

    @Test
    fun `real capture position deltas fit in a byte`() {
        // The premise of v3: a Lumos voxel moves a few dozen 2⁻¹² steps per frame
        // at 20 fps, so every delta fits in a byte and gzip sees a near-constant
        // high plane. PHO-42's recon measured the largest |Δ| at 19–80 steps.
        val wire = TraceQuantization.toWire(realCapture())

        for ((axis, channel) in listOf("x" to wire.cellX, "y" to wire.cellY, "z" to wire.cellZ)) {
            val deltas = signedCodes(channel).drop(wire.cellCounts.first())
            val largest = deltas.maxOf { abs(it) }
            assertTrue(largest <= Byte.MAX_VALUE, "largest $axis delta was $largest steps")
        }
    }

    private fun roundTrip(trace: VoxelTrace): VoxelTrace = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()

    /** One x position code per cell, per frame, at the default step; y and z fixed. */
    private fun traceOfXCodes(frames: List<List<Int>>): VoxelTrace =
        VoxelTrace.fromFrames(
            frames =
                frames.mapIndexed { index, xs ->
                    VoxelFrame(
                        tick = index.toLong(),
                        timestampEpochMillis = index * 50L,
                        resolution = 4,
                        cells =
                            xs.map { code ->
                                VoxelCell(
                                    x = code * TraceQuantization.DEFAULT_POSITION_STEP,
                                    y = 0.25f,
                                    z = -0.25f,
                                    scale = 0.9f,
                                    red = 0.4f,
                                    green = 0.5f,
                                    blue = 0.6f,
                                )
                            },
                        ambient = VoxelAmbient(0f, 0f, 0f, 0f, 0f, 0f, 0f),
                    )
                },
            fps = 20,
            phosphorVersion = "0.8.0",
            createdAtEpochMs = 0L,
        )

    /** Reads a 16-bit byte-plane channel back as signed codes. */
    private fun signedCodes(planes: ByteArray): IntArray {
        val n = planes.size / 2
        return IntArray(n) { ((planes[it].toInt() shl 8) or (planes[n + it].toInt() and 0xFF)).toShort().toInt() }
    }

    private fun randomTrace(random: Random): VoxelTrace {
        val frames = TraceTestData.randomFrames(random)
        return VoxelTrace.fromFrames(
            frames = frames,
            fps = 20,
            phosphorVersion = "0.8.0",
            createdAtEpochMs = 0L,
            segments = TraceTestData.randomSegments(frames.size, random),
        )
    }

    private fun realCapture(): VoxelTrace =
        Oscilloscope
            .capture(
                CaptureConfig(
                    params = CaptureParams(atmosphere = AtmospherePresets.IDLE),
                    seed = 0x10_5E55L,
                    durationMs = 500L,
                    fps = 20,
                ),
            ).getOrThrow()
}
