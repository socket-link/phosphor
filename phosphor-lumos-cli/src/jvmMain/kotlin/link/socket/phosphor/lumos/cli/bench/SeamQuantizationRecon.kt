package link.socket.phosphor.lumos.cli.bench

import java.io.File
import kotlin.math.roundToInt
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.Oscilloscope
import link.socket.phosphor.trace.SeamError
import link.socket.phosphor.trace.TraceCodec
import link.socket.phosphor.trace.TraceSegment
import link.socket.phosphor.trace.VoxelTrace
import link.socket.phosphor.trace.seamErrors

/**
 * PHO-50 Task A recon: does [seamErrors] still mean anything after a v2 round trip?
 *
 * Captures every canonical preset as a 4 s loop at resolution 10, raw and with
 * the seam baked out (`seamBlendFrames = 20`), then encodes and decodes it
 * through [TraceCodec] (v2: Int16 positions at 2⁻¹², scale on a 1/6561 grid,
 * RGB8 colour). For each cell channel it reports the median frame step and the
 * seam ratio before and after, and how many cells' quantized value changes on a
 * median frame — the share that a sub-quantum motion leaves still.
 *
 * Runs at 20 fps, the rate the ticket names, and at 60 fps, where every
 * per-frame step is a third the size and quantization bites hardest.
 *
 * Writes `seam-quantization.md` into the output directory.
 *
 * Run: `./gradlew :phosphor-lumos-cli:runSeamQuantizationRecon -Pout=/abs/path`
 */
private const val RESOLUTION = 10
private const val LOOP_SECONDS = 4
private const val BLEND_FRAMES = 20
private val FPS_RATES = listOf(20, 60)

private val CELL_CHANNELS = listOf("position", "scale", "color")

private class Measured(
    val preset: String,
    val fps: Int,
    val baked: Boolean,
    val before: SeamError,
    val after: SeamError,
    val changedShare: Map<String, Float>,
)

fun main(args: Array<String>) {
    val out = File(args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: ".context/seam-quantization").apply { mkdirs() }

    val rows = ArrayList<Measured>()
    for (fps in FPS_RATES) {
        for ((name, preset) in AtmospherePresets.ALL) {
            for (baked in listOf(false, true)) {
                val loopFrames = LOOP_SECONDS * fps
                // The loop starts after a lead-in as long as the blend, so both variants cut the same frames.
                val start = BLEND_FRAMES
                val trace =
                    Oscilloscope
                        .capture(
                            CaptureConfig(
                                params = CaptureParams(atmosphere = preset.copy(resolution = RESOLUTION)),
                                durationMs = ((start + loopFrames) * 1_000L + fps - 1) / fps,
                                fps = fps,
                                segments = listOf(TraceSegment("loop", start, start + loopFrames - 1, loop = true)),
                                seamBlendFrames = if (baked) BLEND_FRAMES else 0,
                            ),
                        ).getOrThrow()
                val decoded = TraceCodec.decode(TraceCodec.encode(trace)).getOrThrow()
                rows +=
                    Measured(
                        preset = name,
                        fps = fps,
                        baked = baked,
                        before = trace.seamErrors().single(),
                        after = decoded.seamErrors().single(),
                        changedShare = changedShare(decoded, trace.segments.single()),
                    )
            }
        }
    }

    val report = report(rows)
    File(out, "seam-quantization.md").writeText(report)
    println(report)
    println("wrote ${out.absolutePath}")
}

/**
 * Per channel, the median over the segment's steps of the share of cells whose
 * decoded (that is, quantized) value differs from the frame before.
 */
private fun changedShare(
    trace: VoxelTrace,
    segment: TraceSegment,
): Map<String, Float> {
    val offsets = IntArray(trace.frameCount)
    for (frame in 1 until trace.frameCount) offsets[frame] = offsets[frame - 1] + trace.cellCounts[frame - 1]

    val shares = CELL_CHANNELS.associateWith { ArrayList<Float>() }
    for (from in segment.startFrame until segment.endFrame) {
        val count = trace.cellCounts[from]
        if (count == 0 || count != trace.cellCounts[from + 1]) continue
        val a = offsets[from]
        val b = offsets[from + 1]
        var position = 0
        var scale = 0
        var color = 0
        for (i in 0 until count) {
            if (trace.cellX[a + i] != trace.cellX[b + i] ||
                trace.cellY[a + i] != trace.cellY[b + i] ||
                trace.cellZ[a + i] != trace.cellZ[b + i]
            ) {
                position++
            }
            if (trace.cellScale[a + i] != trace.cellScale[b + i]) scale++
            if (trace.cellRed[a + i] != trace.cellRed[b + i] ||
                trace.cellGreen[a + i] != trace.cellGreen[b + i] ||
                trace.cellBlue[a + i] != trace.cellBlue[b + i]
            ) {
                color++
            }
        }
        shares.getValue("position") += position.toFloat() / count
        shares.getValue("scale") += scale.toFloat() / count
        shares.getValue("color") += color.toFloat() / count
    }
    return shares.mapValues { (_, values) -> values.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] } }
}

private fun report(rows: List<Measured>): String =
    buildString {
        appendLine("# Loop seams through a v2 round trip")
        appendLine()
        appendLine(
            "$LOOP_SECONDS s loops at resolution $RESOLUTION; baked rows use seamBlendFrames = $BLEND_FRAMES. " +
                "Each cell is `before → after` a TraceCodec v2 encode/decode.",
        )
        appendLine()
        for (fps in FPS_RATES) {
            appendLine("## $fps fps")
            appendLine()
            appendLine("### contentRatio")
            appendLine()
            appendLine("| preset | raw | baked |")
            appendLine("|---|---|---|")
            rows.filter { it.fps == fps }.groupBy { it.preset }.forEach { (preset, pair) ->
                val raw = pair.single { !it.baked }
                val baked = pair.single { it.baked }
                appendLine(
                    "| $preset | ${fmt(raw.before.contentRatio)} → ${fmt(raw.after.contentRatio)} | " +
                        "${fmt(baked.before.contentRatio)} → ${fmt(baked.after.contentRatio)} |",
                )
            }
            appendLine()
            appendLine("### Baked loops, per cell channel")
            appendLine()
            appendLine("Median step and seam jump are in channel units; cells changed is the median frame's share.")
            appendLine()
            appendLine("| preset | channel | median step | seam jump | ratio | cells changed |")
            appendLine("|---|---|---|---|---|---|")
            rows.filter { it.fps == fps && it.baked }.forEach { row ->
                val before = row.before.channels.toMap()
                val after = row.after.channels.toMap()
                CELL_CHANNELS.forEach { channel ->
                    val b = before.getValue(channel)
                    val a = after.getValue(channel)
                    appendLine(
                        "| ${row.preset} | $channel | ${sci(b.medianStep)} → ${sci(a.medianStep)} | " +
                            "${sci(b.jump)} → ${sci(a.jump)} | ${fmt(b.ratio)} → ${fmt(a.ratio)} | " +
                            "${pct(row.changedShare.getValue(channel))} |",
                    )
                }
            }
            appendLine()
        }
    }

private fun fmt(value: Float): String =
    when {
        value.isInfinite() -> "∞"
        value >= 10f -> value.roundToInt().toString()
        else -> "%.2f".format(value)
    }

private fun sci(value: Float): String = if (value.isInfinite()) "∞" else "%.2e".format(value)

private fun pct(value: Float): String = "${(value * 100).roundToInt()}%"
