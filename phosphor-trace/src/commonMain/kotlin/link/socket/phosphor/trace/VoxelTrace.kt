package link.socket.phosphor.trace

import kotlinx.serialization.Serializable
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.VoxelGlyphState

/**
 * The static lattice descriptor carried once in a [VoxelTrace] header.
 *
 * This is deliberately minimal. The Phosphor rest lattice (per-voxel rest
 * position, unit direction, jitter) lives in `:phosphor-core`'s `VoxelSphere`
 * and is **not** reproduced here: the recorded unit is `VoxelFrame`, whose
 * `VoxelCell` positions are already transformed per frame (noise, surface bump,
 * breath pulse, Y-squash), so a frame recorder cannot recover the rest lattice
 * without coupling to a specific producer. The only datum that is genuinely
 * static across a recording is its lattice [resolution].
 *
 * @property resolution Lattice resolution shared by every frame in the trace.
 */
@Serializable
data class StaticLattice(
    val resolution: Int,
)

/**
 * A recorded `VoxelFrame` sequence plus the metadata needed to replay, segment,
 * and audit it — the foundational contract of the Phosphor trace pipeline.
 *
 * The format is **columnar**: a small header followed by per-frame dynamic
 * channel arrays. Every mutable `VoxelCell` attribute is concatenated across all
 * frames into its own primitive array (all x's, then all y's, and so on), which
 * groups same-channel bytes so the codec's gzip pass compresses them far better
 * than an interleaved array-of-structs would. Per-frame cell counts are stored
 * explicitly because a producer may omit voxels frame-to-frame, so `cells.size`
 * is not constant.
 *
 * The format is **versioned from v1** via [formatVersion]; lossy compression
 * tricks (quantization, inter-frame deltas) are reserved for a later version and
 * the field exists so a decoder can reject payloads it does not understand.
 *
 * Build one from captured frames with [fromFrames] and project it back to a
 * `VoxelFrame` list with [toFrames]; serialize it with `TraceCodec`.
 *
 * @property formatVersion Format version of this trace. v1 is full-precision columnar CBOR + gzip.
 * @property fps Frames per second the trace was captured at.
 * @property frameCount Number of frames in the trace.
 * @property phosphorVersion Phosphor library version that produced the trace.
 * @property seed Deterministic seed of the producer, recorded for reproducible replays.
 * @property paramSnapshot Serialized tuning parameters active during capture, as key/value pairs.
 * @property staticLattice Lattice geometry that is constant across the recording.
 * @property segments Named, replayable slices of the frame stream.
 * @property createdAtEpochMs Wall-clock creation time of the trace, epoch milliseconds.
 * @property ticks Per-frame monotonic frame counter, length [frameCount].
 * @property timestampsEpochMillis Per-frame wall-clock timestamp, length [frameCount].
 * @property cellCounts Per-frame cell count, length [frameCount]. Sums to the channel-array length.
 * @property cellX Concatenated per-cell x positions across all frames.
 * @property cellY Concatenated per-cell y positions across all frames.
 * @property cellZ Concatenated per-cell z positions across all frames.
 * @property cellScale Concatenated per-cell scale multipliers across all frames.
 * @property cellRed Concatenated per-cell red channel across all frames.
 * @property cellGreen Concatenated per-cell green channel across all frames.
 * @property cellBlue Concatenated per-cell blue channel across all frames.
 * @property cellAlpha Concatenated per-cell alpha across all frames; `NaN` encodes a null alpha.
 * @property ambient Per-frame ambient parameters, length [frameCount].
 * @property glyphs Per-frame glyph state, length [frameCount]; an entry is null when no glyph is active.
 */
