package link.socket.phosphor.lumos.cli.demo

import java.util.Locale
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.color.OklabColor
import link.socket.phosphor.lumos.CognitiveLookSwitch
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame.TerminalCell
import link.socket.phosphor.lumos.cli.glyph.CliGlyph
import link.socket.phosphor.lumos.cli.projection.CliLattice
import link.socket.phosphor.lumos.cli.renderer.CliOrb
import link.socket.phosphor.lumos.cli.renderer.DefaultTerminalSize
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase
import link.socket.phosphor.signal.LumosInput
import link.socket.phosphor.trace.SignalGenerator

/**
 * Drive a live orb by phase, activity and escalation from the keyboard, and
 * watch the look switch pick its look (PHO-80, after PHO-58's look keys).
 *
 * A live THINKING scene with the rim fade on, in a [SignalGenerator] stepped
 * through [SignalGenerator.asFrameSource] by the measured frame time — a
 * variable step, which is right for a display. Press `p` to advance the phase
 * round [PHASE_CYCLE], `a` to cycle the activity round [ACTIVITY_CYCLE], `e` to
 * toggle escalation; the generator's [CognitiveLookSwitch] picks the look from
 * the confirmed table and blends to it. `b`, `w`, `s` or `r` wear base,
 * working, searching or reasoning directly, until the next signal change takes
 * over; `i`, `t` or `u` change the atmosphere underneath; `q` quits.
 *
 * The orb is [interactive][LumosRenderConfig.interactive] (PHO-82). `m` shows
 * and hides a virtual mouse pointer, marked by a `+` on its cell, and the
 * arrow keys move it [POINTER_STEP] across the orb's square per press: the
 * cells part around it, follow it, and close behind it. The status line
 * prints where it is. A terminal has no hover, so this stands in for the
 * mouse a desktop surface reads. A recording cannot be pushed, which is one
 * more reason the keys live here.
 *
 * `StateMachineDemo` plays a recorded trace, which carries whatever look it was
 * captured in, so a live phase key has nothing to drive there; the keys live
 * here instead.
 *
 * Not a test. Nothing here is asserted; it is meant to be watched.
 *
 * Run: `./gradlew :phosphor-lumos-cli:runLookDemo`
 *      `./gradlew :phosphor-lumos-cli:runLookDemo -Pseconds=120`
 */
private const val DEFAULT_SECONDS = 60
private const val FPS = 24
private const val MAX_STEP_SECONDS = 0.1
private const val MIN_WIDTH = 24
private const val MIN_HEIGHT = 12
private const val STATUS_LINES = 2

/** How far one arrow press moves the pointer, in the orb's `[-1, 1]` square. */
private const val POINTER_STEP = 0.08f

// The most steps the pointer takes from the centre, so it stays on the orb's square.
private val POINTER_MAX_STEPS: Int = (1f / POINTER_STEP).toInt()

private const val ESC = '\u001b'

private val POINTER_COLOR: OklabColor = OklabColor.fromSrgb(NeutralColor.WHITE)

/** The order `p` steps the phase through, wrapping from LOOP back to PERCEIVE. */
internal val PHASE_CYCLE: List<CognitivePhase> =
    listOf(
        CognitivePhase.PERCEIVE,
        CognitivePhase.RECALL,
        CognitivePhase.OBSERVE,
        CognitivePhase.PLAN,
        CognitivePhase.EXECUTE,
        CognitivePhase.LEARN,
        CognitivePhase.LOOP,
    )

/** The order `a` steps the activity through, wrapping from SPAWNING back to IDLE. */
internal val ACTIVITY_CYCLE: List<AgentActivityState> =
    listOf(
        AgentActivityState.IDLE,
        AgentActivityState.PROCESSING,
        AgentActivityState.COMPLETE,
        AgentActivityState.SPAWNING,
    )

/** The phase after [phase] in [PHASE_CYCLE]; a phase outside it, such as NONE, starts it. */
internal fun nextPhase(phase: CognitivePhase): CognitivePhase =
    PHASE_CYCLE[(PHASE_CYCLE.indexOf(phase) + 1) % PHASE_CYCLE.size]

/** The activity after [activity] in [ACTIVITY_CYCLE]; an activity outside it starts it. */
internal fun nextActivity(activity: AgentActivityState): AgentActivityState =
    ACTIVITY_CYCLE[(ACTIVITY_CYCLE.indexOf(activity) + 1) % ACTIVITY_CYCLE.size]

/** A look as the header names it: `searching`, or `searching · lighthouse` for a variant. */
internal fun lookLabel(look: Look): String {
    val name = look.look.name.lowercase()
    return if (look.variant == Look.DEFAULT_VARIANT) name else "$name · ${look.variant}"
}

