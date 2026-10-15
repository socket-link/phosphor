package link.socket.phosphor.lumos.cli.bench

import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.cli.projection.CliLattice
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.FrameBlend
import link.socket.phosphor.trace.Oscilloscope
import link.socket.phosphor.trace.SeamChannel
import link.socket.phosphor.trace.TraceSegment
import link.socket.phosphor.trace.VoxelTrace
import link.socket.phosphor.trace.seamError

/**
 * PHO-43 Task A recon: how badly does a captured `loop` segment wrap?
 *
 * Captures each canonical preset at 20 fps / resolution 10, cuts 4 s and 8 s
 * loops out of the steady state, and measures the seam four ways for each of
 * the candidate mechanisms:
 *
 * - the trace-level metric ([seamError]), per channel;
 * - CLI visibility through [CliLattice]: terminal cells whose glyph changes, and
 *   the mean truecolour change of lit cells. Since PHO-48 [CliLattice] rotates
 *   each voxel by the frame's orb rotation, so a rotation seam shows in these
 *   numbers as well as in the Compose raster;
 * - Compose visibility: mean pixel change through a Java2D raster of the exact
 *   `ComposeLattice` projection (Euler X → Y → Z, orthographic, painter-sorted),
 *   since there is no Compose host here.
 *
 * Every visibility number is a ratio against the median ordinary step of the
 * same loop, so `~1` reads as one more frame of motion.
 *
 * Writes `seam-recon.md` (tables), `cli-seams.ansi` (`cat` it in a truecolour
 * terminal), and `compose-seams-*.png` / `cli-seams-*.png` strips into the
 * output directory.
 *
 * Run: `./gradlew :phosphor-lumos-cli:runSeamRecon -Pout=/abs/path`
 */
private const val FPS = 20
private const val RESOLUTION = 10

/** Frames of steady state before each loop: what a baked blend fades toward. */
private const val PRE_ROLL = 20

/** Length of the baked seam blend, in frames. */
private const val BLEND_FRAMES = 20
private const val CAPTURE_FRAMES = PRE_ROLL + 200
private val LOOP_SECONDS = listOf(4, 8)

private const val CLI_WIDTH = 64
private const val CLI_HEIGHT = 32
private const val RASTER_PX = 192

/**
 * Zoom applied on top of the `ComposeLattice` mapping. Jittered voxels reach
 * past `[-1, 1]`, so the stock mapping clips the orb's limb — which is where a
 * rotation seam shows first.
 */
private const val RASTER_VIEW = 0.6f
private const val TWO_PI = (2.0 * PI).toFloat()

/** A candidate continuity mechanism, applied to one steady-state capture. */
private enum class Mechanism(
    val label: String,
) {
    RAW("as captured"),
    PULSE_ALIGNED("2: pulse-aligned length"),
    BAKED("3: baked blend (rotation blended)"),
    CARRY("5: rotation carried"),
    PULSE_ALIGNED_CARRY("2+5"),
    BAKED_CARRY("3+5: baked blend, rotation carried"),
}

/**
 * One loop as a viewer sees it: [frames] in order, then [next] — the frame the
 * player shows after the last one. The step `frames.last() → next` is the seam.
 *
 * @property windowSteps How many steps before the seam a mechanism reshaped;
 *  their worst ratio is reported beside the seam's, because a blend that hides
 *  a pop by speeding the motion up has moved the problem rather than solved it.
 */
private class Playback(
    val frames: List<VoxelFrame>,
    val next: VoxelFrame,
    val windowSteps: Int = 0,
) {
    val steps: Int get() = frames.size

    fun pair(step: Int): Pair<VoxelFrame, VoxelFrame> =
        frames[step] to (if (step + 1 < frames.size) frames[step + 1] else next)
}

/**
 * Ratios for one measure: the seam and the worst reshaped step over the median
 * ordinary step, and the seam over the *largest* ordinary step. The last is the
 * visibility test for bursty measures — a quantized glyph ramp flips every voxel
 * on the same frame — where a seam is only conspicuous if the loop never does
 * anything that big on its own.
 */
