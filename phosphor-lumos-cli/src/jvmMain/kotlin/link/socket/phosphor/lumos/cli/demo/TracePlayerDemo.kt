package link.socket.phosphor.lumos.cli.demo

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.lumos.GlyphLifecycle
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.VoxelGlyphState
import link.socket.phosphor.lumos.cli.projection.CliLattice
import link.socket.phosphor.lumos.cli.renderer.CliOrb
import link.socket.phosphor.lumos.cli.renderer.DefaultTerminalSize
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.trace.AtmosphereCue
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.LumosSource
import link.socket.phosphor.trace.Oscilloscope
import link.socket.phosphor.trace.TraceCodec
import link.socket.phosphor.trace.TraceSegment
import link.socket.phosphor.trace.VoxelTrace
import link.socket.phosphor.trace.frames

/**
 * Plays a recorded `.vxt` through the shipped CLI render path (PHO-37, Task E).
 *
 * What it demonstrates, in one terminal: a baked trace entering the pipeline at
 * the same point a live simulation would, and a glyph firing **live on top of
 * it**. The orb's voxels come off disk; the checkmark burning over them was
 * never recorded. That is the whole architectural claim of this ticket, and it
 * holds because the glyph overlay happens downstream of the recording point —
 * `CliOrb.render(VoxelFrame)` projects through `CliLattice` and then calls
 * `CliGlyph.overlay`, neither of which knows or cares where the frame came from.
 *
 * Not a test. Nothing here is asserted; it is meant to be watched.
 *
 * With no arguments it captures a fresh trace, writes it to [DEFAULT_TRACE_PATH],
 * decodes it back, and plays the decoded bytes — so a run exercises the codec
 * end to end rather than replaying an in-memory object.
 *
 * Run: `./gradlew :phosphor-lumos-cli:runTracePlayerDemo`
 *      `./gradlew :phosphor-lumos-cli:runTracePlayerDemo -Pseconds=30`
 *      `./gradlew :phosphor-lumos-cli:runTracePlayerDemo -Ptrace=path/to/clip.vxt`
 */
private const val DEFAULT_SECONDS = 12
private const val CAPTURE_FPS = 24
private const val CAPTURE_MS = 4_000L
private const val DEFAULT_TRACE_PATH = "build/demo/lumos-demo.vxt"

// Terminal geometry. Clamped so the orb still reads on a cramped window.
private const val MIN_WIDTH = 40
private const val MIN_HEIGHT = 20
private const val STATUS_LINES = 3

// Beats of the demo script, as a fraction of the run.
private const val GLYPH_PERIOD_SECONDS = 4.0
private const val GLYPH_WINDOW_SECONDS = 1.6f
private const val SEGMENT_SWITCH_FRACTION = 0.5
private const val PREAMBLE_PAUSE_MS = 1_500L

fun main(args: Array<String>) {
    val runSeconds = args.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_SECONDS
    val tracePath = args.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(Paths::get)

    val trace =
        when (tracePath) {
            null -> captureAndRoundTrip(Paths.get(DEFAULT_TRACE_PATH))
            else -> load(tracePath)
        }.getOrElse { error ->
            System.err.println("Could not open a trace to play: ${error.message}")
            return
        }

    describe(trace, runSeconds)
    play(trace, runSeconds)
}

/**
 * Capture a two-beat clip, encode it to [destination], then decode it back.
 *
 * The decoded trace is what gets played, so every run passes through CBOR and
 * gzip rather than handing the player the object it just built.
 */
private fun captureAndRoundTrip(destination: Path): Result<VoxelTrace> {
    val config =
        CaptureConfig(
            params = CaptureParams(atmosphere = AtmospherePresets.IDLE),
            seed = 0x10_5E55L,
            durationMs = CAPTURE_MS,
            fps = CAPTURE_FPS,
            segments = demoSegments(),
            cues = demoCues(),
        )

    val captured = Oscilloscope.capture(config).getOrElse { return Result.failure(it) }
    val encoded = TraceCodec.encode(captured)

    return runCatching {
        destination.parent?.let(Files::createDirectories)
        Files.write(destination, encoded)
        println("captured ${encoded.size / 1024} KiB to $destination")
        TraceCodec.decode(encoded).getOrThrow()
    }
}

private fun load(path: Path): Result<VoxelTrace> =
    runCatching { Files.readAllBytes(path) }
        .mapCatching { bytes -> TraceCodec.decode(bytes).getOrThrow() }

/**
 * Two looping beats: the clip rests in `idle`, then settles into `thinking`.
 * Halfway through the run the demo calls `setSegment` to cross between them.
 */
