package link.socket.phosphor.lumos.cli.demo

import java.io.PrintStream
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import link.socket.phosphor.lumos.LumosSequence
import link.socket.phosphor.lumos.cli.projection.CliLattice
import link.socket.phosphor.lumos.cli.renderer.CliOrb
import link.socket.phosphor.lumos.cli.renderer.DefaultTerminalSize
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.Oscilloscope
import link.socket.phosphor.trace.TraceSegment
import link.socket.phosphor.trace.TraceStateMachine
import link.socket.phosphor.trace.TransitionPolicy
import link.socket.phosphor.trace.VoxelTrace
import link.socket.phosphor.trace.seamErrors

/**
 * Drives a recorded orb through state changes from the keyboard (PHO-39, Task D).
 *
 * What it demonstrates, in one terminal: a clip that knows nothing about when
 * you will press a key, switching between cognitive beats without a visible pop.
 * Press `i`, `t`, or `u` and watch the orb cross — and watch the status line
 * name *which* of the three resolution paths carried it there:
 *
 * - `idle↔thinking` and `idle↔uncertain` are recorded one-shots. They start the
 *   instant you press and play their authored motion, easing included, fading
 *   in from wherever the loop was over their first half second (PHO-46).
 * - `thinking↔uncertain` is deliberately **not** recorded, so it falls through
 *   to the configured crossfade and the status line counts the weight up.
 * - `f` and `c` reshape the idle orb into the Fibonacci sphere and the cube and
 *   `i` brings it back: recorded morphs (PHO-57) in which every cell springs
 *   from its lattice position to its matched one, so the trace keeps pairing
 *   cells by index and the one-shots blend in like any other. Only idle is
 *   recorded to and from a form; a form key pressed from thinking or uncertain
 *   falls through to the crossfade, which drags cells and is the pop the morph
 *   exists to avoid.
 *
 * The clip is captured fresh on every run from [DEMO_SEQUENCE_RESOURCE], a
 * `LumosSequence` written as JSON (PHO-65): thirteen steps, each the segment the
 * state machine reads, so the layout in the status header is the one the data
 * says. It is the first clip authored in the format. The capture bakes a seam
 * blend (PHO-43), so a loop left alone — idle, most of the orb's life — iterates
 * without popping too; the header prints each loop's measured seam. Each loop
 * follows its transition directly: the blend fades toward a lead-in synthesized
 * from the loop's own steady state (PHO-49), so no one-shot carries settle
 * padding and `state` flips as soon as the authored motion lands.
 *
 * Not a test. Nothing here is asserted; it is meant to be watched.
 *
 * Run: `./gradlew :phosphor-lumos-cli:runStateMachineDemo`
 *      `./gradlew :phosphor-lumos-cli:runStateMachineDemo -Pseconds=60`
 */
private const val DEFAULT_SECONDS = 45
private const val CAPTURE_FPS = 24
private const val CROSSFADE_MS = 600L

/**
 * Half a second of each loop's tail fades toward the loop's own steady state,
 * synthesized at its first frame (PHO-49).
 */
internal const val SEAM_BLEND_FRAMES = 12

// Terminal geometry. Clamped so the orb still reads on a cramped window.
private const val MIN_WIDTH = 40
private const val MIN_HEIGHT = 20
private const val STATUS_LINES = 3
private const val PREAMBLE_PAUSE_MS = 2_000L

private const val IDLE = "idle"
private const val THINKING = "thinking"
private const val UNCERTAIN = "uncertain"
private const val FIBONACCI = "fibonacci"
private const val CUBE = "cube"

/**
 * The capture script, as data: the bundled `LumosSequence`.
 *
 * Three resting loops and four of the six directed transitions between them,
 * then the lattice reshaping into each catalogue form and back (PHO-57).
 * Transition steps last the authored durations in `AtmosphereChoreographer`'s
 * table — 0.8 s, 1.0 s, 1.4 s, 1.15 s — and the morph's 2.4 s, each rounded up to
 * whole frames at 24 fps and written with enough digits to land on them, so each
 * one has landed by the time the loop that follows starts on the very next
 * frame. Nothing is padded: a loop's tail fades toward a lead-in synthesized
 * from its own steady state, not toward these frames (PHO-49). The two
 * `thinking↔uncertain` pairs are left out on purpose: they are what the
 * crossfade covers. Every loop step names the state the one-shot before it
 * landed, which applies nothing — the format's rule for resting beats.
 */
internal const val DEMO_SEQUENCE_RESOURCE = "/lumos/demo-sequence.json"

fun main(args: Array<String>) {
    val runSeconds = args.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_SECONDS

    val trace =
        captureDemoTrace().getOrElse { error ->
            System.err.println("Could not capture a trace to drive: ${error.message}")
            return
        }

    describe(trace)
    withRawKeys { play(trace, runSeconds) }
}

