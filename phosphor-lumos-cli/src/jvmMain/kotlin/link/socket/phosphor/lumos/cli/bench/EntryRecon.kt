package link.socket.phosphor.lumos.cli.bench

import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.trace.AtmosphereCue
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.FrameBlend
import link.socket.phosphor.trace.OneShotEntry
import link.socket.phosphor.trace.Oscilloscope
import link.socket.phosphor.trace.SeamChannel
import link.socket.phosphor.trace.TraceSegment
import link.socket.phosphor.trace.TraceStateMachine
import link.socket.phosphor.trace.TransitionPolicy
import link.socket.phosphor.trace.VoxelTrace
import link.socket.phosphor.trace.seamError

/**
 * PHO-46 recon: how far does a recorded one-shot jump when it is entered from
 * an arbitrary loop frame, and what does each way of not jumping cost?
 *
 * Captures `StateMachineDemo`'s script with 4 s loops, once as captured and
 * once with a baked seam blend, then drives a real [TraceStateMachine] into
 * each recorded transition from **every** frame of the loop it leaves. For each
 * entry it records the frames a viewer sees: the last loop frame, then the
 * window that follows the request. Every step in that window is measured with
 * the [seamError] channels and divided by the outgoing loop's median step, as
 * PHO-43 measured seams; a Compose raster of the same frames is measured the
 * same way. Rotation is carried by the machine, so it is left out of the trace
 * channels but stays in the raster.
 *
 * The mechanisms are [OneShotEntry.Immediate], [OneShotEntry.BlendIn] at three
 * lengths, and "wait for the seam", which is Immediate entered from the loop's
 * last frame — the sweep already contains it. The best loop frame to enter from
 * is reported too: it bounds what any "wait for a good frame" policy could do.
 *
 * Writes `entry-recon.md` and `entry-strip-*.png` into the output directory.
 *
 * Run: `./gradlew :phosphor-lumos-cli:runEntryRecon -Pout=/abs/path`
 */
private const val FPS = 24
private const val LOOP_FRAMES = 4 * FPS
private const val SEAM_BLEND_FRAMES = 12
private val BLEND_MS = listOf(250L, 500L, 1_000L)

/** Recorded frames after the request that each entry is measured over: the longest blend, plus slack. */
private val WINDOW_FRAMES = (BLEND_MS.max() * FPS / 1_000L).toInt() + 2

/** Rasterize every [COMPOSE_STRIDE]th entry frame; the trace channels cover all of them. */
private const val COMPOSE_STRIDE = 4

private const val BLEND_TIMING_WARMUP = 200
private const val BLEND_TIMING_SAMPLES = 500

private val CONTENT_CHANNELS = listOf("position", "scale", "color", "glow")

private const val IDLE = "idle"
private const val THINKING = "thinking"
private const val UNCERTAIN = "uncertain"

/** `StateMachineDemo`'s script, with 4 s loops. Transitions keep the demo's settle padding. */
private val SCRIPT: List<Triple<String, Int, AtmosphereState?>> =
    listOf(
        Triple(IDLE, LOOP_FRAMES, null),
        Triple("$IDLE→$THINKING", 20 + SEAM_BLEND_FRAMES, AtmospherePresets.THINKING),
        Triple(THINKING, LOOP_FRAMES, null),
        Triple("$THINKING→$IDLE", 24 + SEAM_BLEND_FRAMES, AtmospherePresets.IDLE),
        Triple("$IDLE→$UNCERTAIN", 34 + SEAM_BLEND_FRAMES, AtmospherePresets.UNCERTAIN),
        Triple(UNCERTAIN, LOOP_FRAMES, null),
        Triple("$UNCERTAIN→$IDLE", 28 + SEAM_BLEND_FRAMES, AtmospherePresets.IDLE),
    )

private val TRANSITIONS = listOf(IDLE to THINKING, THINKING to IDLE, IDLE to UNCERTAIN, UNCERTAIN to IDLE)