private data class Ratio(
    val seam: Float,
    val window: Float,
    val seamOverMax: Float,
)

private data class Row(
    val preset: String,
    val seconds: Int,
    val mechanism: Mechanism,
    val loopFrames: Int,
    val channels: Map<String, Ratio>,
    val cliGlyphs: Ratio,
    val cliColor: Ratio,
    val compose: Ratio,
    val rotationJumpDegrees: Float,
)

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "true")
    val out = File(args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: ".context/seam-recon").apply { mkdirs() }

    val rows = ArrayList<Row>()
    val ansi = StringBuilder()
    val strips = LinkedHashMap<Mechanism, MutableList<Pair<String, Playback>>>()

    for ((name, preset) in AtmospherePresets.ALL) {
        val captured = capture(preset.copy(resolution = RESOLUTION)).toFrames()
        for (seconds in LOOP_SECONDS) {
            val loopFrames = seconds * FPS
            val alignedFrames = pulseAlignedFrames(preset, seconds)
            val playbacks =
                mapOf(
                    Mechanism.RAW to plain(captured, loopFrames),
                    Mechanism.PULSE_ALIGNED to plain(captured, alignedFrames),
                    Mechanism.BAKED to baked(captured, loopFrames, carryRotation = false),
                    Mechanism.CARRY to carried(plain(captured, loopFrames)),
                    Mechanism.PULSE_ALIGNED_CARRY to carried(plain(captured, alignedFrames)),
                    Mechanism.BAKED_CARRY to baked(captured, loopFrames, carryRotation = true),
                )
            playbacks.forEach { (mechanism, playback) ->
                rows += measure(name, seconds, mechanism, playback)
                if (seconds == LOOP_SECONDS.first()) {
                    strips.getOrPut(mechanism) { ArrayList() } += name to playback
                }
            }
            if (seconds == LOOP_SECONDS.first()) {
                ansi.append(cliSeam(name, playbacks.getValue(Mechanism.RAW)))
            }
        }
    }

    File(out, "seam-recon.md").writeText(report(rows))
    File(out, "cli-seams.ansi").writeText(ansi.toString())
    strips.forEach { (mechanism, entries) ->
        val name = mechanism.name.lowercase()
        ImageIO.write(strip(mechanism, entries, ::raster), "png", File(out, "compose-seams-$name.png"))
        ImageIO.write(strip(mechanism, entries, ::terminalRaster), "png", File(out, "cli-seams-$name.png"))
    }
    println(report(rows))
    println("wrote ${out.absolutePath}")
}

// ----------------------------------------------------------------------
// Capture and mechanisms
// ----------------------------------------------------------------------

private fun capture(atmosphere: AtmosphereState): VoxelTrace =
    Oscilloscope
        .capture(
            CaptureConfig(
                params = CaptureParams(atmosphere = atmosphere),
                durationMs = CAPTURE_FRAMES * 1_000L / FPS,
                fps = FPS,
            ),
        ).getOrThrow()

/** Option 2: the nearest whole number of breaths to the target, rounded to whole frames. */
private fun pulseAlignedFrames(
    preset: AtmosphereState,
    seconds: Int,
): Int {
    val period = 1f / preset.pulseFrequency
    val cycles = (seconds / period).roundToInt().coerceAtLeast(1)
    return (cycles * period * FPS).roundToInt()
}

/** The loop as captured: `loopFrames` of steady state after the pre-roll, wrapping to its own first frame. */
private fun plain(
    captured: List<VoxelFrame>,
    loopFrames: Int,
): Playback {
    val loop = captured.subList(PRE_ROLL, PRE_ROLL + loopFrames)
    return Playback(loop, loop.first())
}

/**
 * Option 5: carry the orb's rotation across the seam instead of replaying the
 * recorded value — the next iteration starts where the last frame's rotation
 * plus one ordinary step would have put it. Ambient only; cells untouched.
 */