/**
 * One line per phase of [PHASE_CYCLE], naming the look [switch] picks for it
 * while the agent is idle, which is where the demo starts.
 */
internal fun phaseTable(switch: CognitiveLookSwitch = CognitiveLookSwitch()): List<String> {
    val column = PHASE_CYCLE.maxOf { it.name.length }
    return PHASE_CYCLE.map { phase ->
        "  ${phase.name.padEnd(column)}  ${lookLabel(switch.lookFor(phase, AgentActivityState.IDLE).look)}"
    }
}

/**
 * The status line: the signal the generator was last given, then the look
 * key the switch chose, then where the virtual [pointer] is when it is shown,
 * then the keys — fitted to [width] so it never wraps.
 */
internal fun statusLine(
    phase: CognitivePhase,
    activity: AgentActivityState,
    escalated: Boolean,
    lookKey: String?,
    width: Int,
    pointer: Vector2? = null,
): String {
    val at = pointer?.let { "pointer " + "(%+.2f, %+.2f)".format(Locale.ROOT, it.x, it.y) + " | " } ?: ""
    val line =
        "${phase.name} · ${activity.name} · ${if (escalated) "ESCALATED" else "calm"} | " +
            "look ${lookKey ?: "-"} | $at[p][a][e] [b][w][s][r] [i][t][u] [m]+arrows [q]"
    val room = (width - 1).coerceAtLeast(1)
    return line.take(room).padEnd(room)
}

/**
 * The demo's generator: a live THINKING scene with nothing in it but an
 * atmosphere, rim fade on, and the confirmed look switch installed by an
 * idle agent, so the first key pressed blends instead of cutting.
 */
internal fun lookDemoGenerator(): SignalGenerator =
    SignalGenerator(
        configuration =
            SceneConfiguration(
                width = 1,
                height = 1,
                enableWaveform = false,
                enableParticles = false,
                enableFlow = false,
                enableEmitters = false,
                enableCamera = false,
                enableAtmosphere = true,
                initialAtmosphere = AtmospherePresets.THINKING,
            ),
        renderConfig = LumosRenderConfig(rimFade = true, interactive = true),
        fps = FPS,
    ).apply { setActivity(AgentActivityState.IDLE) }

fun main(args: Array<String>) {
    val runSeconds = args.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_SECONDS
    println(
        "Lumos looks, live: [p]hase [a]ctivity [e]scalate | [b]ase [w]orking [s]earching [r]easoning " +
            "| [i]dle [t]hinking [u]ncertain | [m]ouse pointer and arrows to push the cells | [q]uit",
    )
    println("The phase picks the look (an activity of COMPLETE or SPAWNING wins, escalation wins over both):")
    phaseTable().forEach(::println)
    withRawKeys { playLooks(runSeconds) }
}

private fun playLooks(runSeconds: Int) {
    val terminal = DefaultTerminalSize().current()
    val width = terminal.columns.coerceAtLeast(MIN_WIDTH)
    val height = (terminal.rows - STATUS_LINES).coerceAtLeast(MIN_HEIGHT)

    val lattice = CliLattice(width = width, height = height)
    val orb = CliOrb(lattice = lattice, targetFps = FPS)
    val generator = lookDemoGenerator()
    val source = generator.asFrameSource()
    var phase = CognitivePhase.NONE
    var activity = AgentActivityState.IDLE
    var escalated = false
    val keys = KeyReader()
    val pointer = VirtualPointer()

    val frameNanos = 1_000_000_000L / FPS
    val deadline = System.nanoTime() + runSeconds * 1_000_000_000L
    var last = System.nanoTime()
    var running = true
    var rendered = 0L

    orb.start()
    try {
        while (running && System.nanoTime() < deadline) {
            val frameStart = System.nanoTime()
            keys.read(drainKeys()).forEach { press ->
                when (press) {
                    is Press.Arrow -> pointer.move(press.dx, press.dy)
                    is Press.Key ->
                        when (press.char.lowercaseChar()) {
                            'q' -> running = false
                            'm' -> pointer.toggle()
                            'p' -> {
                                phase = nextPhase(phase)
                                generator.setPhase(phase)
                            }
                            'a' -> {
                                activity = nextActivity(activity)
                                generator.setActivity(activity)
                            }
                            'e' -> {
                                escalated = !escalated
                                generator.setEscalated(escalated)
                            }
                            'b' -> generator.setLook(Look(LumosLook.BASE))
                            'w' -> generator.setLook(Look(LumosLook.WORKING))
                            's' -> generator.setLook(Look(LumosLook.SEARCHING))
                            'r' -> generator.setLook(Look(LumosLook.REASONING))
                            else -> atmosphereFor(press.char)?.let { generator.setAtmosphere(it) }
                        }
                }
            }
            source.setInput(pointer.input)

            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000.0).coerceAtMost(MAX_STEP_SECONDS).toFloat()
            last = now

            val frame = source.next(dt)
            val projected = CliGlyph.overlay(lattice.project(frame))
            val at = pointer.input.pointer
            orb.render(if (at == null) projected else marked(projected, at, lattice))
            print(statusLine(phase, activity, escalated, source.lookKey, width, at))
            rendered++

            val spare = frameNanos - (System.nanoTime() - frameStart)
            if (spare > 0) Thread.sleep(spare / 1_000_000L, (spare % 1_000_000L).toInt())
        }
    } finally {
        orb.stop()
    }

    println("rendered $rendered live frames; left at ${phase.name} · ${activity.name} wearing ${source.lookKey ?: "-"}")
}

