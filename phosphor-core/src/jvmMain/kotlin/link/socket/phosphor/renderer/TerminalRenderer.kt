package link.socket.phosphor.renderer

import link.socket.phosphor.color.AnsiColorAdapter
import link.socket.phosphor.palette.AsciiLuminancePalette

/**
 * Terminal renderer that emits ANSI-encoded text lines.
 */
data class TerminalRenderFrame(
    val width: Int,
    val height: Int,
    val lines: List<String>,
    val frameTick: Long,
    val renderedAtEpochMillis: Long,
    val rendered: Boolean,
) {
    val text: String get() = lines.joinToString("\n")
}

class TerminalRenderer(
    private val palette: AsciiLuminancePalette = AsciiLuminancePalette.STANDARD,
    override val preferredFps: Int = DEFAULT_TARGET_FPS,
    private val includeAnsi: Boolean = true,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) : PhosphorRenderer<TerminalRenderFrame> {
    override val target: RenderTarget = RenderTarget.TERMINAL

    private var lastRenderAtMillis: Long? = null
    private var lastRenderedFrame: TerminalRenderFrame? = null

    init {
        require(preferredFps > 0) { "preferredFps must be > 0, got $preferredFps" }
    }

    override fun render(frame: SimulationFrame): TerminalRenderFrame {
        val now = clockMillis()
        val lastFrame = lastRenderedFrame
        val dimensionsChanged =
            lastFrame != null && (lastFrame.width != frame.width || lastFrame.height != frame.height)

        if (!dimensionsChanged && shouldThrottle(now)) {
            if (lastFrame != null) {
                return lastFrame.copy(
                    frameTick = frame.tick,
                    renderedAtEpochMillis = now,
                    rendered = false,
                )
            }
        }

        val renderedFrame = buildFrame(frame = frame, rendered = true, now = now)
        lastRenderAtMillis = now
        lastRenderedFrame = renderedFrame
        return renderedFrame
    }

    fun resetThrottle() {
        lastRenderAtMillis = null
        lastRenderedFrame = null
    }

    private fun shouldThrottle(now: Long): Boolean {
        val last = lastRenderAtMillis ?: return false
        val frameIntervalMillis = ((1_000f / preferredFps).toLong()).coerceAtLeast(1L)
        return (now - last) < frameIntervalMillis
    }

    private fun buildFrame(
        frame: SimulationFrame,
        rendered: Boolean,
        now: Long,
    ): TerminalRenderFrame {
        val lines = ArrayList<String>(frame.height)

        for (row in 0 until frame.height) {
            val line = StringBuilder(frame.width * if (includeAnsi) 20 else 1)

            for (col in 0 until frame.width) {
                val cell = frame.cellAt(row, col)
                val char = remapChar(cell)

                if (includeAnsi) {
                    appendStyle(line, cell)
                    line.append(char)
                    line.append(AnsiColorAdapter.RESET)
                } else {
                    line.append(char)
                }
            }

            lines += line.toString()
        }

        return TerminalRenderFrame(
            width = frame.width,
            height = frame.height,
            lines = lines,
            frameTick = frame.tick,
            renderedAtEpochMillis = now,
            rendered = rendered,
        )
    }

    private fun remapChar(cell: FrameCell): Char {
        val luminance = cell.luminance ?: return cell.char

        return if (cell.normalX != null && cell.normalY != null) {
            palette.charForSurface(luminance, cell.normalX, cell.normalY)
        } else {
            palette.charForLuminance(luminance)
        }
    }

    private fun appendStyle(
        builder: StringBuilder,
        cell: FrameCell,
    ) {
        builder.append(if (cell.bold) ANSI_BOLD else ANSI_NORMAL_WEIGHT)
        builder.append(AnsiColorAdapter.foregroundEscapeForCode(cell.fgColor))
        cell.bgColor?.let { bg -> builder.append(AnsiColorAdapter.backgroundEscapeForCode(bg)) }
    }

    companion object {
        const val DEFAULT_TARGET_FPS: Int = 30

        private const val ESC = "\u001B["
        private const val ANSI_BOLD = "${ESC}1m"
        private const val ANSI_NORMAL_WEIGHT = "${ESC}22m"
    }
}