private fun carried(playback: Playback): Playback {
    val frames = playback.frames
    val last = frames.last().ambient
    val beforeLast = frames[frames.size - 2].ambient
    val next = playback.next
    return Playback(
        frames = frames,
        next =
            next.copy(
                ambient =
                    next.ambient.copy(
                        orbRotationX = wrap(last.orbRotationX + arc(beforeLast.orbRotationX, last.orbRotationX)),
                        orbRotationY = wrap(last.orbRotationY + arc(beforeLast.orbRotationY, last.orbRotationY)),
                        orbRotationZ = wrap(last.orbRotationZ + arc(beforeLast.orbRotationZ, last.orbRotationZ)),
                    ),
            ),
        windowSteps = playback.windowSteps,
    )
}

/**
 * Option 3: over the loop's last [BLEND_FRAMES], fade toward the pre-roll that
 * precedes its first frame, so the recording itself ends one step before it
 * begins. With [carryRotation] the fade touches cells only and rotation is
 * carried across the seam (option 5) instead of blended.
 */
private fun baked(
    captured: List<VoxelFrame>,
    loopFrames: Int,
    carryRotation: Boolean,
): Playback {
    val loop = captured.subList(PRE_ROLL, PRE_ROLL + loopFrames).toMutableList()
    for (j in 0 until BLEND_FRAMES) {
        val index = loopFrames - BLEND_FRAMES + j
        val outgoing = loop[index]
        val incoming = captured[PRE_ROLL - BLEND_FRAMES + j]
        val blended = FrameBlend(outgoing, incoming, (j + 1).toFloat() / BLEND_FRAMES).transduce()
        loop[index] =
            if (carryRotation) {
                blended.copy(
                    ambient =
                        blended.ambient.copy(
                            orbRotationX = outgoing.ambient.orbRotationX,
                            orbRotationY = outgoing.ambient.orbRotationY,
                            orbRotationZ = outgoing.ambient.orbRotationZ,
                        ),
                )
            } else {
                blended
            }
    }
    val playback = Playback(loop, loop.first(), windowSteps = BLEND_FRAMES)
    return if (carryRotation) carried(playback) else playback
}

// ----------------------------------------------------------------------
// Measurement
// ----------------------------------------------------------------------

private fun measure(
    preset: String,
    seconds: Int,
    mechanism: Mechanism,
    playback: Playback,
): Row {
    val distances = (0 until playback.steps).map { step -> channelDistances(playback.pair(step)) }
    val channelNames = distances.first().keys
    val channels =
        channelNames.associateWith { channel ->
            ratio(playback, FloatArray(playback.steps) { distances[it].getValue(channel) })
        }

    val lattice = CliLattice(CLI_WIDTH, CLI_HEIGHT)
    val projected = (playback.frames + playback.next).map { cliImage(lattice, it) }
    val cliGlyphs = ratio(playback, FloatArray(playback.steps) { glyphChanges(projected[it], projected[it + 1]) })
    val cliColor = ratio(playback, FloatArray(playback.steps) { colorChange(projected[it], projected[it + 1]) })

    val rasters = (playback.frames + playback.next).map(::raster)
    val compose = ratio(playback, FloatArray(playback.steps) { pixelDelta(rasters[it], rasters[it + 1]) })

    val (last, next) = playback.pair(playback.steps - 1)
    val rotationJump = Math.toDegrees(abs(arc(last.ambient.orbRotationY, next.ambient.orbRotationY)).toDouble())

    return Row(
        preset = preset,
        seconds = seconds,
        mechanism = mechanism,
        loopFrames = playback.frames.size,
        channels = channels,
        cliGlyphs = cliGlyphs,
        cliColor = cliColor,
        compose = compose,
        rotationJumpDegrees = rotationJump.toFloat(),
    )
}

/** Seam over the median of the steps the mechanism left alone; window is the worst reshaped step. */
private fun ratio(
    playback: Playback,
    perStep: FloatArray,
): Ratio {
    val seamIndex = playback.steps - 1
    val ordinary = perStep.copyOfRange(0, seamIndex - playback.windowSteps).sorted()
    val median = if (ordinary.isEmpty()) 0f else ordinary[ordinary.size / 2]
    val largest = ordinary.lastOrNull() ?: 0f
    val window = (seamIndex - playback.windowSteps until seamIndex).maxOfOrNull { perStep[it] } ?: 0f
    return Ratio(
        seam = SeamChannel(perStep[seamIndex], median).ratio,
        window = SeamChannel(window, median).ratio,
        seamOverMax = SeamChannel(perStep[seamIndex], largest).ratio,
    )
}

