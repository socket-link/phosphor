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
    fun readmeShowsTheCompiledSnippet() {
        val snippet = File(SNIPPET).readText().substringAfter("\n").trim()
        val readme = File("../README.md").readText()
        val section = readme.indexOf(HEADING)
        check(section >= 0) { "README has no \"$HEADING\" section" }
        val start = readme.indexOf("```kotlin\nimport ", section) + "```kotlin\n".length
        val end = readme.indexOf("\n```", start)

        assertTrue(
            readme.substring(start, end).trim() == snippet,
            "README's Lumos quick start no longer matches $SNIPPET",
        )
    }

    private companion object {
        const val HEADING = "### A Lumos orb in Compose"
        const val SNIPPET = "src/jvmTest/kotlin/link/socket/phosphor/lumos/compose/docs/ReadmeLumosQuickStart.kt"
    }
}
