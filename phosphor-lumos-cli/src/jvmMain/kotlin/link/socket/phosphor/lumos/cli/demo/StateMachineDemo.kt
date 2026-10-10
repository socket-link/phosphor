package link.socket.phosphor.lumos.cli.demo

import java.io.PrintStream
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import link.socket.phosphor.lumos.cli.projection.CliLattice
import link.socket.phosphor.lumos.cli.renderer.CliOrb
import link.socket.phosphor.lumos.cli.renderer.DefaultTerminalSize
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.trace.AtmosphereCue
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.Oscilloscope
import link.socket.phosphor.trace.TraceSegment
import link.socket.phosphor.trace.TraceStateMachine
import link.socket.phosphor.trace.TransitionPolicy
import link.socket.phosphor.trace.VoxelTrace

/**
 * Drives a recorded orb through state changes from the keyboard (PHO-39, Task D).
 *
 * What it demonstrates, in one terminal: a clip that knows nothing about when
 * you will press a key, switching between cognitive beats without a visible pop.
 * Press `i`, `t`, or `u` and watch the orb cross — and watch the status line
 * name *which* of the three resolution paths carried it there:
 *
 * - `idle↔thinking` and `idle↔uncertain` are recorded one-shots. They start the
 *   instant you press and play their authored motion, easing included.
 * - `thinking↔uncertain` is deliberately **not** recorded, so it falls through
 *   to the configured crossfade and the status line counts the weight up.
 *
 * The clip is captured fresh on every run, so the segment layout in the status
 * header is the layout the state machine is actually reading.
 *
 * Not a test. Nothing here is asserted; it is meant to be watched.
 *
 * Run: `./gradlew :phosphor-lumos-cli:runStateMachineDemo`
 *      `./gradlew :phosphor-lumos-cli:runStateMachineDemo -Pseconds=60`
 */
private const val DEFAULT_SECONDS = 45
private const val CAPTURE_FPS = 24
private const val CROSSFADE_MS = 600L

// Terminal geometry. Clamped so the orb still reads on a cramped window.
private const val MIN_WIDTH = 40
private const val MIN_HEIGHT = 20
private const val STATUS_LINES = 3
private const val PREAMBLE_PAUSE_MS = 2_000L

private const val IDLE = "idle"
private const val THINKING = "thinking"
private const val UNCERTAIN = "uncertain"

/**
 * One stretch of the captured clip.
 *
 * @property name Segment name written into the trace, arrow convention included.
 * @property frames How many captured frames the stretch covers.
 * @property loop Whether a player should loop the stretch or play it once.
 * @property cue Atmosphere to fire at the stretch's first frame, or null to carry on.
 */
private data class Beat(
    val name: String,
    val frames: Int,
    val loop: Boolean,
    val cue: AtmosphereState? = null,
)

/**
 * The capture script.
 *
 * Three resting loops and four of the six directed transitions between them.
 * Transition lengths are one frame longer than the authored durations in
 * `AtmosphereChoreographer`'s table — 0.8 s, 1.0 s, 1.4 s, 1.15 s at 24 fps —
 * so each one fully lands inside its own segment. The two `thinking↔uncertain`
 * pairs are left out on purpose: they are what the crossfade covers.
 */
private val SCRIPT =
    listOf(
        Beat(IDLE, frames = 48, loop = true),
        Beat("$IDLE→$THINKING", frames = 20, loop = false, cue = AtmospherePresets.THINKING),
        Beat(THINKING, frames = 48, loop = true),
        Beat("$THINKING→$IDLE", frames = 24, loop = false, cue = AtmospherePresets.IDLE),
        Beat("$IDLE→$UNCERTAIN", frames = 34, loop = false, cue = AtmospherePresets.UNCERTAIN),
        Beat(UNCERTAIN, frames = 48, loop = true),
        Beat("$UNCERTAIN→$IDLE", frames = 28, loop = false, cue = AtmospherePresets.IDLE),
    )

fun main(args: Array<String>) {
    val runSeconds = args.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_SECONDS

    val trace =
        capture().getOrElse { error ->
            System.err.println("Could not capture a trace to drive: ${error.message}")
            return
        }

    describe(trace)
    withRawKeys { play(trace, runSeconds) }
}

private fun capture(): Result<VoxelTrace> {
    val totalFrames = SCRIPT.sumOf(Beat::frames)
    val segments = ArrayList<TraceSegment>(SCRIPT.size)
    val cues = ArrayList<AtmosphereCue>()
    var cursor = 0

    SCRIPT.forEach { beat ->
        segments += TraceSegment(beat.name, cursor, cursor + beat.frames - 1, beat.loop)
        beat.cue?.let { cues += AtmosphereCue(atFrame = cursor, atmosphere = it) }
        cursor += beat.frames
    }

    return Oscilloscope.capture(
        CaptureConfig(
            params = CaptureParams(atmosphere = AtmospherePresets.IDLE),
            seed = 0x10_5E55L,
            durationMs = (totalFrames * 1_000L + CAPTURE_FPS - 1) / CAPTURE_FPS,
            fps = CAPTURE_FPS,
            segments = segments,
            cues = cues,
        ),
    )
}