/**
 * The library's own distance, per channel, between two frames: a two-frame
 * trace's seam *is* the distance between them, so the recon and `seamError`
 * cannot disagree about what a step is.
 */
internal fun channelDistances(pair: Pair<VoxelFrame, VoxelFrame>): Map<String, Float> {
    val (a, b) = pair
    val trace =
        VoxelTrace.fromFrames(
            frames = listOf(a, b),
            fps = FPS,
            phosphorVersion = "recon",
            createdAtEpochMs = 0L,
        )
    return trace.seamError(TraceSegment("pair", 0, 1)).channels.associate { (name, channel) -> name to channel.jump }
}

/** What a truecolour terminal shows for a frame: one glyph and one packed 8-bit RGB (or -1 for blank) per cell. */
private class CliImage(
    val glyphs: CharArray,
    val rgb: IntArray,
)

private fun cliImage(
    lattice: CliLattice,
    frame: VoxelFrame,
): CliImage {
    val cells = lattice.project(frame).cells
    return CliImage(
        glyphs = CharArray(cells.size) { cells[it].char },
        rgb =
            IntArray(cells.size) { index ->
                cells[index].foreground?.toSrgb()?.let { color ->
                    (channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue)
                } ?: -1
            },
    )
}

private fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f).roundToInt()

/** Terminal cells whose glyph changed — shape, occupancy, and luminance ramp step. */
private fun glyphChanges(
    a: CliImage,
    b: CliImage,
): Float = a.glyphs.indices.count { a.glyphs[it] != b.glyphs[it] }.toFloat()

/**
 * Mean 8-bit colour change over cells lit in both frames, `0..1`. A count of
 * changed cells saturates — a moving pattern recolours nearly every cell every
 * frame — so this measures how far, not how many.
 */
private fun colorChange(
    a: CliImage,
    b: CliImage,
): Float {
    var total = 0L
    var lit = 0
    for (i in a.rgb.indices) {
        val p = a.rgb[i]
        val q = b.rgb[i]
        if (p < 0 || q < 0) continue
        lit++
        total += abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) +
            abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) +
            abs((p and 0xFF) - (q and 0xFF))
    }
    return if (lit == 0) 0f else total.toFloat() / (lit * 3 * 255)
}

/**
 * The `ComposeLattice` projection, rasterized: rotate X → Y → Z, orthographic,
 * far voxels first, zoomed by [RASTER_VIEW].
 */
internal fun raster(frame: VoxelFrame): BufferedImage {
    val image = BufferedImage(RASTER_PX, RASTER_PX, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    val ambient = frame.ambient
    val cx = cos(ambient.orbRotationX)
    val sx = sin(ambient.orbRotationX)
    val cy = cos(ambient.orbRotationY)
    val sy = sin(ambient.orbRotationY)
    val cz = cos(ambient.orbRotationZ)
    val sz = sin(ambient.orbRotationZ)
    // Half a lattice cell, the base radius the 0.7.0 voxelGap fix documents.
    val radius = RASTER_VIEW * RASTER_PX / (4f * frame.resolution)

    val projected =
        frame.cells.filter { it.scale > 0f }.map { cell ->
            val y1 = cell.y * cx - cell.z * sx
            val z1 = cell.y * sx + cell.z * cx
            val x2 = cell.x * cy + z1 * sy
            val z2 = -cell.x * sy + z1 * cy
            val rx = x2 * cz - y1 * sz
            val ry = x2 * sz + y1 * cz
            val screenX = (rx * RASTER_VIEW + 1f) * 0.5f * RASTER_PX
            val screenY = (1f - (ry * RASTER_VIEW + 1f) * 0.5f) * RASTER_PX
            Triple(z2, floatArrayOf(screenX, screenY), cell)
        }
    for ((_, screen, cell) in projected.sortedBy { it.first }) {
        val half = radius * cell.scale
        g.color = Color(cell.red.coerceIn(0f, 1f), cell.green.coerceIn(0f, 1f), cell.blue.coerceIn(0f, 1f))
        g.fillRect(
            (screen[0] - half).roundToInt(),
            (screen[1] - half).roundToInt(),
            max(1, (2 * half).roundToInt()),
            max(1, (2 * half).roundToInt()),
        )
    }
    g.dispose()
    return image
}

/** Mean absolute per-channel pixel change, `0..1`. */
internal fun pixelDelta(
    a: BufferedImage,
    b: BufferedImage,
): Float {
    var total = 0L
    for (y in 0 until RASTER_PX) {
        for (x in 0 until RASTER_PX) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            total += abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) +
                abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) +
                abs((p and 0xFF) - (q and 0xFF))
        }
    }
    return total.toFloat() / (RASTER_PX * RASTER_PX * 3 * 255)
}