@Serializable
class VoxelTrace(
    val formatVersion: Int,
    val fps: Int,
    val frameCount: Int,
    val phosphorVersion: String,
    val seed: Long,
    val paramSnapshot: Map<String, String>,
    val staticLattice: StaticLattice,
    val segments: List<TraceSegment>,
    val createdAtEpochMs: Long,
    val ticks: LongArray,
    val timestampsEpochMillis: LongArray,
    val cellCounts: IntArray,
    val cellX: FloatArray,
    val cellY: FloatArray,
    val cellZ: FloatArray,
    val cellScale: FloatArray,
    val cellRed: FloatArray,
    val cellGreen: FloatArray,
    val cellBlue: FloatArray,
    val cellAlpha: FloatArray,
    val ambient: List<VoxelAmbient>,
    val glyphs: List<VoxelGlyphState?>,
) {
    /**
     * Project this columnar trace back into the `VoxelFrame` sequence it was
     * recorded from. The result is structurally identical to the input of
     * [fromFrames] (a `NaN` alpha channel decodes back to a null alpha).
     */
    fun toFrames(): List<VoxelFrame> {
        val frames = ArrayList<VoxelFrame>(frameCount)
        var cursor = 0
        for (frameIndex in 0 until frameCount) {
            val count = cellCounts[frameIndex]
            val cells = ArrayList<VoxelCell>(count)
            for (i in 0 until count) {
                val cellIndex = cursor + i
                val alpha = cellAlpha[cellIndex]
                cells +=
                    VoxelCell(
                        x = cellX[cellIndex],
                        y = cellY[cellIndex],
                        z = cellZ[cellIndex],
                        scale = cellScale[cellIndex],
                        red = cellRed[cellIndex],
                        green = cellGreen[cellIndex],
                        blue = cellBlue[cellIndex],
                        alpha = if (alpha.isNaN()) null else alpha,
                    )
            }
            cursor += count
            frames +=
                VoxelFrame(
                    tick = ticks[frameIndex],
                    timestampEpochMillis = timestampsEpochMillis[frameIndex],
                    resolution = staticLattice.resolution,
                    cells = cells,
                    ambient = ambient[frameIndex],
                    glyph = glyphs[frameIndex],
                )
        }
        return frames
    }

    @Suppress("CyclomaticComplexMethod")
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoxelTrace) return false
        return formatVersion == other.formatVersion &&
            fps == other.fps &&
            frameCount == other.frameCount &&
            phosphorVersion == other.phosphorVersion &&
            seed == other.seed &&
            paramSnapshot == other.paramSnapshot &&
            staticLattice == other.staticLattice &&
            segments == other.segments &&
            createdAtEpochMs == other.createdAtEpochMs &&
            ticks.contentEquals(other.ticks) &&
            timestampsEpochMillis.contentEquals(other.timestampsEpochMillis) &&
            cellCounts.contentEquals(other.cellCounts) &&
            cellX.contentEquals(other.cellX) &&
            cellY.contentEquals(other.cellY) &&
            cellZ.contentEquals(other.cellZ) &&
            cellScale.contentEquals(other.cellScale) &&
            cellRed.contentEquals(other.cellRed) &&
            cellGreen.contentEquals(other.cellGreen) &&
            cellBlue.contentEquals(other.cellBlue) &&
            cellAlpha.contentEquals(other.cellAlpha) &&
            ambient == other.ambient &&
            glyphs == other.glyphs
    }

    override fun hashCode(): Int {
        var result = formatVersion
        result = 31 * result + fps
        result = 31 * result + frameCount
        result = 31 * result + phosphorVersion.hashCode()
        result = 31 * result + seed.hashCode()
        result = 31 * result + paramSnapshot.hashCode()
        result = 31 * result + staticLattice.hashCode()
        result = 31 * result + segments.hashCode()
        result = 31 * result + createdAtEpochMs.hashCode()
        result = 31 * result + ticks.contentHashCode()
        result = 31 * result + timestampsEpochMillis.contentHashCode()
        result = 31 * result + cellCounts.contentHashCode()
        result = 31 * result + cellX.contentHashCode()
        result = 31 * result + cellY.contentHashCode()
        result = 31 * result + cellZ.contentHashCode()
        result = 31 * result + cellScale.contentHashCode()
        result = 31 * result + cellRed.contentHashCode()
        result = 31 * result + cellGreen.contentHashCode()
        result = 31 * result + cellBlue.contentHashCode()
        result = 31 * result + cellAlpha.contentHashCode()
        result = 31 * result + ambient.hashCode()
        result = 31 * result + glyphs.hashCode()
        return result
    }

    companion object {
        /** Current trace [formatVersion] written by [fromFrames]. */
        const val CURRENT_FORMAT_VERSION: Int = 1

        /**
         * Record [frames] into a columnar [VoxelTrace].
         *
         * @param frames Captured frames in playback order; must be non-empty and share one `VoxelFrame.resolution`.
         * @param fps Frames per second the capture ran at; must be `> 0`.
         * @param phosphorVersion Phosphor library version that produced the frames.
         * @param createdAtEpochMs Wall-clock creation time, epoch milliseconds.
         * @param seed Deterministic producer seed; defaults to `0`.
         * @param paramSnapshot Serialized tuning parameters active during capture.
         * @param segments Named slices of the stream; each must fall within `0 until frames.size`.
         * @throws IllegalArgumentException if the inputs violate the constraints above.
         */
        fun fromFrames(
            frames: List<VoxelFrame>,
            fps: Int,
            phosphorVersion: String,
            createdAtEpochMs: Long,
            seed: Long = 0L,
            paramSnapshot: Map<String, String> = emptyMap(),
            segments: List<TraceSegment> = emptyList(),
        ): VoxelTrace {
            require(frames.isNotEmpty()) { "A VoxelTrace requires at least one frame" }
            require(fps > 0) { "fps must be > 0, was $fps" }

            val resolution = frames.first().resolution
            require(frames.all { it.resolution == resolution }) {
                "VoxelTrace requires a uniform lattice resolution across all frames"
            }

            val frameCount = frames.size
            segments.forEach { segment ->
                require(segment.endFrame < frameCount) {
                    "Segment '${segment.name}' endFrame ${segment.endFrame} is outside the " +
                        "trace's $frameCount frames"
                }
            }

            val totalCells = frames.sumOf { it.cells.size }
            val ticks = LongArray(frameCount)
            val timestamps = LongArray(frameCount)
            val cellCounts = IntArray(frameCount)
            val cellX = FloatArray(totalCells)
            val cellY = FloatArray(totalCells)
            val cellZ = FloatArray(totalCells)
            val cellScale = FloatArray(totalCells)
            val cellRed = FloatArray(totalCells)
            val cellGreen = FloatArray(totalCells)
            val cellBlue = FloatArray(totalCells)
            val cellAlpha = FloatArray(totalCells)
            val ambient = ArrayList<VoxelAmbient>(frameCount)
            val glyphs = ArrayList<VoxelGlyphState?>(frameCount)

            var cellIndex = 0
            frames.forEachIndexed { frameIndex, frame ->
                ticks[frameIndex] = frame.tick
                timestamps[frameIndex] = frame.timestampEpochMillis
                cellCounts[frameIndex] = frame.cells.size
                ambient += frame.ambient
                glyphs += frame.glyph
                for (cell in frame.cells) {
                    cellX[cellIndex] = cell.x
                    cellY[cellIndex] = cell.y
                    cellZ[cellIndex] = cell.z
                    cellScale[cellIndex] = cell.scale
                    cellRed[cellIndex] = cell.red
                    cellGreen[cellIndex] = cell.green
                    cellBlue[cellIndex] = cell.blue
                    cellAlpha[cellIndex] = cell.alpha ?: Float.NaN
                    cellIndex++
                }
            }

            return VoxelTrace(
                formatVersion = CURRENT_FORMAT_VERSION,
                fps = fps,
                frameCount = frameCount,
                phosphorVersion = phosphorVersion,
                seed = seed,
                paramSnapshot = paramSnapshot,
                staticLattice = StaticLattice(resolution),
                segments = segments,
                createdAtEpochMs = createdAtEpochMs,
                ticks = ticks,
                timestampsEpochMillis = timestamps,
                cellCounts = cellCounts,
                cellX = cellX,
                cellY = cellY,
                cellZ = cellZ,
                cellScale = cellScale,
                cellRed = cellRed,
                cellGreen = cellGreen,
                cellBlue = cellBlue,
                cellAlpha = cellAlpha,
                ambient = ambient,
                glyphs = glyphs,
            )
        }
    }
}
