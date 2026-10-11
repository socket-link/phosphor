package link.socket.phosphor.trace

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
 * The format is **versioned** via [formatVersion], and the version decides how
 * `TraceCodec` writes the cell channels:
 *
 * - **v1** stores them at full `Float` precision. Lossless, about 12.5–13.6 bytes
 *   per voxel-frame on a real Lumos capture.
 * - **v2** quantizes them: positions to 16-bit multiples of a
 *   power-of-two step (`2⁻¹²` lattice units for an orb, so at most `2⁻¹³` of
 *   error), scale to 16-bit multiples of `1/6561`, and colour and alpha to the
 *   8-bit sRGB byte Compose draws. Colour and scale project through
 *   `ComposeLattice` and `CliLattice` exactly as the originals do; only position
 *   moves, and by under an eighth of a pixel on a 1080 px canvas. About 3.4–4.3
 *   bytes per voxel-frame on a real capture.
 * - **v3** (current) is v2 with each frame's position codes stored as the
 *   difference from the previous frame's, falling back to absolute codes for
 *   the first frame and any frame whose cell count changed. The deltas are
 *   between integers, so v3 decodes to exactly the columns v2 would.
 *
 * In memory a trace decoded from v2 or v3 keeps its cell channels quantized — 11
 * bytes per voxel-frame, 13 with an alpha channel, against 32 as floats — and
 * [TracePlayer] dequantizes only the frame it builds. The [cellX]…[cellAlpha]
 * columns still read as floats whichever version a trace came from; on a v2 or v3
 * trace they are expanded on first access and then held, which costs the RAM
 * the quantized backing saves, so playback code never touches them. The values
 * are the quantized ones either way, so `decode(encode(t))` equals `t` only up
 * to quantization — and re-encodes to identical bytes. The field also lets a
 * decoder reject payloads it does not understand rather than silently misread
 * them.
 *
 * Build one from captured frames with [fromFrames] and project it back to a
 * `VoxelFrame` list with [toFrames]; serialize it with `TraceCodec`.
 *
 * @property formatVersion Format version of this trace and the format `TraceCodec` writes it in:
 *  v1 is full-precision columnar CBOR + gzip, v2 quantizes the cell channels, v3 also delta-codes positions
 *  against the previous frame.
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
 * @property ambient Per-frame ambient parameters, length [frameCount].
 * @property glyphs Per-frame glyph state, length [frameCount]; an entry is null when no glyph is active.
 */
