package link.socket.phosphor.lumos.cli.bench

import java.io.OutputStream
import java.io.PrintStream
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.lumos.cli.glyph.CliGlyph
import link.socket.phosphor.lumos.cli.projection.CliLattice
import link.socket.phosphor.lumos.cli.renderer.CliOrb
import link.socket.phosphor.lumos.cli.renderer.TerminalSize
import link.socket.phosphor.lumos.probe.FramePhase
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.lumos.probe.FrameProbeSummary
import link.socket.phosphor.lumos.probe.PhaseStats
import link.socket.phosphor.lumos.probe.RingBufferFrameProbe
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.CognitiveSceneRuntime
import link.socket.phosphor.runtime.SceneConfiguration

/**
 * JVM baseline harness for [FrameProbe] (PHO-34, Task C).
 *
 * Drives the full CLI Lumos frame loop — `CognitiveSceneRuntime.update` →
 * `VoxelFrameBuilder.build` (BUILD) → `CliLattice.project` (PROJECT) →
 * `CliOrb.render` (DRAW) — for a fixed wall-clock duration with the probe
 * enabled, then prints a p50/p95/max table per phase. Terminal output is sunk
 * into a discarding stream so DRAW measures ANSI assembly and write cost
 * without flooding a real terminal.
 *
 * This is the CLI projection/draw path: it is the only fully JVM-drawable
 * surface in the repo today. The Compose path (`ComposeLattice` + `LumosCanvas`,
 * ~1,500-voxel `drawRect`) needs a Compose host and is out of scope here; the
 * gap is noted in the run output.
 *
 * Run: `./gradlew :phosphor-lumos-cli:runFrameProbeBenchmark` (60s) or pass a
 * duration in seconds as the first argument.
 */
private const val DEFAULT_DURATION_SECONDS = 60
private const val WARMUP_SECONDS = 3
private const val FIXED_DT_SECONDS = 1f / 60f
private const val GRID_WIDTH = 64
private const val GRID_HEIGHT = 32
private const val OVERHEAD_FRAMES = 3_000
private const val OVERHEAD_ROUNDS = 3

fun main(args: Array<String>) {
    val durationSeconds = args.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_DURATION_SECONDS

    val enabledProbe = RingBufferFrameProbe()
    val enabled = newPipeline(enabledProbe)
    val voxelCount = enabled.frameVoxelCount()

    println("FrameProbe baseline - CLI path (BUILD + PROJECT + DRAW)")
    println("grid=${GRID_WIDTH}x$GRID_HEIGHT  voxels/frame=$voxelCount  tickDt=${FIXED_DT_SECONDS}s")
    println("warmup=${WARMUP_SECONDS}s  measured=${durationSeconds}s")
    println()

    // Warm the JIT, then discard warm-up samples before the measured window.
    runFor(enabled, WARMUP_SECONDS)
    enabledProbe.reset()
    val measuredFrames = runFor(enabled, durationSeconds)

    printSummary(enabledProbe.summary(), measuredFrames, durationSeconds)

    println()
    printOverheadComparison()
}

/** One pipeline instance: a runtime plus the three measured stages, all sharing [probe]. */
private class Pipeline(
    private val runtime: CognitiveSceneRuntime,
    private val builder: VoxelFrameBuilder,
    private val lattice: CliLattice,
    private val orb: CliOrb,
) {
    fun step() {
        val snapshot = runtime.update(FIXED_DT_SECONDS)
        val frame = builder.build(snapshot, FIXED_DT_SECONDS)
        val projected = lattice.project(frame)
        orb.render(CliGlyph.overlay(projected))
    }

    fun frameVoxelCount(): Int {
        val snapshot = runtime.update(FIXED_DT_SECONDS)
        return builder.build(snapshot, FIXED_DT_SECONDS).cells.size
    }
}

