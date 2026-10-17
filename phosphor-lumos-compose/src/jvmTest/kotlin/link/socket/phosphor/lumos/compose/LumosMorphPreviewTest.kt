package link.socket.phosphor.lumos.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import link.socket.phosphor.field.Choreography
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.palette.AtmospherePresets
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * PHO-57 Task E: the morph draws through [LumosMorphPreview], and the pictures the visual verdict
 * is given on are written to `build/lumos-previews/`: the strip, lattice → Fibonacci → cube at six
 * offsets, plus one orb caught mid-morph under each choreography.
 */
class LumosMorphPreviewTest {
    @Test
    fun `the morph strip draws every orb`() {
        val width = MORPH_STRIP_ORBS * (ORB + GAP) - GAP
        val scene = SteppedScene(width = width, height = ORB, density = Density(1f)) { LumosMorphPreview() }
        try {
            val image = scene.renderUntil(POSE_NANOS)
            save(image, "lumos-morph-strip.png")
            val pixels = image.toComposeImageBitmap().toPixelMap()
            for (index in 0 until MORPH_STRIP_ORBS) {
                val left = index * (ORB + GAP)
                var lit = 0
                for (y in 0 until ORB) {
                    for (x in left until left + ORB) {
                        if (pixels[x, y] != BACKGROUND) lit++
                    }
                }
                assertTrue(lit > 0, "strip orb $index drew nothing")
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `one orb is written mid-morph under each choreography`() {
        for (choreography in Choreography.entries) {
            val scene =
                SteppedScene(width = ORB, height = ORB, density = Density(1f)) {
                    MorphOrb(startSeconds = MID_MORPH_SECONDS, size = ORB.dp, choreography = choreography)
                }
            try {
                save(scene.renderUntil(POSE_NANOS), "lumos-morph-${choreography.name.lowercase()}-mid.png")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `the morphing source cycles through its forms`() {
        val source = MorphingFrameSource(LiveFrameSource(AtmospherePresets.IDLE))
        source.advance(MorphingFrameSource.DEFAULT_HOLD_SECONDS + 0.1f)
        assertTrue(source.inner.builder.isMorphing)
        assertTrue(source.inner.builder.form === Forms.Fibonacci)
    }

    private fun save(
        image: Image,
        name: String,
    ) {
        val bytes = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
        File("build/lumos-previews").apply { mkdirs() }.resolve(name).writeBytes(bytes)
    }

    private companion object {
        const val ORB = 160
        const val GAP = 12

        // Each strip orb starts 0.6 s further along; the leftmost has rested 1.2 s before its first morph,
        // so a second in, the strip spans rest, early morph, mid morph and settle.
        const val POSE_NANOS = 1_000_000_000L

        // The hold plus a third of the morph: the arc envelope near its peak.
        const val MID_MORPH_SECONDS = MorphingFrameSource.DEFAULT_HOLD_SECONDS + 0.8f

        val BACKGROUND = Color(0xFF111111)
    }
}
