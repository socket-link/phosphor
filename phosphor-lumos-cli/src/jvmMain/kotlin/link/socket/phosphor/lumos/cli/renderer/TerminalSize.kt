package link.socket.phosphor.lumos.cli.renderer

/**
 * Provides the current terminal dimensions to [CliOrb] for resize polling and
 * orb-region sizing. Abstracted behind an interface so tests can inject fake
 * dimensions and trigger the resize codepath deterministically.
 */
fun interface TerminalSize {
    fun current(): Dimensions

    /**
     * Terminal viewport dimensions in character cells.
     *
     * @property columns Visible columns (>= 1).
     * @property rows Visible rows (>= 1).
     */
    data class Dimensions(
        val columns: Int,
        val rows: Int,
    ) {
        init {
            require(columns >= 1) { "columns must be >= 1, got $columns" }
            require(rows >= 1) { "rows must be >= 1, got $rows" }
        }
    }

    companion object {
        /** Conservative fallback when no real dimensions can be detected. */
        val FALLBACK: Dimensions = Dimensions(columns = 80, rows = 24)

        /** Fixed-size provider for tests and headless usage. */
        fun fixed(
            columns: Int,
            rows: Int,
        ): TerminalSize = TerminalSize { Dimensions(columns, rows) }
    }
}

/**
 * Default JVM [TerminalSize] provider.
 *
 * Cross-platform best effort:
 *  1. Honor explicit `phosphor.term.columns` / `phosphor.term.rows` JVM
 *     properties when set — useful in tests and headless containers.
 *  2. Honor `COLUMNS` / `LINES` environment variables when present.
 *  3. Fall back to [TerminalSize.FALLBACK] (80x24).
 *
 * Native terminal queries (TIOCGWINSZ on Unix, GetConsoleScreenBufferInfo on
 * Windows) are intentionally out of scope: they require either a JNI library
 * or JLine, which would add a runtime dependency this module avoids. Most
 * embedders run inside a shell that exports `LINES` and `COLUMNS`, and the
 * fallback keeps the renderer usable when nothing is exported.
 */
class DefaultTerminalSize : TerminalSize {
    override fun current(): TerminalSize.Dimensions {
        val cols = readDimension("phosphor.term.columns", "COLUMNS") ?: TerminalSize.FALLBACK.columns
        val rows = readDimension("phosphor.term.rows", "LINES") ?: TerminalSize.FALLBACK.rows
        return TerminalSize.Dimensions(columns = cols, rows = rows)
    }

    private fun readDimension(
        systemProperty: String,
        environmentVariable: String,
    ): Int? {
        val fromProperty = System.getProperty(systemProperty)?.toIntOrNull()?.takeIf { it >= 1 }
        if (fromProperty != null) return fromProperty
        return System.getenv(environmentVariable)?.toIntOrNull()?.takeIf { it >= 1 }
    }
}
