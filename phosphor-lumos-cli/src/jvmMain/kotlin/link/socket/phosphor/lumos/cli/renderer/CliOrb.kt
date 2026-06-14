package link.socket.phosphor.lumos.cli.renderer

import java.io.PrintStream
import link.socket.phosphor.color.AnsiColorMap
import link.socket.phosphor.color.AnsiColorMode
import link.socket.phosphor.color.OklabColor
import link.socket.phosphor.lumos.LumosRenderTarget
import link.socket.phosphor.lumos.LumosRenderer
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.cli.frame.LumosTerminalFrame
import link.socket.phosphor.lumos.cli.glyph.CliGlyph
import link.socket.phosphor.lumos.cli.projection.CliLattice
import link.socket.phosphor.lumos.probe.FramePhase
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.lumos.probe.measure

/**
 * JVM terminal renderer for [LumosTerminalFrame].
 *
 * Writes ANSI escape sequences directly to a [PrintStream] (default `System.out`),
 * completing the Wave 2 pipeline:
 *
 * ```
 * VoxelFrame → CliLattice.project → LumosTerminalFrame
 *            → CliGlyph.overlay   → LumosTerminalFrame (with glyph)
 *            → CliOrb.render      → stdout ANSI bytes
 * ```
 *
 * The renderer never blocks on stdin and never installs signal handlers. Resize
 * detection is polled every [resizePollFrames] renders via the injected
 * [terminalSize] provider so the implementation works on Unix and Windows JVMs
 * uniformly.
 *
 * [LumosRenderer] is satisfied by overriding `render(VoxelFrame)` to perform
 * the full projection-and-overlay pipeline internally — useful for embedders
 * that bind CliOrb directly to a [link.socket.phosphor.runtime.CognitiveSceneRuntime].
 * Embedders that have already projected (e.g. for snapshot testing) call
 * `render(LumosTerminalFrame)` instead.
 *
 * @param out Destination for ANSI bytes. Defaults to standard output.
 * @param colorMode Color quantization strategy delegated to [AnsiColorMap].
 * @param targetFps Informational throttling hint exposed via [preferredFps].
 *  The renderer itself does not schedule frames; the caller drives [render].
 * @param clearMode `FULL` rewrites every cell each frame; `DIFFERENTIAL` only
 *  writes changed cells against the previous frame.
 * @param terminalSize Provider for the current terminal viewport. Injected
 *  so tests can drive deterministic resize behavior.
 * @param resizePollFrames Re-query [terminalSize] every N frames. Set to 0 to
 *  disable polling entirely (useful in tests that drive resize manually).
 * @param lattice Optional projection lattice. Required only when callers use
 *  the [LumosRenderer] interface method `render(VoxelFrame)` directly.
 * @param probe Per-phase timing sink. Defaults to [FrameProbe.Disabled], which
 *  adds no per-frame cost; pass a [link.socket.phosphor.lumos.probe.RingBufferFrameProbe]
 *  to capture DRAW timings (the terminal write).
 */
