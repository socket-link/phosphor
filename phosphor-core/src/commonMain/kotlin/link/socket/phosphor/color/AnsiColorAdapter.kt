package link.socket.phosphor.color

/**
 * ANSI output mode.
 */
enum class AnsiColorMode {
    ANSI_256,
    TRUECOLOR,
    ;

    companion object {
        @Deprecated(
            message = "Use TRUECOLOR.",
            replaceWith = ReplaceWith("AnsiColorMode.TRUECOLOR"),
        )
        val TRUE_COLOR: AnsiColorMode = TRUECOLOR
    }
}

/**
 * Adapts neutral colors to ANSI terminal escape sequences.
 *
 * In ANSI_256 mode, colors are quantized to the nearest ANSI palette index.
 * In TRUECOLOR mode, exact RGB values are emitted using 24-bit escapes.
 */
class AnsiColorAdapter(
    private val mode: AnsiColorMode = AnsiColorMode.ANSI_256,
) : PlatformColorAdapter<String> {
    override fun adapt(color: NeutralColor): String = foreground(color)

    fun foreground(color: NeutralColor): String {
        return when (mode) {
            AnsiColorMode.ANSI_256 -> foregroundEscapeForCode(ansi256Code(color))
            AnsiColorMode.TRUECOLOR -> {
                "${ESC}38;2;${color.redInt};${color.greenInt};${color.blueInt}m"
            }
        }
    }

    fun background(color: NeutralColor): String {
        return when (mode) {
            AnsiColorMode.ANSI_256 -> backgroundEscapeForCode(ansi256Code(color))
            AnsiColorMode.TRUECOLOR -> {
                "${ESC}48;2;${color.redInt};${color.greenInt};${color.blueInt}m"
            }
        }
    }

    fun ansi256Code(color: NeutralColor): Int = Ansi256Palette.nearestIndex(color)

    fun neutralFromAnsi256(index: Int): NeutralColor = ansi256ToNeutral(index)

    companion object {
        private const val ESC = "\u001B["
        const val RESET = "${ESC}0m"

        val DEFAULT: AnsiColorAdapter = AnsiColorAdapter()

        fun ansi256ToNeutral(index: Int): NeutralColor = Ansi256Palette.neutralAt(index)

        fun foregroundEscapeForCode(code: Int): String = "${ESC}38;5;${code.coerceIn(0, 255)}m"

        fun backgroundEscapeForCode(code: Int): String = "${ESC}48;5;${code.coerceIn(0, 255)}m"
    }
}
