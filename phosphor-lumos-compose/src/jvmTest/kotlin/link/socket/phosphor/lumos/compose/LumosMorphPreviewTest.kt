package link.socket.phosphor.lumos.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * offsets, plus one orb caught mid-morph under each choreography. PHO-74 Task C: the pair below the
 * strip, the plain morph beside the burst-then-morph, written frame by frame to
 * `build/lumos-previews/burst-pair/` for the recording.
 */
class LumosMorphPreviewTest {
    @Test
    fun `the morph strip draws every orb`() {
        val width = MORPH_STRIP_ORBS * (ORB + GAP) - GAP
        val scene = SteppedScene(width = width, height = 2 * ORB + GAP, density = Density(1f)) { LumosMorphPreview() }
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
    fun `the burst pair draws both orbs through the morph and is written frame by frame`() {
        val scene =
            SteppedScene(width = PAIR_WIDTH, height = ORB, density = Density(1f)) {
                BurstMorphPair(startSeconds = PAIR_START_SECONDS, size = ORB.dp)
            }
        val frames =
            File("build/lumos-previews/burst-pair").apply {
                deleteRecursively()
                mkdirs()
            }
        try {
            var frame = 0
            var image = scene.render()
            while (scene.nowNanos <= PAIR_RECORD_NANOS) {
                save(image, "burst-pair/frame-${frame.toString().padStart(3, '0')}.png")
                val (left, right) = halves(image)
                assertTrue(left.any { it != BACKGROUND }, "the plain orb drew nothing on frame $frame")
                assertTrue(right.any { it != BACKGROUND }, "the bursting orb drew nothing on frame $frame")
                frame++
                image = scene.renderNextFrame()
            }
            assertEquals(frame, frames.list().orEmpty().size)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the bursting source fires before each morph and the morph carries it`() {
        val source = MorphingFrameSource(LiveFrameSource(AtmospherePresets.IDLE), burstStrength = MORPH_BURST_STRENGTH)
        source.advance(MorphingFrameSource.DEFAULT_HOLD_SECONDS + 0.05f)
        assertTrue(source.inner.builder.isMorphing)
        assertTrue(source.inner.builder.form === Forms.Fibonacci)
    }

    @Test
    fun `the morphing source cycles through its forms`() {
        val source = MorphingFrameSource(LiveFrameSource(AtmospherePresets.IDLE))
        source.advance(MorphingFrameSource.DEFAULT_HOLD_SECONDS + 0.1f)
        assertTrue(source.inner.builder.isMorphing)
        assertTrue(source.inner.builder.form === Forms.Fibonacci)
    }

    /** The pair's two orbs as pixel lists, left and right, for comparing whole. */
    private fun halves(image: Image): Pair<List<Color>, List<Color>> {
        val pixels = image.toComposeImageBitmap().toPixelMap()

        fun orb(left: Int): List<Color> =
            buildList {
                for (y in 0 until ORB) for (x in left until left + ORB) add(pixels[x, y])
            }
        return orb(0) to orb(ORB + GAP)
    }

    private fun save(
        image: Image,
        name: String,
    ) {
        val bytes = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
        File("build/lumos-previews").resolve(name).apply { parentFile.mkdirs() }.writeBytes(bytes)
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

        const val PAIR_WIDTH = 2 * ORB + GAP

        // The pair starts 0.3 s before its first morph, and is recorded through the morph and its settle.
        const val PAIR_START_SECONDS = MorphingFrameSource.DEFAULT_HOLD_SECONDS - 0.3f

        const val PAIR_RECORD_NANOS = 3_200_000_000L
    }
}
