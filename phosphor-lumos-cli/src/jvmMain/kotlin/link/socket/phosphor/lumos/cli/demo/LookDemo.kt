package link.socket.phosphor.lumos.cli.demo

import link.socket.phosphor.lumos.CognitiveLookSwitch
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.cli.glyph.CliGlyph
import link.socket.phosphor.lumos.cli.projection.CliLattice
import link.socket.phosphor.lumos.cli.renderer.CliOrb
import link.socket.phosphor.lumos.cli.renderer.DefaultTerminalSize
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase
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
 * key the switch chose, then the keys — fitted to [width] so it never wraps.
 */
internal fun statusLine(
    phase: CognitivePhase,
    activity: AgentActivityState,
    escalated: Boolean,
    lookKey: String?,
    width: Int,
): String {
    val line =
        "${phase.name} · ${activity.name} · ${if (escalated) "ESCALATED" else "calm"} | " +
            "look ${lookKey ?: "-"} | [p][a][e] [b][w][s][r] [i][t][u] [q]"
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
        renderConfig = LumosRenderConfig(rimFade = true),
        fps = FPS,
    ).apply { setActivity(AgentActivityState.IDLE) }

fun main(args: Array<String>) {
    val runSeconds = args.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_SECONDS
    println(
        "Lumos looks, live: [p]hase [a]ctivity [e]scalate | [b]ase [w]orking [s]earching [r]easoning " +
            "| [i]dle [t]hinking [u]ncertain | [q]uit",
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

    val frameNanos = 1_000_000_000L / FPS
    val deadline = System.nanoTime() + runSeconds * 1_000_000_000L
    var last = System.nanoTime()
    var running = true
    var rendered = 0L

    orb.start()
    try {
        while (running && System.nanoTime() < deadline) {
            val frameStart = System.nanoTime()
            drainKeys().forEach { key ->
                when (key.lowercaseChar()) {
                    'q' -> running = false
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
                    else -> atmosphereFor(key)?.let { generator.setAtmosphere(it) }
                }
            }

            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000.0).coerceAtMost(MAX_STEP_SECONDS).toFloat()
            last = now

            val frame = source.next(dt)
            orb.render(CliGlyph.overlay(lattice.project(frame)))
            print(statusLine(phase, activity, escalated, source.lookKey, width))
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