// ----------------------------------------------------------------------
// Output
// ----------------------------------------------------------------------

private fun report(rows: List<Row>): String =
    buildString {
        appendLine("## Seam ratio by preset and loop length (as captured)")
        appendLine()
        appendLine("Ratio = seam jump / median frame step. ~1 is an ordinary frame; N is an N-frame jump.")
        appendLine("Visibility columns also give seam / largest ordinary step in brackets: above 1, the seam")
        appendLine("is bigger than anything the loop does on its own.")
        appendLine()
        appendLine(
            "| preset | loop | frames | position | scale | color | glow | rotation | rotY jump | " +
                "CLI glyphs | CLI colour | Compose px |",
        )
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
        // Visibility columns: seam ÷ median step (seam ÷ largest ordinary step).
        rows.filter { it.mechanism == Mechanism.RAW }.forEach { row ->
            appendLine(
                "| ${row.preset} | ${row.seconds} s | ${row.loopFrames} | " +
                    listOf("position", "scale", "color", "glow", "rotation").joinToString(" | ") {
                        fmt(row.channels.getValue(it).seam)
                    } +
                    " | ${fmt(row.rotationJumpDegrees)}° | ${both(row.cliGlyphs)} | ${both(row.cliColor)} | " +
                    "${both(row.compose)} |",
            )
        }
        appendLine()
        appendLine("## Mechanisms")
        appendLine()
        appendLine(
            "Seam ÷ median (seam ÷ largest ordinary step); for baked blends, the worst reshaped step ÷ median in [ ].",
        )
        appendLine()
        appendLine("| preset | loop | mechanism | frames | worst channel | CLI glyphs | CLI colour | Compose px |")
        appendLine("|---|---|---|---|---|---|---|---|")
        rows.forEach { row ->
            val worst = row.channels.maxBy { it.value.seam }
            val worstWindow = row.channels.maxBy { it.value.window }
            val windowNote =
                if (row.mechanism == Mechanism.BAKED || row.mechanism == Mechanism.BAKED_CARRY) {
                    " [${worstWindow.key} ${fmt(worstWindow.value.window)}]"
                } else {
                    ""
                }
            appendLine(
                "| ${row.preset} | ${row.seconds} s | ${row.mechanism.label} | ${row.loopFrames} | " +
                    "${worst.key} ${fmt(worst.value.seam)}$windowNote | " +
                    "${both(row.cliGlyphs)}${window(row, row.cliGlyphs)} | " +
                    "${both(row.cliColor)}${window(row, row.cliColor)} | " +
                    "${both(row.compose)}${window(row, row.compose)} |",
            )
        }
    }

private fun window(
    row: Row,
    ratio: Ratio,
): String =
    if (row.mechanism == Mechanism.BAKED || row.mechanism == Mechanism.BAKED_CARRY) " [${fmt(ratio.window)}]" else ""

private fun both(ratio: Ratio): String = "${fmt(ratio.seam)} (${fmt(ratio.seamOverMax)})"

private fun fmt(value: Float): String =
    when {
        value.isInfinite() -> "∞"
        value >= 10f -> value.roundToInt().toString()
        else -> "%.2f".format(value)
    }

