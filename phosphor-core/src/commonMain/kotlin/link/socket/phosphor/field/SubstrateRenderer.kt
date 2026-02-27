package link.socket.phosphor.field

import link.socket.phosphor.math.Vector2

/**
 * Interface for rendering substrate state to output.
 *
 * Allows platform-agnostic substrate rendering with different
 * output implementations (terminal, test, etc.).
 */
interface SubstrateRenderer {
    /**
     * Render the substrate state to a list of strings (one per row).
     *
     * @param state The substrate state to render
     * @return List of rendered rows
     */
    fun render(state: SubstrateState): List<String>

    /**
     * Render the substrate state to a single string.
     *
     * @param state The substrate state to render
     * @return Complete rendered output
     */
    fun renderToString(state: SubstrateState): String {
        return render(state).joinToString("\n")
    }
}

/**
 * Basic substrate renderer that converts density to glyphs.
 *
 * @property useUnicode Whether to use Unicode glyphs
 * @property showFlowIndicators Whether to show flow direction indicators
 */
class BasicSubstrateRenderer(
    private val useUnicode: Boolean = true,
    private val showFlowIndicators: Boolean = false,
) : SubstrateRenderer {
    override fun render(state: SubstrateState): List<String> {
        return buildList {
            for (y in 0 until state.height) {
                val row =
                    buildString {
                        for (x in 0 until state.width) {
                            val density = state.getDensity(x, y)
                            val glyph =
                                if (showFlowIndicators) {
                                    val flow = state.getFlow(x, y)
                                    if (flow.length() > 0.3f) {
                                        flowToArrow(flow, useUnicode)
                                    } else {
                                        SubstrateGlyphs.forDensity(density, useUnicode)
                                    }
                                } else {
                                    SubstrateGlyphs.forDensity(density, useUnicode)
                                }
                            append(glyph)
                        }
                    }
                add(row)
            }
        }
    }

    private fun flowToArrow(
        flow: Vector2,
        useUnicode: Boolean,
    ): Char {
        // Determine primary direction
        val absX = kotlin.math.abs(flow.x)
        val absY = kotlin.math.abs(flow.y)

        return if (useUnicode) {
            when {
                absX > absY && flow.x > 0 -> '\u2192' // →
                absX > absY && flow.x < 0 -> '\u2190' // ←
                absY > absX && flow.y > 0 -> '\u2193' // ↓
                absY > absX && flow.y < 0 -> '\u2191' // ↑
                flow.x > 0 && flow.y > 0 -> '\u2198' // ↘
                flow.x > 0 && flow.y < 0 -> '\u2197' // ↗
                flow.x < 0 && flow.y > 0 -> '\u2199' // ↙
                flow.x < 0 && flow.y < 0 -> '\u2196' // ↖
                else -> '\u00B7' // ·
            }
        } else {
            when {
                absX > absY && flow.x > 0 -> '>'
                absX > absY && flow.x < 0 -> '<'
                absY > absX && flow.y > 0 -> 'v'
                absY > absX && flow.y < 0 -> '^'
                else -> '.'
            }
        }
    }
}

/**
 * Substrate renderer with ANSI color support.
 *
 * @property useUnicode Whether to use Unicode glyphs
 * @property colorScheme Color scheme to use
 */
class ColoredSubstrateRenderer(
    private val useUnicode: Boolean = true,
    private val colorScheme: SubstrateColorScheme = SubstrateColorScheme.DEFAULT,
) : SubstrateRenderer {
    override fun render(state: SubstrateState): List<String> {
        return buildList {
            for (y in 0 until state.height) {
                val row =
                    buildString {
                        for (x in 0 until state.width) {
                            val density = state.getDensity(x, y)
                            val glyph = SubstrateGlyphs.forDensity(density, useUnicode)
                            val color = colorScheme.getColorForDensity(density)
                            append(color)
                            append(glyph)
                        }
                        append(ANSI_RESET)
                    }
                add(row)
            }
        }
    }

    companion object {
        private const val ANSI_RESET = "\u001B[0m"
    }
}

/**
 * Color scheme for substrate rendering.
 */
class SubstrateColorScheme(
    private val colors: List<String>,
) {
    init {
        require(colors.size == 5) { "Color scheme must have exactly 5 colors" }
    }

    fun getColorForDensity(density: Float): String {
        val index =
            when {
                density < 0.2f -> 0
                density < 0.4f -> 1
                density < 0.6f -> 2
                density < 0.8f -> 3
                else -> 4
            }
        return colors[index]
    }

    companion object {
        /** Default blue-cyan color scheme */
        val DEFAULT =
            SubstrateColorScheme(
                listOf(
                    // Dark gray
                    "\u001B[38;5;236m",
                    // Gray
                    "\u001B[38;5;240m",
                    // Light gray
                    "\u001B[38;5;250m",
                    // Cyan
                    "\u001B[38;5;45m",
                    // Bright cyan
                    "\u001B[38;5;51m",
                ),
            )

        /** Green-teal color scheme */
        val MATRIX =
            SubstrateColorScheme(
                listOf(
                    // Dark green
                    "\u001B[38;5;22m",
                    // Green
                    "\u001B[38;5;28m",
                    // Bright green
                    "\u001B[38;5;34m",
                    // Lime
                    "\u001B[38;5;46m",
                    // Bright lime
                    "\u001B[38;5;118m",
                ),
            )

        /** Purple-magenta color scheme */
        val ENERGY =
            SubstrateColorScheme(
                listOf(
                    // Dark purple
                    "\u001B[38;5;53m",
                    // Purple
                    "\u001B[38;5;127m",
                    // Magenta
                    "\u001B[38;5;165m",
                    // Pink
                    "\u001B[38;5;207m",
                    // Light pink
                    "\u001B[38;5;213m",
                ),
            )

        /** Gold-amber color scheme */
        val WARM =
            SubstrateColorScheme(
                listOf(
                    // Dark brown
                    "\u001B[38;5;94m",
                    // Brown
                    "\u001B[38;5;130m",
                    // Orange
                    "\u001B[38;5;172m",
                    // Gold
                    "\u001B[38;5;214m",
                    // Yellow
                    "\u001B[38;5;226m",
                ),
            )
    }
}
