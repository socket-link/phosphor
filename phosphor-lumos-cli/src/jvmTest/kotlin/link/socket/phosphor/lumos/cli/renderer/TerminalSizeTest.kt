package link.socket.phosphor.lumos.cli.renderer

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TerminalSizeTest {
    private var savedColumns: String? = null
    private var savedRows: String? = null

    @BeforeTest
    fun saveProperties() {
        savedColumns = System.getProperty(PROPERTY_COLUMNS)
        savedRows = System.getProperty(PROPERTY_ROWS)
        System.clearProperty(PROPERTY_COLUMNS)
        System.clearProperty(PROPERTY_ROWS)
    }

    @AfterTest
    fun restoreProperties() {
        savedColumns?.let { System.setProperty(PROPERTY_COLUMNS, it) }
            ?: System.clearProperty(PROPERTY_COLUMNS)
        savedRows?.let { System.setProperty(PROPERTY_ROWS, it) }
            ?: System.clearProperty(PROPERTY_ROWS)
    }

    @Test
    fun `default provider honors system properties when set`() {
        System.setProperty(PROPERTY_COLUMNS, "120")
        System.setProperty(PROPERTY_ROWS, "40")

        val dims = DefaultTerminalSize().current()

        assertEquals(120, dims.columns)
        assertEquals(40, dims.rows)
    }

    @Test
    fun `default provider falls back to 80x24 when no signal is available`() {
        // System properties cleared by BeforeTest; env vars in CI may or may not
        // be set, so we only assert the fallback when both are absent.
        val columnsEnv = System.getenv("COLUMNS")?.toIntOrNull()
        val rowsEnv = System.getenv("LINES")?.toIntOrNull()
        if (columnsEnv != null || rowsEnv != null) return

        val dims = DefaultTerminalSize().current()
        assertEquals(TerminalSize.FALLBACK.columns, dims.columns)
        assertEquals(TerminalSize.FALLBACK.rows, dims.rows)
    }

    @Test
    fun `fixed factory returns the injected dimensions`() {
        val provider = TerminalSize.fixed(columns = 64, rows = 18)
        val dims = provider.current()
        assertEquals(64, dims.columns)
        assertEquals(18, dims.rows)
    }

    @Test
    fun `dimensions reject non-positive values`() {
        assertFailsWith<IllegalArgumentException> { TerminalSize.Dimensions(columns = 0, rows = 24) }
        assertFailsWith<IllegalArgumentException> { TerminalSize.Dimensions(columns = 80, rows = 0) }
        assertFailsWith<IllegalArgumentException> { TerminalSize.Dimensions(columns = -1, rows = 24) }
    }

    companion object {
        private const val PROPERTY_COLUMNS: String = "phosphor.term.columns"
        private const val PROPERTY_ROWS: String = "phosphor.term.rows"
    }
}