private fun atmosphereFor(key: Char): AtmosphereState? =
    when (key.lowercaseChar()) {
        'i' -> AtmospherePresets.IDLE
        't' -> AtmospherePresets.THINKING
        'u' -> AtmospherePresets.UNCERTAIN
        else -> null
    }

/**
 * [frame] with a `+` on the cell [pointer] falls in, placed as [CliLattice]
 * places a cell's centre. The pointer is already in the orb's view space, so,
 * unlike a cell, it is not rotated.
 */
internal fun marked(
    frame: LumosTerminalFrame,
    pointer: Vector2,
    lattice: CliLattice,
): LumosTerminalFrame {
    val screenX = ((pointer.x + 1f) * 0.5f * lattice.width).toInt()
    val screenY = ((1f - (pointer.y + 1f) * 0.5f) * lattice.height * (1f / lattice.characterAspectRatio)).toInt()
    if (screenX < 0 || screenX >= frame.width || screenY < 0 || screenY >= frame.height) return frame
    val cells = frame.cells.toMutableList()
    cells[screenY * frame.width + screenX] = TerminalCell(char = '+', foreground = POINTER_COLOR, bold = true)
    return frame.copy(cells = cells)
}

/**
 * The virtual pointer: a whole number of [POINTER_STEP]s from the centre of the
 * orb's square on each axis, `+y` up as [LumosInput.pointer] reads it, and
 * whether it is shown. Hidden, it is [LumosInput.NONE] and the cells go home.
 */
internal class VirtualPointer {
    private var stepsX = 0
    private var stepsY = 0
    private var shown = false

    /** What the source is handed each frame: the pointer when shown, else nothing. */
    var input: LumosInput = LumosInput.NONE
        private set

    fun toggle() {
        shown = !shown
        update()
    }

    fun move(
        dx: Int,
        dy: Int,
    ) {
        stepsX = (stepsX + dx).coerceIn(-POINTER_MAX_STEPS, POINTER_MAX_STEPS)
        stepsY = (stepsY + dy).coerceIn(-POINTER_MAX_STEPS, POINTER_MAX_STEPS)
        update()
    }

    private fun update() {
        input =
            if (shown) {
                LumosInput(pointer = Vector2(stepsX * POINTER_STEP, stepsY * POINTER_STEP))
            } else {
                LumosInput.NONE
            }
    }
}

/** A keypress: a plain key, or an arrow moving the pointer one step along [Arrow.dx] and [Arrow.dy]. */
internal sealed interface Press {
    data class Key(val char: Char) : Press

    data class Arrow(val dx: Int, val dy: Int) : Press
}

/**
 * Splits raw-mode keyboard bytes into [Press]es. `ESC [ A` to `ESC [ D` — or
 * `ESC O A` to `ESC O D`, from a terminal in application cursor mode — are the
 * up, down, right and left arrows; every other byte is a plain key. A sequence
 * split across two reads is finished on the next.
 */
internal class KeyReader {
    // How far into an escape sequence the last byte left us: 0 outside one, 1 after ESC, 2 after ESC [.
    private var escape = 0

    fun read(chars: List<Char>): List<Press> {
        val presses = ArrayList<Press>()
        for (char in chars) {
            when (escape) {
                0 -> if (char == ESC) escape = 1 else presses += Press.Key(char)
                1 ->
                    if (char == '[' || char == 'O') {
                        escape = 2
                    } else {
                        // A lone ESC: drop it and read this byte as it is.
                        escape = 0
                        presses += Press.Key(char)
                    }
                else -> {
                    escape = 0
                    arrowFor(char)?.let { presses += it }
                }
            }
        }
        return presses
    }

    private fun arrowFor(char: Char): Press.Arrow? =
        when (char) {
            'A' -> Press.Arrow(0, 1)
            'B' -> Press.Arrow(0, -1)
            'C' -> Press.Arrow(1, 0)
            'D' -> Press.Arrow(-1, 0)
            else -> null
        }
}