@Serializable(with = VoxelTraceV1Serializer::class)
class VoxelTrace internal constructor(
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
    internal val cells: CellChannels,
    val ambient: List<VoxelAmbient>,
    val glyphs: List<VoxelGlyphState?>,
) {
    /**
     * A trace over full-precision float columns.
     *
     * @param cellX Concatenated per-cell x positions across all frames.
     * @param cellY Concatenated per-cell y positions across all frames.
     * @param cellZ Concatenated per-cell z positions across all frames.
     * @param cellScale Concatenated per-cell scale multipliers across all frames.
     * @param cellRed Concatenated per-cell red channel across all frames.
     * @param cellGreen Concatenated per-cell green channel across all frames.
     * @param cellBlue Concatenated per-cell blue channel across all frames.
     * @param cellAlpha Concatenated per-cell alpha across all frames; `NaN` encodes a null alpha.
     */
    constructor(
        formatVersion: Int,
        fps: Int,
        frameCount: Int,
        phosphorVersion: String,
        seed: Long,
        paramSnapshot: Map<String, String>,
        staticLattice: StaticLattice,
        segments: List<TraceSegment>,
        createdAtEpochMs: Long,
        ticks: LongArray,
        timestampsEpochMillis: LongArray,
        cellCounts: IntArray,
        cellX: FloatArray,
        cellY: FloatArray,
        cellZ: FloatArray,
        cellScale: FloatArray,
        cellRed: FloatArray,
        cellGreen: FloatArray,
        cellBlue: FloatArray,
        cellAlpha: FloatArray,
        ambient: List<VoxelAmbient>,
        glyphs: List<VoxelGlyphState?>,
    ) : this(
        formatVersion = formatVersion,
        fps = fps,
        frameCount = frameCount,
        phosphorVersion = phosphorVersion,
        seed = seed,
        paramSnapshot = paramSnapshot,
        staticLattice = staticLattice,
        segments = segments,
        createdAtEpochMs = createdAtEpochMs,
        ticks = ticks,
        timestampsEpochMillis = timestampsEpochMillis,
        cellCounts = cellCounts,
        cells = CellChannels.Floats(cellX, cellY, cellZ, cellScale, cellRed, cellGreen, cellBlue, cellAlpha),
        ambient = ambient,
        glyphs = glyphs,
    )

    // The public float columns. A float-backed trace hands out its own arrays;
    // a quantized one expands all eight on first access and holds them.
    private val floatColumns: Lazy<CellChannels.Floats> =
        if (cells is CellChannels.Floats) lazyOf(cells) else lazy { cells.toFloats() }

    /** Whether the [cellX]…[cellAlpha] float columns are resident: always for a float-backed trace. */
    internal val floatColumnsResident: Boolean get() = floatColumns.isInitialized()

    /** Concatenated per-cell x positions across all frames. Expanded on first access for a quantized trace. */
    val cellX: FloatArray get() = floatColumns.value.cellX

    /** Concatenated per-cell y positions across all frames. Expanded on first access for a quantized trace. */
    val cellY: FloatArray get() = floatColumns.value.cellY

    /** Concatenated per-cell z positions across all frames. Expanded on first access for a quantized trace. */
    val cellZ: FloatArray get() = floatColumns.value.cellZ

    /** Concatenated per-cell scale multipliers across all frames. Expanded on first access for a quantized trace. */
    val cellScale: FloatArray get() = floatColumns.value.cellScale

    /** Concatenated per-cell red channel across all frames. Expanded on first access for a quantized trace. */
    val cellRed: FloatArray get() = floatColumns.value.cellRed

    /** Concatenated per-cell green channel across all frames. Expanded on first access for a quantized trace. */
    val cellGreen: FloatArray get() = floatColumns.value.cellGreen

    /** Concatenated per-cell blue channel across all frames. Expanded on first access for a quantized trace. */
    val cellBlue: FloatArray get() = floatColumns.value.cellBlue

    /**
     * Concatenated per-cell alpha across all frames; `NaN` encodes a null alpha.
     * Expanded on first access for a quantized trace.
     */
    val cellAlpha: FloatArray get() = floatColumns.value.cellAlpha

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
            val frameCells = ArrayList<VoxelCell>(count)
            for (i in 0 until count) {
                frameCells += cells.cellAt(cursor + i)
            }
            cursor += count
            frames +=
                VoxelFrame(
                    tick = ticks[frameIndex],
                    timestampEpochMillis = timestampsEpochMillis[frameIndex],
                    resolution = staticLattice.resolution,
                    cells = frameCells,
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
            cells.contentEquals(other.cells) &&
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
        result = 31 * result + cells.contentHashCode()
        result = 31 * result + ambient.hashCode()
        result = 31 * result + glyphs.hashCode()
        return result
    }

    companion object {
        /** Current trace [formatVersion], stamped by [fromFrames]: v3, quantized cells with delta-coded positions. */
        const val CURRENT_FORMAT_VERSION: Int = 3

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

/**
 * The format-v1 wire shape of a [VoxelTrace]: field for field what the class
 * serialized as before it could hold quantized channels. CBOR writes field names
 * but no class name, so v1 bytes are unchanged; `TraceV1FixtureTest` holds that.
 */
@Serializable
internal class VoxelTraceWireV1(
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
)

/** Serializes a [VoxelTrace] as its float columns, through [VoxelTraceWireV1]. */
internal object VoxelTraceV1Serializer : KSerializer<VoxelTrace> {
    private val wire = VoxelTraceWireV1.serializer()

    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor =
        SerialDescriptor("link.socket.phosphor.trace.VoxelTrace", wire.descriptor)

    override fun serialize(
        encoder: Encoder,
        value: VoxelTrace,
    ) {
        encoder.encodeSerializableValue(
            wire,
            VoxelTraceWireV1(
                formatVersion = value.formatVersion,
                fps = value.fps,
                frameCount = value.frameCount,
                phosphorVersion = value.phosphorVersion,
                seed = value.seed,
                paramSnapshot = value.paramSnapshot,
                staticLattice = value.staticLattice,
                segments = value.segments,
                createdAtEpochMs = value.createdAtEpochMs,
                ticks = value.ticks,
                timestampsEpochMillis = value.timestampsEpochMillis,
                cellCounts = value.cellCounts,
                cellX = value.cellX,
                cellY = value.cellY,
                cellZ = value.cellZ,
                cellScale = value.cellScale,
                cellRed = value.cellRed,
                cellGreen = value.cellGreen,
                cellBlue = value.cellBlue,
                cellAlpha = value.cellAlpha,
                ambient = value.ambient,
                glyphs = value.glyphs,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): VoxelTrace {
        val decoded = decoder.decodeSerializableValue(wire)
        return VoxelTrace(
            formatVersion = decoded.formatVersion,
            fps = decoded.fps,
            frameCount = decoded.frameCount,
            phosphorVersion = decoded.phosphorVersion,
            seed = decoded.seed,
            paramSnapshot = decoded.paramSnapshot,
            staticLattice = decoded.staticLattice,
            segments = decoded.segments,
            createdAtEpochMs = decoded.createdAtEpochMs,
            ticks = decoded.ticks,
            timestampsEpochMillis = decoded.timestampsEpochMillis,
            cellCounts = decoded.cellCounts,
            cellX = decoded.cellX,
            cellY = decoded.cellY,
            cellZ = decoded.cellZ,
            cellScale = decoded.cellScale,
            cellRed = decoded.cellRed,
            cellGreen = decoded.cellGreen,
            cellBlue = decoded.cellBlue,
            cellAlpha = decoded.cellAlpha,
            ambient = decoded.ambient,
            glyphs = decoded.glyphs,
        )
    }
}