private class EntryMechanism(
    val label: String,
    val entry: OneShotEntry,
)

private val MECHANISMS: List<EntryMechanism> =
    listOf(EntryMechanism("Immediate", OneShotEntry.Immediate)) +
        BLEND_MS.map { EntryMechanism("BlendIn ${it}ms", OneShotEntry.BlendIn(it)) }

/** Worst step in one entry's window, over the outgoing loop's median step. */
private class EntryResult(
    val entryFrame: Int,
    val content: Float,
    val compose: Float?,
)

private class Medians(
    val channels: Map<String, Float>,
    val compose: Float,
) {
    /**
     * Content channels the loop moves on its own. One it holds still — the
     * halo, in a steady atmosphere — has no ordinary step to compare against,
     * and every change there is the transition's authored motion, not a pop.
     */
    val moving: List<String> = CONTENT_CHANNELS.filter { channels.getValue(it) > 0f }
}

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = File(args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: ".context/entry-recon").apply { mkdirs() }

    val report = StringBuilder()
    report.appendLine("# PHO-46 entry recon")
    report.appendLine()
    report.appendLine(
        "$FPS fps, resolution 10, ${LOOP_FRAMES / FPS} s loops. Ratio = worst step from the last loop frame " +
            "through $WINDOW_FRAMES frames after the request, over the outgoing loop's median step. ~1 is an " +
            "ordinary frame. Trace channels: worst of position/scale/colour/glow. Sweep over every loop frame " +
            "(Compose: every ${COMPOSE_STRIDE}th).",
    )

    for ((label, blendFrames) in listOf(
        "as captured" to 0,
        "baked seam blend ($SEAM_BLEND_FRAMES frames)" to SEAM_BLEND_FRAMES,
    )) {
        val trace = capture(blendFrames)
        val recorded = trace.toFrames()
        report.appendLine()
        report.appendLine("## $label")
        report.appendLine()
        report.appendLine(
            "| transition | mechanism | median | p90 | max | at seam | best frame | Compose median | Compose max | " +
                "own motion | loop's largest step |",
        )
        report.appendLine("|---|---|---|---|---|---|---|---|---|---|---|")

        for ((from, to) in TRANSITIONS) {
            val loop = trace.segment(from)
            val oneShot = trace.segment(TraceStateMachine.transitionName(from, to))
            val medians = medians(trace, recorded, loop)
            val own = ownMotion(recorded, oneShot, medians)
            val loopLargest = loopLargest(recorded, loop, medians)

            val sweeps = MECHANISMS.associateWith { mechanism -> sweep(trace, from, to, loop, mechanism, medians) }
            report.appendLine(
                "| $from→$to | *channels counted: ${medians.moving.joinToString("/")}* | | | | | | | | | |",
            )
            sweeps.forEach { (mechanism, results) ->
                val content = results.map(EntryResult::content).sorted()
                val compose = results.mapNotNull(EntryResult::compose).sorted()
                val atSeam = results.last().content
                val best = results.minBy(EntryResult::content)
                report.appendLine(
                    "| $from→$to | ${mechanism.label} | ${fmt(percentile(content, 0.5f))} | " +
                        "${fmt(percentile(content, 0.9f))} | ${fmt(content.last())} | ${fmt(atSeam)} | " +
                        "${fmt(best.content)} @${best.entryFrame} | ${fmt(percentile(compose, 0.5f))} | " +
                        "${fmt(compose.last())} | ${fmt(own.first)} (${fmt(own.second)} px) | " +
                        "${fmt(loopLargest.first)} (${fmt(loopLargest.second)} px) |",
                )
            }

            if (from == IDLE && to == THINKING) {
                val worst = sweeps.getValue(MECHANISMS.first()).maxBy(EntryResult::content).entryFrame
                ImageIO.write(
                    strip(trace, from, to, worst, label),
                    "png",
                    File(out, "entry-strip-${if (blendFrames == 0) "captured" else "baked"}.png"),
                )
            }
        }

        report.appendLine()
        report.appendLine(blendCost(recorded))
    }

    File(out, "entry-recon.md").writeText(report.toString())
    println(report)
    println("wrote ${out.absolutePath}")
}

