package link.socket.phosphor.trace

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.VoxelGlyphState

/** Shared fixtures for the trace codec tests. */
internal object TraceTestData {
    private val glyphNames = listOf("SPARK", "PULSE", "BLOOM", "WAVE")
    private val segmentNames = listOf("idle", "thinking", "idle→thinking", "thinking→idle")

    /** A random, internally consistent frame stream with a uniform resolution. */
    fun randomFrames(random: Random): List<VoxelFrame> {
        val resolution = random.nextInt(0, 11)
        val frameCount = random.nextInt(1, 7)
        return List(frameCount) { frameIndex ->
            val cellCount = random.nextInt(0, 9)
            val cells =
                List(cellCount) {
                    VoxelCell(
                        x = random.nextFloat() * 4f - 2f,
                        y = random.nextFloat() * 4f - 2f,
                        z = random.nextFloat() * 4f - 2f,
                        scale = random.nextFloat(),
                        red = random.nextFloat(),
                        green = random.nextFloat(),
                        blue = random.nextFloat(),
                        alpha = if (random.nextBoolean()) random.nextFloat() else null,
                    )
                }
            VoxelFrame(
                tick = frameIndex.toLong(),
                timestampEpochMillis = random.nextLong(0, 1_000_000),
                resolution = resolution,
                cells = cells,
                ambient =
                    VoxelAmbient(
                        glowRed = random.nextFloat(),
                        glowGreen = random.nextFloat(),
                        glowBlue = random.nextFloat(),
                        glowIntensity = random.nextFloat(),
                        orbRotationX = random.nextFloat(),
                        orbRotationY = random.nextFloat(),
                        orbRotationZ = random.nextFloat(),
                    ),
                glyph =
                    if (random.nextBoolean()) {
                        VoxelGlyphState(
                            glyphName = glyphNames.random(random),
                            progress = random.nextFloat(),
                            red = random.nextFloat(),
                            green = random.nextFloat(),
                            blue = random.nextFloat(),
                        )
                    } else {
                        null
                    },
            )
        }
    }

    /** Up to two valid segments inside `0 until frameCount`. */
    fun randomSegments(
        frameCount: Int,
        random: Random,
    ): List<TraceSegment> =
        List(random.nextInt(0, 3)) {
            val start = random.nextInt(0, frameCount)
            val end = random.nextInt(start, frameCount)
            TraceSegment(
                name = segmentNames.random(random),
                startFrame = start,
                endFrame = end,
                loop = random.nextBoolean(),
            )
        }

    /** A minimal single-frame trace built directly, with a controllable [formatVersion]. */
    fun sampleTrace(formatVersion: Int): VoxelTrace =
        VoxelTrace(
            formatVersion = formatVersion,
            fps = 30,
            frameCount = 1,
            phosphorVersion = "0.7.0",
            seed = 1L,
            paramSnapshot = mapOf("pulseFrequency" to "0.5"),
            staticLattice = StaticLattice(4),
            segments = emptyList(),
            createdAtEpochMs = 0L,
            ticks = longArrayOf(0L),
            timestampsEpochMillis = longArrayOf(0L),
            cellCounts = intArrayOf(1),
            cellX = floatArrayOf(0.1f),
            cellY = floatArrayOf(0.2f),
            cellZ = floatArrayOf(0.3f),
            cellScale = floatArrayOf(0.9f),
            cellRed = floatArrayOf(0.4f),
            cellGreen = floatArrayOf(0.5f),
            cellBlue = floatArrayOf(0.6f),
            cellAlpha = floatArrayOf(Float.NaN),
            ambient = listOf(VoxelAmbient(0f, 0f, 0f, 0f, 0f, 0f, 0f)),
            glyphs = listOf(null),
        )

    /** Bytes [trace]'s cell channels hold in memory, however they are stored. */
    fun residentCellBytes(trace: VoxelTrace): Long =
        when (val cells = trace.cells) {
            is CellChannels.Floats -> 8L * Float.SIZE_BYTES * cells.size
            is CellChannels.Quantized ->
                listOf(
                    cells.cellX,
                    cells.cellY,
                    cells.cellZ,
                    cells.cellScale,
                    cells.cellRed,
                    cells.cellGreen,
                    cells.cellBlue,
                    cells.cellAlpha,
                ).sumOf { it.size.toLong() }
        }

