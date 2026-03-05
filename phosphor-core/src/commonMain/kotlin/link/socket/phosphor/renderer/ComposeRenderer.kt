package link.socket.phosphor.renderer

import kotlin.math.roundToInt
import link.socket.phosphor.color.AnsiColorAdapter
import link.socket.phosphor.color.PlatformColorAdapter

/**
 * Generic color model for Compose-like draw surfaces.
 */
data class ComposeColor(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Float = 1f,
) {
    init {
        require(red in 0..255) { "red must be 0..255, got $red" }
        require(green in 0..255) { "green must be 0..255, got $green" }
        require(blue in 0..255) { "blue must be 0..255, got $blue" }
        require(alpha in 0f..1f) { "alpha must be 0..1, got $alpha" }
    }

    fun lighten(factor: Float): ComposeColor {
        val clamped = factor.coerceIn(0f, 1f)
        return ComposeColor(
            red = (red + ((255 - red) * clamped)).roundToInt(),
            green = (green + ((255 - green) * clamped)).roundToInt(),
            blue = (blue + ((255 - blue) * clamped)).roundToInt(),
            alpha = alpha,
        )
    }
}

data class ComposeGradient(
    val start: ComposeColor,
    val end: ComposeColor,
)

data class ComposeDrawCommand(
    val x: Int,
    val y: Int,
    val char: Char,
    val gradient: ComposeGradient,
    val alpha: Float,
    val bold: Boolean,
)

data class ComposeRenderFrame(
    val width: Int,
    val height: Int,
    val commands: List<ComposeDrawCommand>,
)

/**
 * Converts a simulation frame into draw commands usable by Canvas-based adapters.
 */
class ComposeRenderer(
    override val preferredFps: Int = DEFAULT_TARGET_FPS,
    private val skipWhitespaceCells: Boolean = true,
    private val colorAdapter: PlatformColorAdapter<ComposeColor> = ComposeColorAdapter(),
    private val ansiColorAdapter: AnsiColorAdapter = AnsiColorAdapter.DEFAULT,
) : PhosphorRenderer<ComposeRenderFrame> {
    override val target: RenderTarget = RenderTarget.COMPOSE

    init {
        require(preferredFps > 0) { "preferredFps must be > 0, got $preferredFps" }
    }

    override fun render(frame: SimulationFrame): ComposeRenderFrame {
        val commands = ArrayList<ComposeDrawCommand>(frame.cells.size)

        for (row in 0 until frame.height) {
            for (col in 0 until frame.width) {
                val cell = frame.cellAt(row, col)
                if (skipWhitespaceCells && cell.char == ' ') continue

                val start = colorAdapter.adapt(ansiColorAdapter.neutralFromAnsi256(cell.fgColor))
                val end = start.lighten(if (cell.bold) 0.25f else 0.12f)
                val alpha = (cell.luminance ?: if (cell.char == ' ') 0f else 1f).coerceIn(0f, 1f)

                commands +=
                    ComposeDrawCommand(
                        x = col,
                        y = row,
                        char = cell.char,
                        gradient = ComposeGradient(start = start, end = end),
                        alpha = alpha,
                        bold = cell.bold,
                    )
            }
        }

        return ComposeRenderFrame(
            width = frame.width,
            height = frame.height,
            commands = commands,
        )
    }

    companion object {
        const val DEFAULT_TARGET_FPS: Int = 60

        private val defaultAnsiAdapter = AnsiColorAdapter.DEFAULT
        private val defaultComposeAdapter = ComposeColorAdapter()

        internal fun ansi256ToColor(index: Int): ComposeColor {
            val neutral = defaultAnsiAdapter.neutralFromAnsi256(index)
            return defaultComposeAdapter.adapt(neutral)
        }
    }
}