/** Read the bundled sequence; a malformed resource is a build error, so this throws. */
internal fun demoSequence(): LumosSequence {
    val text =
        checkNotNull(object {}.javaClass.getResourceAsStream(DEMO_SEQUENCE_RESOURCE)) {
            "$DEMO_SEQUENCE_RESOURCE is not on the classpath"
        }.use { stream -> stream.readBytes().decodeToString() }
    return LumosSequence.fromJson(text).getOrThrow()
}

/**
 * Capture the bundled sequence into a trace. [seamBlendFrames] is a parameter so
 * a test can capture the same script unblended and measure what the blend changed.
 */
internal fun captureDemoTrace(seamBlendFrames: Int = SEAM_BLEND_FRAMES): Result<VoxelTrace> =
    Oscilloscope.capture(
        CaptureConfig.forSequence(
            sequence = demoSequence(),
            fps = CAPTURE_FPS,
            params = CaptureParams(atmosphere = AtmospherePresets.IDLE),
            seed = 0x10_5E55L,
            seamBlendFrames = seamBlendFrames,
        ),
    )

private fun describe(trace: VoxelTrace) {
    val loops = trace.segments.filter(TraceSegment::loop).map(TraceSegment::name)
    val recorded = trace.segments.filterNot(TraceSegment::loop).map(TraceSegment::name)

    println("Lumos state-driven playback - PHO-39, scripted by $DEMO_SEQUENCE_RESOURCE (PHO-65)")
    println(
        "frames=${trace.frameCount} fps=${trace.fps} resolution=${trace.staticLattice.resolution} " +
            "cells/frame=${trace.cellCounts.maxOrNull() ?: 0}",
    )
    println("loops: ${loops.joinToString()}")
    println("recorded transitions: ${recorded.joinToString()}")
    println("missing (crossfade covers these): $THINKING→$UNCERTAIN, $UNCERTAIN→$THINKING")
    // ~1 wraps like one more frame. Rotation is left out: the state machine
    // carries it across the seam rather than replaying it.
    println(
        "loop seams (frame steps, baked over $SEAM_BLEND_FRAMES frames): " +
            trace.seamErrors().joinToString { "${it.segment.name} ${"%.2f".format(it.contentRatio)}" },
    )
    // A pairwise blend needs cell index i to mean the same voxel on both sides,
    // which holds whenever the capture ran with omitBelowScale = 0.
    val paired = trace.cellCounts.distinct().size == 1
    println("blend alignment: ${if (paired) "paired" else "unpaired - the crossfade will cut instead"}")
    println()
    println("keys: [i] $IDLE  [t] $THINKING  [u] $UNCERTAIN  [f] $FIBONACCI  [c] $CUBE  [q] quit")
    println("forms morph from idle and back; from thinking or uncertain they crossfade instead")
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

                        val fading = machine.blend != null && !machine.isBlendingIntoOneShot()
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
    val line =
        "[i]$IDLE [t]$THINKING [u]$UNCERTAIN [f]$FIBONACCI [c]$CUBE [q]uit | " +
            "${machine.state.value}$arriving | ${path(machine)}"
    // One short of the width so the cursor cannot wrap onto the row below.
    val room = (width - 1).coerceAtLeast(1)
    out.print(line.take(room).padEnd(room))
}

private fun path(machine: TraceStateMachine): String {
    val fade = machine.blend
    return when {
        !machine.isTransitioning -> "looping ${machine.activeSegment.name}"
        fade != null && machine.isBlendingIntoOneShot() ->
            "recorded ${machine.incomingSegment?.name}, blending in ${(fade.weight * 100).roundToInt()}%"
        fade != null -> "crossfade ${(fade.weight * 100).roundToInt()}% -> ${machine.incomingSegment?.name}"
        machine.activeSegment.name.contains(TraceStateMachine.TRANSITION_ARROW) ->
            "recorded ${machine.activeSegment.name}"
        else -> "finish-loop, waiting for the seam"
    }
}

/** Whether the incoming head is a recorded one-shot being faded into, rather than a loop. */
private fun TraceStateMachine.isBlendingIntoOneShot(): Boolean =
    incomingSegment?.name?.contains(TraceStateMachine.TRANSITION_ARROW) == true

private fun stateFor(key: Char): String? =
    when (key.lowercaseChar()) {
        'i' -> IDLE
        't' -> THINKING
        'u' -> UNCERTAIN
        'f' -> FIBONACCI
        'c' -> CUBE
        else -> null
    }

/**
 * Read whatever keys are already buffered, without blocking the render loop.
 *
 * Polling rather than a reader thread: the machine is single-threaded by
 * contract, so a request has to be made from the coroutine that drives it.
 */
internal fun drainKeys(): List<Char> {
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
internal fun withRawKeys(block: () -> Unit) {
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