// ----------------------------------------------------------------------
// Capture
// ----------------------------------------------------------------------

private fun capture(seamBlendFrames: Int): VoxelTrace {
    val segments = ArrayList<TraceSegment>()
    val cues = ArrayList<AtmosphereCue>()
    var cursor = 0
    SCRIPT.forEach { (name, frames, cue) ->
        segments += TraceSegment(name, cursor, cursor + frames - 1, loop = cue == null)
        cue?.let { cues += AtmosphereCue(atFrame = cursor, atmosphere = it) }
        cursor += frames
    }
    return Oscilloscope
        .capture(
            CaptureConfig(
                params = CaptureParams(atmosphere = AtmospherePresets.IDLE),
                seed = 0x10_5E55L,
                durationMs = (cursor * 1_000L + FPS - 1) / FPS,
                fps = FPS,
                segments = segments,
                cues = cues,
                seamBlendFrames = seamBlendFrames,
            ),
        ).getOrThrow()
}

private fun VoxelTrace.segment(name: String): TraceSegment = segments.first { it.name == name }

// ----------------------------------------------------------------------
// Measurement
// ----------------------------------------------------------------------

/** The outgoing loop's median step per trace channel, and through the Compose raster. */
private fun medians(
    trace: VoxelTrace,
    frames: List<VoxelFrame>,
    loop: TraceSegment,
): Medians {
    val seam = trace.seamError(loop)
    val rasters = (loop.startFrame..loop.endFrame).map { raster(frames[it]) }
    val steps = rasters.zipWithNext { a, b -> pixelDelta(a, b) }.sorted()
    return Medians(
        channels = seam.channels.toMap().mapValues { it.value.medianStep },
        compose = steps[steps.size / 2],
    )
}

/** Worst step over a list of displayed frames, trace channels and (optionally) raster, over the loop's medians. */
private fun worstStep(
    frames: List<VoxelFrame>,
    medians: Medians,
    rasterize: Boolean,
): Pair<Float, Float?> {
    val content =
        frames.zipWithNext().maxOf { pair ->
            val distances = channelDistances(pair)
            medians.moving.maxOf { SeamChannel(distances.getValue(it), medians.channels.getValue(it)).ratio }
        }
    val compose =
        if (rasterize) {
            frames.map(::raster).zipWithNext { a, b -> SeamChannel(pixelDelta(a, b), medians.compose).ratio }.max()
        } else {
            null
        }
    return content to compose
}

/** The one-shot's own worst step over the same window, entered as recorded: the motion it was authored with. */
private fun ownMotion(
    frames: List<VoxelFrame>,
    oneShot: TraceSegment,
    medians: Medians,
): Pair<Float, Float> {
    val last = minOf(oneShot.endFrame, oneShot.startFrame + WINDOW_FRAMES)
    val (content, compose) = worstStep((oneShot.startFrame..last).map { frames[it] }, medians, rasterize = true)
    return content to checkNotNull(compose)
}

/** The outgoing loop's largest ordinary step: a pop smaller than this is something the loop already does. */
private fun loopLargest(
    frames: List<VoxelFrame>,
    loop: TraceSegment,
    medians: Medians,
): Pair<Float, Float> {
    val (content, compose) = worstStep((loop.startFrame..loop.endFrame).map { frames[it] }, medians, rasterize = true)
    return content to checkNotNull(compose)
}

