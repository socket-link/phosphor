package link.socket.phosphor.trace

import kotlin.random.Random
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
}