private fun demoSegments(): List<TraceSegment> {
    val frameCount = capturedFrameCount()
    val midpoint = frameCount / 2
    return listOf(
        TraceSegment(name = "idle", startFrame = 0, endFrame = midpoint - 1, loop = true),
        TraceSegment(name = "thinking", startFrame = midpoint, endFrame = frameCount - 1, loop = true),
    )
}

/** One cue at the seam, so the two beats actually look different. */
private fun demoCues(): List<AtmosphereCue> =
    listOf(
        AtmosphereCue(
            atFrame = capturedFrameCount() / 2,
            atmosphere = AtmospherePresets.THINKING,
        ),
    )

private fun capturedFrameCount(): Int = (CAPTURE_MS * CAPTURE_FPS / 1_000L).toInt()

private fun describe(
    trace: VoxelTrace,
    runSeconds: Int,
) {
    val cellsPerFrame = trace.cellCounts.maxOrNull() ?: 0
    println("Lumos trace playback - PHO-37")
    println(
        "frames=${trace.frameCount} fps=${trace.fps} resolution=${trace.staticLattice.resolution} " +
            "cells/frame=$cellsPerFrame segments=${trace.segments.joinToString { it.name }}",
    )
    println("playing for ${runSeconds}s - the orb is recorded, the glyph is live")
    println("press ctrl-c to stop")
    Thread.sleep(PREAMBLE_PAUSE_MS)
}

private fun play(
    trace: VoxelTrace,
    runSeconds: Int,
) {
    val terminal = DefaultTerminalSize().current()
    val width = terminal.columns.coerceAtLeast(MIN_WIDTH)
    val height = (terminal.rows - STATUS_LINES).coerceAtLeast(MIN_HEIGHT)

    val source = LumosSource.Trace(trace, initialSegment = trace.segments.firstOrNull()?.name)
    val player = source.player
    val lattice = CliLattice(width = width, height = height)
    val orb = CliOrb(lattice = lattice, targetFps = trace.fps)

    // The live half: a lifecycle this demo owns, advanced per frame and fired on
    // a timer. Nothing in the trace knows about it.
    val glyphs = listOf(LumosGlyph.CHECK, LumosGlyph.STAR, LumosGlyph.LIGHTNING)
    var burning: GlyphLifecycle? = null
    var fired = 0
    var switched = false
    var rendered = 0L

    val frameSeconds = 1f / trace.fps
    val glyphPeriodFrames = (GLYPH_PERIOD_SECONDS * trace.fps).toLong().coerceAtLeast(1L)
    val switchAtFrame = (runSeconds * trace.fps * SEGMENT_SWITCH_FRACTION).toLong()
    val secondSegment = trace.segments.getOrNull(1)?.name

    orb.start()
    try {
        runBlocking {
            withTimeoutOrNull(runSeconds.seconds) {
                source.frames().collect { frame ->
                    if (rendered % glyphPeriodFrames == 0L) {
                        burning =
                            GlyphLifecycle(
                                glyph = glyphs[fired % glyphs.size],
                                totalDurationSeconds = GLYPH_WINDOW_SECONDS,
                                ageSeconds = 0f,
                            )
                        fired++
                    }
                    if (!switched && secondSegment != null && rendered >= switchAtFrame) {
                        player.setSegment(secondSegment)
                        switched = true
                    }

                    orb.render(frame.withLiveGlyph(burning))

                    burning = burning?.advance(frameSeconds)?.takeUnless { it.isComplete }
                    rendered++
                }
            }
        }
    } finally {
        orb.stop()
    }

    println("rendered $rendered frames from a ${trace.frameCount}-frame trace")
    println("fired $fired live glyphs over baked voxels")
    println("segment at exit: ${player.activeSegment.name}")
}

/**
 * Graft a live glyph onto a recorded frame.
 *
 * The recorded frame's own `glyph` is whatever was burning at capture time —
 * nothing, for this clip. Replacing it is a single-object copy: the cell list is
 * shared, not duplicated, so overlaying a glyph on a 4,801-voxel frame costs one
 * allocation rather than 4,801.
 */
private fun VoxelFrame.withLiveGlyph(lifecycle: GlyphLifecycle?): VoxelFrame {
    val active = lifecycle ?: return this
    val color =
        NeutralColor.fromHsl(
            active.glyph.hue,
            active.glyph.saturation,
            active.glyph.lightness,
        )
    return copy(
        glyph =
            VoxelGlyphState(
                glyphName = active.glyph.name,
                progress = active.progress,
                red = color.red,
                green = color.green,
                blue = color.blue,
            ),
    )
}
