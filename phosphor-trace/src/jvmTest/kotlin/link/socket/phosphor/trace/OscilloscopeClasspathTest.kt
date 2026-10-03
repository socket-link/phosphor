package link.socket.phosphor.trace

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * PHO-36 definition of done: capture runs with no Compose dependency on the
 * classpath. `:phosphor-trace` depends on `:phosphor-lumos` and `:phosphor-core`
 * only; this test fails if a Compose artifact ever leaks into that graph.
 */
class OscilloscopeClasspathTest {
    @Test
    fun `capture succeeds with no Compose classes loadable`() {
        listOf(
            "androidx.compose.runtime.Composable",
            "androidx.compose.ui.graphics.Color",
            "androidx.compose.ui.graphics.drawscope.DrawScope",
        ).forEach { className ->
            assertFailsWith<ClassNotFoundException>("$className must not be on the :phosphor-trace classpath") {
                Class.forName(className)
            }
        }

        val result = Oscilloscope.capture(CaptureConfig(durationMs = 100L, fps = 10))
        assertTrue(result.isSuccess)
    }
}
