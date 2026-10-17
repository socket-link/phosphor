package link.socket.phosphor.lumos.compose.docs

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps the README's Lumos quick start honest. `ReadmeLumosQuickStart.kt` holds the snippet with
 * exactly its imports: it must compile, it must draw an orb, and the README must show it as is.
 * `ReadmeLumosPointer.kt` is the section's second snippet — a consumer drawing `LumosCanvas`
 * itself, with `interactive` and `lumosPointer` — held to the same rule.
 */
class ReadmeLumosQuickStartTest {
    @Test
    fun quickStartDrawsAnOrb() {
        val scene = ImageComposeScene(width = 160, height = 160, density = Density(1f)) { ThinkingOrb() }
        try {
            var now = 0L
            repeat(10) {
                scene.render(now)
                now += 16_666_667L
            }
            val pixels = scene.render(now).toComposeImageBitmap().toPixelMap()

            assertTrue(
                (0 until 160).any { x -> (0 until 160).any { y -> pixels[x, y].alpha > 0f } },
                "the quick start drew nothing",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun pointedCanvasDrawsAnOrb() {
        val scene = ImageComposeScene(width = 160, height = 160, density = Density(1f)) { PointedCanvas() }
        try {
            var now = 0L
            repeat(10) {
                scene.render(now)
                now += 16_666_667L
            }
            val pixels = scene.render(now).toComposeImageBitmap().toPixelMap()

            assertTrue(
                (0 until 160).any { x -> (0 until 160).any { y -> pixels[x, y].alpha > 0f } },
                "the pointed canvas drew nothing",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun readmeShowsTheCompiledSnippets() {
        val readme = File("../README.md").readText()
        val section = readme.indexOf(HEADING)
        check(section >= 0) { "README has no \"$HEADING\" section" }

        val quickStart = readme.kotlinBlock(from = section)
        assertTrue(
            readme.substring(quickStart).trim() == snippet(SNIPPET),
            "README's Lumos quick start no longer matches $SNIPPET",
        )

        val pointer = readme.kotlinBlock(from = quickStart.last)
        assertTrue(
            readme.substring(pointer).trim() == snippet(POINTER_SNIPPET),
            "README's pointer snippet no longer matches $POINTER_SNIPPET",
        )
    }

    private fun snippet(path: String) = File(path).readText().substringAfter("\n").trim()

    // The first fenced Kotlin block that starts with an import at or after [from].
    private fun String.kotlinBlock(from: Int): IntRange {
        val start = indexOf("```kotlin\nimport ", from) + "```kotlin\n".length
        val end = indexOf("\n```", start)
        return start until end
    }

    private companion object {
        const val HEADING = "### A Lumos orb in Compose"
        const val SNIPPET = "src/jvmTest/kotlin/link/socket/phosphor/lumos/compose/docs/ReadmeLumosQuickStart.kt"
        const val POINTER_SNIPPET = "src/jvmTest/kotlin/link/socket/phosphor/lumos/compose/docs/ReadmeLumosPointer.kt"
    }
}