private fun newPipeline(probe: FrameProbe): Pipeline {
    val config =
        SceneConfiguration(
            width = GRID_WIDTH,
            height = GRID_HEIGHT,
            enableAtmosphere = true,
            initialAtmosphere = AtmospherePresets.THINKING,
        )
    val runtime = CognitiveSceneRuntime(config)
    val builder = VoxelFrameBuilder(initialResolution = config.initialAtmosphere.resolution, probe = probe)
    val lattice = CliLattice(width = GRID_WIDTH, height = GRID_HEIGHT, probe = probe)
    val orb =
        CliOrb(
            out = discardingStream(),
            terminalSize = TerminalSize.fixed(GRID_WIDTH, GRID_HEIGHT + 2),
            lattice = lattice,
            probe = probe,
        )
    return Pipeline(runtime, builder, lattice, orb)
}

/** Drive [pipeline] until [seconds] of wall-clock elapse; returns the frame count. */
private fun runFor(
    pipeline: Pipeline,
    seconds: Int,
): Long {
    val deadline = System.nanoTime() + seconds * 1_000_000_000L
    var frames = 0L
    while (System.nanoTime() < deadline) {
        pipeline.step()
        frames++
    }
    return frames
}

/**
 * Zero-overhead-when-off check: time a fixed frame count with the probe enabled
 * vs disabled and report the per-frame delta. Reports the min across rounds to
 * shed scheduling noise.
 */
private fun printOverheadComparison() {
    val disabled = newPipeline(FrameProbe.Disabled)
    val enabled = newPipeline(RingBufferFrameProbe())

    // Warm both before timing.
    repeat(OVERHEAD_FRAMES) { disabled.step() }
    repeat(OVERHEAD_FRAMES) { enabled.step() }

    val disabledNs = minRoundNanosPerFrame(disabled)
    val enabledNs = minRoundNanosPerFrame(enabled)
    val deltaNs = enabledNs - disabledNs
    val pct = if (disabledNs > 0) deltaNs * 100.0 / disabledNs else 0.0

    println("Zero-overhead-when-off ($OVERHEAD_FRAMES frames/round, min of $OVERHEAD_ROUNDS rounds):")
    println("  probe OFF : ${formatNanos(disabledNs)}/frame")
    println("  probe ON  : ${formatNanos(enabledNs)}/frame")
    println("  delta     : ${formatNanos(deltaNs)}/frame (${formatSigned(pct)}%)")
}

private fun minRoundNanosPerFrame(pipeline: Pipeline): Long {
    var best = Long.MAX_VALUE
    repeat(OVERHEAD_ROUNDS) {
        val start = System.nanoTime()
        repeat(OVERHEAD_FRAMES) { pipeline.step() }
        val perFrame = (System.nanoTime() - start) / OVERHEAD_FRAMES
        if (perFrame < best) best = perFrame
    }
    return best
}

private fun printSummary(
    summary: FrameProbeSummary,
    frames: Long,
    seconds: Int,
) {
    val fps = if (seconds > 0) frames.toDouble() / seconds else 0.0
    println("Frames measured: $frames  (${"%.0f".format(fps)} fps uncapped)")
    println()
    println("Phase    | samples | p50        | p95        | max")
    println("---------|---------|------------|------------|------------")
    for (phase in FramePhase.entries) {
        println(formatRow(summary.forPhase(phase)))
    }
}

private fun formatRow(stats: PhaseStats): String =
    buildString {
        append(stats.phase.name.padEnd(8))
        append(" | ")
        append(stats.sampleCount.toString().padStart(7))
        append(" | ")
        append(formatNanos(stats.p50Nanos).padStart(10))
        append(" | ")
        append(formatNanos(stats.p95Nanos).padStart(10))
        append(" | ")
        append(formatNanos(stats.maxNanos).padStart(10))
    }

private fun formatNanos(nanos: Long): String =
    when {
        nanos >= 1_000_000L -> "%.2f ms".format(nanos / 1_000_000.0)
        nanos >= 1_000L -> "%.1f us".format(nanos / 1_000.0)
        else -> "$nanos ns"
    }

private fun formatSigned(value: Double): String = (if (value >= 0) "+" else "") + "%.1f".format(value)

private fun discardingStream(): PrintStream =
    PrintStream(
        object : OutputStream() {
            override fun write(b: Int) = Unit

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) = Unit
        },
    )
