package link.socket.phosphor.lumos.compose.spike

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.compose.SteppedScene
import org.jetbrains.skia.EncodedImageFormat

/**
 * PHO-68 throwaway. Not a regression test: it records the Turn-on moment to
 * `build/lumos-spike/frame-NNN.png` for the spike's verdict, and checks the two claims the
 * prototype makes about hand-off — the real button keeps its click and semantics under the overlay,
 * and the overlay leaves nothing in the semantics tree.
 */
@OptIn(ExperimentalTestApi::class)
class TurnOnSpikeTest {
    @Test
    fun `records the orb dissolving into the Turn on button`() {
        val assembly = TurnOnAssembly(WIDTH, HEIGHT, VIEWPORT)
        val dir =
            File("build/lumos-spike").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() }
            }
        val scene = SteppedScene(WIDTH, HEIGHT) { TurnOnSpike(assembly) }
        try {
            var index = 0
            var image = scene.render()
            while (scene.nowNanos < RECORD_NANOS) {
                val png = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
                dir.resolve("frame-%03d.png".format(index++)).writeBytes(png)
                image = scene.renderNextFrame()
            }
            println("[PHO-68] recorded $index frames; done=${assembly.done} arrival=${assembly.arrival}")
            assertTrue(assembly.done, "the assembly should settle within ${RECORD_NANOS / 1e9} s")
            assertEquals(1f, assembly.buttonAlpha)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `reduced motion shows the control at once`() {
        val assembly = TurnOnAssembly(WIDTH, HEIGHT, VIEWPORT, assembleAt = 0f, reducedMotion = true)
        val scene = SteppedScene(WIDTH, HEIGHT) { TurnOnSpike(assembly) }
        try {
            scene.renderNextFrame()
            scene.renderNextFrame()
            assertTrue(assembly.done)
            assertEquals(1f, assembly.buttonAlpha)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the real button keeps click and semantics under the overlay`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val assembly = TurnOnAssembly(WIDTH, HEIGHT, VIEWPORT, assembleAt = 100f)
            var clicks = 0
            setContent { TurnOnSpike(assembly) { clicks++ } }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeByFrame()

            assertTrue(!assembly.done, "the overlay must still be composed for this to mean anything")
            onNodeWithText("Turn on").performClick()
            assertEquals(1, clicks)
            onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertCountEquals(1)
        }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 260
        val VIEWPORT = OrbViewport(centerXPx = 200f, centerYPx = 100f, radiusPx = 64f)
        const val RECORD_NANOS = 3_600_000_000L
    }
}
