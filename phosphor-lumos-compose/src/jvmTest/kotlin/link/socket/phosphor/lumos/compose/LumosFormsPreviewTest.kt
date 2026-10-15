package link.socket.phosphor.lumos.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosForm
import link.socket.phosphor.lumos.counted
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * PHO-56: every catalogue form draws through [LumosFormsPreview], and the pictures the visual
 * verdict is given on are written to `build/lumos-previews/`: the whole preview strip, one
 * 200 px frame per form, the inline orb at its real 20 px, and the same 80 cells drawn on a
 * 160 px canvas so the geometry behind the 20 px raster can be seen.
 */
class LumosFormsPreviewTest {
    @Test
    fun `every form in the preview draws something`() {
        val forms = Forms.All.size
        val width = forms * (ORB + GAP) + INLINE_ORB_PX
        val scene = SteppedScene(width = width, height = ORB, density = Density(1f)) { LumosFormsPreview() }
        try {
            val image = scene.renderUntil(POSE_NANOS)
            save(image, "lumos-forms-preview.png")
            val pixels = image.toComposeImageBitmap().toPixelMap()

            Forms.All.forEachIndexed { index, (name, _) ->
                val left = index * (ORB + GAP)
                assertTrue(region(pixels, left, ORB, ORB).any { it != BACKGROUND }, "the $name orb drew nothing")
            }
            val inlineLeft = forms * (ORB + GAP)
            assertTrue(
                region(pixels, inlineLeft, INLINE_ORB_PX, INLINE_ORB_PX).any { it != BACKGROUND },
                "the inline orb drew nothing",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `each form is written at 200 px, and the inline orb at 20 px and magnified`() {
        for ((name, form) in Forms.All) {
            save(renderForm(form, ORB, LumosConfig(label = name)), "lumos-form-$name-${ORB}px.png")
        }
        val inline = Forms.Fibonacci.counted(Forms.inlineOrbCount(INLINE_ORB_PX))
        save(renderForm(inline, INLINE_ORB_PX), "lumos-form-inline-${INLINE_ORB_PX}px.png")
        save(renderForm(inline, MAGNIFIED), "lumos-form-inline-${INLINE_ORB_PX}px-magnified-${MAGNIFIED}px.png")
    }

    private fun renderForm(
        form: LumosForm,
        px: Int,
        config: LumosConfig = LumosConfig(),
    ): Image {
        val scene = SteppedScene(width = px, height = px, density = Density(1f)) { FormOrb(form, px.dp, config) }
        try {
            return scene.renderUntil(POSE_NANOS)
        } finally {
            scene.close()
        }
    }

    private fun region(
        pixels: PixelMap,
        left: Int,
        width: Int,
        height: Int,
    ): List<Color> =
        buildList {
            for (y in 0 until height) {
                for (x in left until left + width) add(pixels[x, y])
            }
        }

    private fun save(
        image: Image,
        name: String,
    ) {
        val bytes = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
        File("build/lumos-previews").apply { mkdirs() }.resolve(name).writeBytes(bytes)
    }

    private companion object {
        const val ORB = 200
        const val GAP = 16
        const val MAGNIFIED = 160

        // Two seconds in: the IDLE spin has turned the cube off its axis, so faces and corners both show.
        const val POSE_NANOS = 2_000_000_000L

        val BACKGROUND = Color(0xFF111111)
    }
}
