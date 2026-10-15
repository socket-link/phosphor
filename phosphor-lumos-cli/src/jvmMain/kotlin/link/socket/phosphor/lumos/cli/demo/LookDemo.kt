package link.socket.phosphor.lumos.cli.demo

import java.io.PrintStream
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.cli.glyph.CliGlyph
import link.socket.phosphor.lumos.cli.projection.CliLattice
import link.socket.phosphor.lumos.cli.renderer.CliOrb
import link.socket.phosphor.lumos.cli.renderer.DefaultTerminalSize
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState

/**
 * Watch the first four looks live and switch between them from the keyboard
 * (PHO-58, Task E).
 *
 * A live THINKING scene with the rim fade on, stepped at [FPS]. Press `b`, `w`,
 * `s` or `r` to wear base, working, searching or reasoning; `i`, `t` or `u` to
 * change the atmosphere underneath; `q` to quit. The look is the builder's
 * [link.socket.phosphor.lumos.VoxelFrameBuilder.look]; the atmosphere is the
 * skin and the look is the motion, so any pairing works.
 *
 * `StateMachineDemo` plays a recorded trace, which carries whatever look it was
 * captured in, so the keys live here instead.
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

fun main(args: Array<String>) {
    val runSeconds = args.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_SECONDS
    println("Lumos looks, live: [b]ase [w]orking [s]earching [r]easoning | [i]dle [t]hinking [u]ncertain | [q]uit")
    withRawKeys { playLooks(runSeconds) }
}

private fun playLooks(runSeconds: Int) {
    val terminal = DefaultTerminalSize().current()
    val width = terminal.columns.coerceAtLeast(MIN_WIDTH)
    val height = (terminal.rows - STATUS_LINES).coerceAtLeast(MIN_HEIGHT)

    val lattice = CliLattice(width = width, height = height)
    val orb = CliOrb(lattice = lattice, targetFps = FPS)
    val source = LiveFrameSource(AtmospherePresets.THINKING, renderConfig = LumosRenderConfig(rimFade = true))
    val builder = source.builder
    var atmosphereName = "thinking"

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
                    'b' -> builder.look = Look(LumosLook.BASE)
                    'w' -> builder.look = Look(LumosLook.WORKING)
                    's' -> builder.look = Look(LumosLook.SEARCHING)
                    'r' -> builder.look = Look(LumosLook.REASONING)
                    else ->
                        atmosphereFor(key)?.let { (name, atmosphere) ->
                            atmosphereName = name
                            source.runtime.setAtmosphere(atmosphere)
                        }
                }
            }

            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000.0).coerceAtMost(MAX_STEP_SECONDS).toFloat()
            last = now

            val frame = source.next(dt)
            orb.render(CliGlyph.overlay(lattice.project(frame)))
            status(builder.look, atmosphereName, width)
            rendered++

            val spare = frameNanos - (System.nanoTime() - frameStart)
            if (spare > 0) Thread.sleep(spare / 1_000_000L, (spare % 1_000_000L).toInt())
        }
    } finally {
        orb.stop()
    }

    println("rendered $rendered live frames; left wearing ${builder.look.key} over $atmosphereName")
}

private fun status(
    look: Look,
    atmosphereName: String,
    width: Int,
    out: PrintStream = System.out,
) {
    val line =
        "[b]ase [w]orking [s]earching [r]easoning [i][t][u] [q]uit | " +
            "look ${look.key} | atmosphere $atmosphereName"
    val room = (width - 1).coerceAtLeast(1)
    out.print(line.take(room).padEnd(room))
}

private fun atmosphereFor(key: Char): Pair<String, AtmosphereState>? =
    when (key.lowercaseChar()) {
        'i' -> "idle" to AtmospherePresets.IDLE
        't' -> "thinking" to AtmospherePresets.THINKING
        'u' -> "uncertain" to AtmospherePresets.UNCERTAIN
        else -> null
    }