private fun describe(trace: VoxelTrace) {
    val loops = trace.segments.filter(TraceSegment::loop).map(TraceSegment::name)
    val recorded = trace.segments.filterNot(TraceSegment::loop).map(TraceSegment::name)

    println("Lumos state-driven playback - PHO-39")
    println(
        "frames=${trace.frameCount} fps=${trace.fps} resolution=${trace.staticLattice.resolution} " +
            "cells/frame=${trace.cellCounts.maxOrNull() ?: 0}",
    )
    println("loops: ${loops.joinToString()}")
    println("recorded transitions: ${recorded.joinToString()}")
    println("missing (crossfade covers these): $THINKING→$UNCERTAIN, $UNCERTAIN→$THINKING")
    // A pairwise blend needs cell index i to mean the same voxel on both sides,
    // which holds whenever the capture ran with omitBelowScale = 0.
    val paired = trace.cellCounts.distinct().size == 1
    println("blend alignment: ${if (paired) "paired" else "unpaired - the crossfade will cut instead"}")
    println()
    println("keys: [i] $IDLE  [t] $THINKING  [u] $UNCERTAIN  [q] quit")
    println("watch for a pop at the switch - there should not be one")
    Thread.sleep(PREAMBLE_PAUSE_MS)
}

private fun play(
    trace: VoxelTrace,
    runSeconds: Int,
) {
    val terminal = DefaultTerminalSize().current()
    val width = terminal.columns.coerceAtLeast(MIN_WIDTH)
    val height = (terminal.rows - STATUS_LINES).coerceAtLeast(MIN_HEIGHT)

    val machine =
        TraceStateMachine(
            trace = trace,
            initialState = IDLE,
            policy = TransitionPolicy.Crossfade(durationMs = CROSSFADE_MS),
        )
    val orb =
        CliOrb(
            lattice = CliLattice(width = width, height = height),
            targetFps = trace.fps,
        )

    var running = true
    var requests = 0
    var fades = 0
    var wasFading = false
    var rendered = 0L

    orb.start()
    try {
        runBlocking {
            withTimeoutOrNull(runSeconds.seconds) {
                machine
                    .frames()
                    .takeWhile { running }
                    .collect { frame ->
                        drainKeys().forEach { key ->
                            when (key.lowercaseChar()) {
                                'q' -> running = false
                                else ->
                                    stateFor(key)?.let { requested ->
                                        requests++
                                        machine.requestState(requested).getOrThrow()
                                    }
                            }
                        }

                        orb.render(frame)
                        status(machine, width)

                        val fading = machine.blend != null
                        if (fading && !wasFading) fades++
                        wasFading = fading
                        rendered++
                    }
            }
        }
    } finally {
        orb.stop()
    }

    println("rendered $rendered frames from a ${trace.frameCount}-frame trace")
    println("$requests state requests, $fades of which fell through to a crossfade")
    println("state at exit: ${machine.state.value}")
}

/**
 * Write the live status where [CliOrb] parked the cursor — one row below the orb.
 *
 * Padded to [width] so a shorter line overwrites a longer one rather than
 * leaving its tail behind.
 */
private fun status(
    machine: TraceStateMachine,
    width: Int,
    out: PrintStream = System.out,
) {
    val arriving = if (machine.isTransitioning) " -> ${machine.requestedState.value}" else ""
    val line = "[i]$IDLE [t]$THINKING [u]$UNCERTAIN [q]uit | ${machine.state.value}$arriving | ${path(machine)}"
    // One short of the width so the cursor cannot wrap onto the row below.
    val room = (width - 1).coerceAtLeast(1)
    out.print(line.take(room).padEnd(room))
}

private fun path(machine: TraceStateMachine): String {
    val fade = machine.blend
    return when {
        !machine.isTransitioning -> "looping ${machine.activeSegment.name}"
        fade != null -> "crossfade ${(fade.weight * 100).roundToInt()}% -> ${machine.incomingSegment?.name}"
        machine.activeSegment.name.contains(TraceStateMachine.TRANSITION_ARROW) ->
            "recorded ${machine.activeSegment.name}"
        else -> "finish-loop, waiting for the seam"
    }
}

private fun stateFor(key: Char): String? =
    when (key.lowercaseChar()) {
        'i' -> IDLE
        't' -> THINKING
        'u' -> UNCERTAIN
        else -> null
    }

/**
 * Read whatever keys are already buffered, without blocking the render loop.
 *
 * Polling rather than a reader thread: the machine is single-threaded by
 * contract, so a request has to be made from the coroutine that drives it.
 */
private fun drainKeys(): List<Char> {
    val keys = ArrayList<Char>()
    while (System.`in`.available() > 0) {
        val byte = System.`in`.read()
        if (byte < 0) break
        keys += byte.toChar()
    }
    return keys
}

/**
 * Run [block] with the terminal in raw mode, so a keypress arrives without a
 * newline behind it and is not echoed over the orb.
 *
 * Falls back to the terminal's normal line discipline when `stty` is not
 * available — the demo still works, it just wants Enter after each key.
 */
private fun withRawKeys(block: () -> Unit) {
    val raw = stty("-echo", "-icanon")
    try {
        block()
    } finally {
        if (raw) stty("echo", "icanon")
    }
}

private fun stty(vararg args: String): Boolean =
    runCatching {
        ProcessBuilder(listOf("stty") + args)
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    }.getOrDefault(false)