/** The two frames either side of the seam, glyphs in truecolour, side by side. */
private fun cliSeam(
    preset: String,
    playback: Playback,
): String {
    val lattice = CliLattice(CLI_WIDTH, CLI_HEIGHT)
    val frames =
        listOf(
            "end-1" to playback.frames[playback.frames.size - 2],
            "end" to playback.frames.last(),
            "start (after wrap)" to playback.next,
        ).map { (label, frame) -> label to lattice.project(frame) }
    return buildString {
        appendLine("== $preset, 4 s loop as captured: ${frames.joinToString("  |  ") { it.first }} ==")
        // A 2:1 character aspect puts the whole orb in the top half of the grid.
        for (row in 0 until lattice.height / 2) {
            frames.forEachIndexed { index, (_, projected) ->
                if (index > 0) append("\u001b[0m | ")
                for (column in 0 until lattice.width) {
                    val cell = projected.cells[row * lattice.width + column]
                    val color = cell.foreground?.toSrgb()
                    if (color != null) {
                        append("\u001b[38;2;${channel(color.red)};${channel(color.green)};${channel(color.blue)}m")
                    }
                    append(cell.char)
                }
            }
            appendLine("\u001b[0m")
        }
        appendLine()
    }
}

/**
 * What [CliOrb][link.socket.phosphor.lumos.cli.renderer.CliOrb] would put on a
 * truecolour terminal, as pixels: each character cell a 3×6 block in its
 * foreground colour, dimmed by its luminance-ramp step.
 */
private fun terminalRaster(frame: VoxelFrame): BufferedImage {
    val lattice = CliLattice(CLI_WIDTH, CLI_HEIGHT)
    val projected = lattice.project(frame)
    val image = BufferedImage(RASTER_PX, RASTER_PX, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    val cellWidth = RASTER_PX / CLI_WIDTH
    val cellHeight = cellWidth * 2
    val ramp = CliLattice.LUMINANCE_RAMP
    for (row in 0 until CLI_HEIGHT / 2) {
        for (column in 0 until CLI_WIDTH) {
            val cell = projected.cells[row * CLI_WIDTH + column]
            val color = cell.foreground?.toSrgb() ?: continue
            val weight = 1f - ramp.indexOf(cell.char).coerceAtLeast(0).toFloat() / (ramp.length - 1)
            g.color =
                Color(
                    (color.red * weight).coerceIn(0f, 1f),
                    (color.green * weight).coerceIn(0f, 1f),
                    (color.blue * weight).coerceIn(0f, 1f),
                )
            g.fillRect(column * cellWidth, row * cellHeight + RASTER_PX / 4, cellWidth, cellHeight)
        }
    }
    g.dispose()
    return image
}

/** Rows of `end-1 | end | start` images, one row per preset, for a 4 s loop under [mechanism]. */
private fun strip(
    mechanism: Mechanism,
    entries: List<Pair<String, Playback>>,
    render: (VoxelFrame) -> BufferedImage,
): BufferedImage {
    val label = 18
    val image = BufferedImage(RASTER_PX * 3, (RASTER_PX + label) * entries.size, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
    entries.forEachIndexed { row, (preset, playback) ->
        val top = row * (RASTER_PX + label)
        g.color = Color.WHITE
        g.drawString("$preset · ${mechanism.label} · end-1 | end | start", 4, top + 13)
        listOf(playback.frames[playback.frames.size - 2], playback.frames.last(), playback.next)
            .forEachIndexed { column, frame -> g.drawImage(render(frame), column * RASTER_PX, top + label, null) }
    }
    g.dispose()
    return image
}

// ----------------------------------------------------------------------
// Angles
// ----------------------------------------------------------------------

private fun arc(
    from: Float,
    to: Float,
): Float {
    var arc = (to - from) % TWO_PI
    if (arc > PI.toFloat()) arc -= TWO_PI
    if (arc < -PI.toFloat()) arc += TWO_PI
    return arc
}

private fun wrap(angle: Float): Float {
    var wrapped = angle % TWO_PI
    if (wrapped < 0f) wrapped += TWO_PI
    return wrapped
}