class CliOrb(
    private val out: PrintStream = System.out,
    private val colorMode: AnsiColorMode = AnsiColorMode.TRUECOLOR,
    val targetFps: Int = DEFAULT_TARGET_FPS,
    private val clearMode: ClearMode = ClearMode.DIFFERENTIAL,
    private val terminalSize: TerminalSize = DefaultTerminalSize(),
    private val resizePollFrames: Int = DEFAULT_RESIZE_POLL_FRAMES,
    private val lattice: CliLattice? = null,
    private val probe: FrameProbe = FrameProbe.Disabled,
) : LumosRenderer<LumosTerminalFrame> {
    init {
        require(targetFps > 0) { "targetFps must be > 0, got $targetFps" }
        require(resizePollFrames >= 0) {
            "resizePollFrames must be >= 0, got $resizePollFrames"
        }
    }

    override val target: LumosRenderTarget = LumosRenderTarget.VOXEL_TERMINAL
    override val preferredFps: Int = targetFps

    private var initialized: Boolean = false
    private var stopped: Boolean = false
    private var lastFrame: LumosTerminalFrame? = null
    private var lastSize: TerminalSize.Dimensions? = null
    private var framesSinceResizeCheck: Int = 0
    private var shutdownHook: Thread? = null

    /**
     * Project [frame] via [lattice], overlay any active glyph, then render to
     * [out]. The projected [LumosTerminalFrame] is returned so callers can
     * inspect it for tests or downstream consumers.
     *
     * Requires a non-null `lattice` constructor argument. If the caller has
     * already projected, use the [render] overload that accepts a
     * [LumosTerminalFrame] instead.
     */
    override fun render(frame: VoxelFrame): LumosTerminalFrame {
        val activeLattice =
            lattice
                ?: error(
                    "CliOrb.render(VoxelFrame) requires a CliLattice; either pass one to " +
                        "the constructor or project externally and call render(LumosTerminalFrame).",
                )
        val projected = activeLattice.project(frame)
        val withGlyph = CliGlyph.overlay(projected)
        render(withGlyph)
        return withGlyph
    }

    /**
     * Render an already-projected [frame] to [out]. Safe to call repeatedly;
     * the first call hides the cursor and clears the screen, subsequent calls
     * follow [clearMode].
     *
     * Calls after [stop] are no-ops.
     */
    fun render(frame: LumosTerminalFrame) {
        if (stopped) return
        probe.measure(FramePhase.DRAW) { renderFrame(frame) }
    }

    private fun renderFrame(frame: LumosTerminalFrame) {
        var forceFull = clearMode == ClearMode.FULL
        if (!initialized) {
            out.print(CURSOR_HIDE)
            out.print(CLEAR_SCREEN)
            initialized = true
            lastSize = terminalSize.current()
            forceFull = true
        } else if (resizePollFrames > 0 && framesSinceResizeCheck >= resizePollFrames) {
            framesSinceResizeCheck = 0
            val now = terminalSize.current()
            if (now != lastSize) {
                lastSize = now
                out.print(CLEAR_SCREEN)
                forceFull = true
            }
        }
        framesSinceResizeCheck++

        val previousFrame =
            if (forceFull ||
                lastFrame?.width != frame.width ||
                lastFrame?.height != frame.height
            ) {
                null
            } else {
                lastFrame
            }

        writeCells(frame, previousFrame)
        parkCursor(frame)
        lastFrame = frame
        out.flush()
    }

    /**
     * Begin a render session and install a JVM shutdown hook that calls
     * [stop]. Calling [start] more than once has no additional effect.
     */
    fun start() {
        if (shutdownHook != null) return
        val hook = Thread({ stop() }, "CliOrb-shutdown")
        Runtime.getRuntime().addShutdownHook(hook)
        shutdownHook = hook
    }

    /**
     * Clear the orb region, restore the cursor, and mark this renderer as
     * stopped so further [render] calls are no-ops. Idempotent.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        if (initialized) {
            out.print(CLEAR_SCREEN)
            out.print(CURSOR_HOME)
        }
        out.print(CURSOR_SHOW)
        out.print(STYLE_RESET)
        out.flush()

        val hook = shutdownHook
        if (hook != null) {
            shutdownHook = null
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (_: IllegalStateException) {
                // JVM is already shutting down — the hook is running us and
                // cannot be removed. Safe to ignore.
            }
        }
    }

    private fun writeCells(
        frame: LumosTerminalFrame,
        previous: LumosTerminalFrame?,
    ) {
        val width = frame.width
        val height = frame.height
        if (width == 0 || height == 0) return

        var lastFg: OklabColor? = null
        var lastBg: OklabColor? = null
        var lastBold = false
        var styleEstablished = false
        var cursorRow = -1
        var cursorCol = -1

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val cell = frame.cells[idx]
                val prevCell = previous?.cells?.get(idx)
                if (prevCell != null && prevCell == cell) {
                    continue
                }

                if (cursorRow != y || cursorCol != x) {
                    out.print(cursorMove(y, x))
                }

                val fgChanged = cell.foreground != lastFg
                val bgChanged = cell.background != lastBg
                val boldChanged = cell.bold != lastBold
                if (!styleEstablished || fgChanged || bgChanged || boldChanged) {
                    out.print(STYLE_RESET)
                    if (cell.bold) out.print(STYLE_BOLD)
                    cell.foreground?.let { out.print(AnsiColorMap.escape(it, colorMode)) }
                    cell.background?.let {
                        out.print(AnsiColorMap.backgroundEscape(it, colorMode))
                    }
                    lastFg = cell.foreground
                    lastBg = cell.background
                    lastBold = cell.bold
                    styleEstablished = true
                }

                out.print(cell.char)
                cursorRow = y
                cursorCol = x + 1
            }
        }

        if (styleEstablished) {
            out.print(STYLE_RESET)
        }
    }

    private fun parkCursor(frame: LumosTerminalFrame) {
        // Park just below the orb region so the cursor never sits on a visible
        // cell. Bounded by the current terminal height when known.
        val viewportRows = lastSize?.rows ?: (frame.height + 1)
        val parkRow = (frame.height + 1).coerceAtMost(viewportRows).coerceAtLeast(1)
        out.print(cursorMove(parkRow - 1, 0))
    }

    private fun cursorMove(
        row: Int,
        col: Int,
    ): String = "${CSI}${row + 1};${col + 1}H"

    companion object {
        const val DEFAULT_TARGET_FPS: Int = 30
        const val DEFAULT_RESIZE_POLL_FRAMES: Int = 30

        internal const val CSI: String = "["
        internal const val CURSOR_HIDE: String = "$CSI?25l"
        internal const val CURSOR_SHOW: String = "$CSI?25h"
        internal const val CURSOR_HOME: String = "${CSI}H"
        internal const val CLEAR_SCREEN: String = "${CSI}2J"
        internal const val STYLE_RESET: String = "${CSI}0m"
        internal const val STYLE_BOLD: String = "${CSI}1m"
    }
}