    /** [trace] with every field kept but [VoxelTrace.formatVersion] replaced — the format `TraceCodec` writes it in. */
    fun withFormatVersion(
        trace: VoxelTrace,
        formatVersion: Int,
    ): VoxelTrace =
        VoxelTrace(
            formatVersion = formatVersion,
            fps = trace.fps,
            frameCount = trace.frameCount,
            phosphorVersion = trace.phosphorVersion,
            seed = trace.seed,
            paramSnapshot = trace.paramSnapshot,
            staticLattice = trace.staticLattice,
            segments = trace.segments,
            createdAtEpochMs = trace.createdAtEpochMs,
            ticks = trace.ticks,
            timestampsEpochMillis = trace.timestampsEpochMillis,
            cellCounts = trace.cellCounts,
            cellX = trace.cellX,
            cellY = trace.cellY,
            cellZ = trace.cellZ,
            cellScale = trace.cellScale,
            cellRed = trace.cellRed,
            cellGreen = trace.cellGreen,
            cellBlue = trace.cellBlue,
            cellAlpha = trace.cellAlpha,
            ambient = trace.ambient,
            glyphs = trace.glyphs,
        )

    /** The position step a v2 or v3 encode of [trace] uses. */
    fun positionStepOf(trace: VoxelTrace): Float {
        var maxAbs = 0f
        for (i in trace.cellX.indices) {
            maxAbs = maxOf(maxAbs, abs(trace.cellX[i]), abs(trace.cellY[i]), abs(trace.cellZ[i]))
        }
        return TraceQuantization.positionStepFor(maxAbs)
    }

    /**
     * `CliLattice.luminanceChar`'s ramp index, restated because `:phosphor-trace`
     * cannot see the CLI module. v2 promises a quantized scale never changes it.
     */
    fun rampIndex(scale: Float): Int = ((1f - scale.coerceIn(0f, 1f)) * 9).roundToInt().coerceIn(0, 9)

    /** The byte Compose packs an sRGB float into. v2 promises a quantized colour packs to the same byte. */
    fun composeByte(channel: Float): Int = (channel * 255.0f + 0.5f).toInt()

    /**
     * Assert [decoded] is [original] after one v2 or v3 round trip: every header
     * and per-frame field exact, and every cell within the v2 tolerance (PHO-42),
     * which v3 shares —
     * position within half a step, scale on the same luminance-ramp character and
     * the same side of zero, colour and alpha packing to the same Compose byte.
     */
    fun assertV2Equivalent(
        original: VoxelTrace,
        decoded: VoxelTrace,
    ) {
        assertTrue(original.formatVersion >= 2, "v${original.formatVersion} is not a quantized format")
        assertEquals(original.formatVersion, decoded.formatVersion)
        assertEquals(original.fps, decoded.fps)
        assertEquals(original.frameCount, decoded.frameCount)
        assertEquals(original.phosphorVersion, decoded.phosphorVersion)
        assertEquals(original.seed, decoded.seed)
        assertEquals(original.paramSnapshot, decoded.paramSnapshot)
        assertEquals(original.staticLattice, decoded.staticLattice)
        assertEquals(original.segments, decoded.segments)
        assertEquals(original.createdAtEpochMs, decoded.createdAtEpochMs)
        assertContentEquals(original.ticks, decoded.ticks)
        assertContentEquals(original.timestampsEpochMillis, decoded.timestampsEpochMillis)
        assertContentEquals(original.cellCounts, decoded.cellCounts)
        assertEquals(original.ambient, decoded.ambient)
        assertEquals(original.glyphs, decoded.glyphs)

        val halfStep = positionStepOf(original) / 2f
        assertEquals(original.cellX.size, decoded.cellX.size)
        for (i in original.cellX.indices) {
            assertTrue(
                abs(original.cellX[i] - decoded.cellX[i]) <= halfStep,
                "x off by more than half a step at cell $i",
            )
            assertTrue(
                abs(original.cellY[i] - decoded.cellY[i]) <= halfStep,
                "y off by more than half a step at cell $i",
            )
            assertTrue(
                abs(original.cellZ[i] - decoded.cellZ[i]) <= halfStep,
                "z off by more than half a step at cell $i",
            )

            val scale = original.cellScale[i]
            val decodedScale = decoded.cellScale[i]
            assertEquals(scale > 0f, decodedScale > 0f, "scale crossed zero at cell $i")
            assertEquals(rampIndex(scale), rampIndex(decodedScale), "scale changed ramp character at cell $i")
            assertTrue(
                abs(scale.coerceAtLeast(0f) - decodedScale) <= 1f / 6561f,
                "scale off by more than a grid step at cell $i",
            )

            assertEquals(composeByte(original.cellRed[i]), composeByte(decoded.cellRed[i]), "red byte at cell $i")
            assertEquals(composeByte(original.cellGreen[i]), composeByte(decoded.cellGreen[i]), "green byte at cell $i")
            assertEquals(composeByte(original.cellBlue[i]), composeByte(decoded.cellBlue[i]), "blue byte at cell $i")

            val alpha = original.cellAlpha[i]
            val decodedAlpha = decoded.cellAlpha[i]
            assertEquals(alpha.isNaN(), decodedAlpha.isNaN(), "alpha nullness at cell $i")
            if (!alpha.isNaN()) assertEquals(composeByte(alpha), composeByte(decodedAlpha), "alpha byte at cell $i")
        }
    }
}