private fun sweep(
    trace: VoxelTrace,
    from: String,
    to: String,
    loop: TraceSegment,
    mechanism: EntryMechanism,
    medians: Medians,
): List<EntryResult> =
    (0 until loop.frameCount).map { entryFrame ->
        val frames = entryWindow(trace, from, to, mechanism.entry, entryFrame)
        val (content, compose) = worstStep(frames, medians, rasterize = entryFrame % COMPOSE_STRIDE == 0)
        EntryResult(entryFrame, content, compose)
    }

/**
 * What a viewer sees around one request: the loop frame on screen when it
 * arrives, the frame the machine shows the instant it is accepted, then one
 * frame per recorded step.
 */
private fun entryWindow(
    trace: VoxelTrace,
    from: String,
    to: String,
    entry: OneShotEntry,
    entryFrame: Int,
): List<VoxelFrame> {
    val machine =
        TraceStateMachine(
            trace = trace,
            initialState = from,
            policy = TransitionPolicy.FinishLoopThenSwitch,
            oneShotEntry = entry,
        )
    val frames = ArrayList<VoxelFrame>(WINDOW_FRAMES + 2)
    frames += machine.advance(machine.frameDuration * entryFrame)
    machine.requestState(to).getOrThrow()
    frames += machine.currentFrame
    repeat(WINDOW_FRAMES) { frames += machine.advance(machine.frameDuration) }
    return frames
}

/** Median wall time of one [FrameBlend.transduce] on this trace's frames, and what a blend-in spends per switch. */
private fun blendCost(frames: List<VoxelFrame>): String {
    val a = frames.first()
    val b = frames.last()
    repeat(BLEND_TIMING_WARMUP) { FrameBlend(a, b, it.toFloat() / BLEND_TIMING_WARMUP).transduce() }
    val samples =
        LongArray(BLEND_TIMING_SAMPLES) {
            val start = System.nanoTime()
            FrameBlend(a, b, it.toFloat() / BLEND_TIMING_SAMPLES).transduce()
            System.nanoTime() - start
        }.sorted()
    val micros = samples[samples.size / 2] / 1_000f
    val perSwitch =
        BLEND_MS.joinToString {
                ms ->
            "${ms}ms: ${(ms * FPS / 1_000L)} blends, ${fmt(micros * ms * FPS / 1_000f / 1_000f)} ms"
        }
    return "Blend cost (JVM, ${a.cells.size} cells): median ${fmt(
        micros,
    )} µs per `FrameBlend.transduce`; per switch — $perSwitch."
}

// ----------------------------------------------------------------------
// Output
// ----------------------------------------------------------------------

private fun percentile(
    sorted: List<Float>,
    p: Float,
): Float = if (sorted.isEmpty()) Float.NaN else sorted[((sorted.size - 1) * p).roundToInt()]

private fun fmt(value: Float): String =
    when {
        value.isNaN() -> "–"
        value.isInfinite() -> "∞"
        value >= 10f -> value.roundToInt().toString()
        else -> "%.2f".format(value)
    }

/** Compose rasters around the worst Immediate entry: one row per mechanism, columns at fixed offsets after the request. */
private fun strip(
    trace: VoxelTrace,
    from: String,
    to: String,
    entryFrame: Int,
    variant: String,
): BufferedImage {
    val offsets = listOf(-1, 0, 1, 2, 4, 8, 12)
    val px = 192
    val label = 18
    val image = BufferedImage(px * offsets.size, (px + label) * MECHANISMS.size, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    MECHANISMS.forEachIndexed { row, mechanism ->
        val frames = entryWindow(trace, from, to, mechanism.entry, entryFrame)
        val top = row * (px + label)
        g.color = Color.WHITE
        g.drawString(
            "$from→$to · $variant · ${mechanism.label} · entry @$entryFrame · cols: last loop, +0, +1, +2, +4, +8, +12",
            4,
            top + 13,
        )
        offsets.forEachIndexed { column, offset ->
            g.drawImage(raster(frames[offset + 1]), column * px, top + label, null)
        }
    }
    g.dispose()
    return image
}
