package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.Avatar
import link.socket.phosphor.lumos.AvatarExpression
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosForm
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.counted
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.LumosInput
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * PHO-67 Task A: the [Avatar] drawn through the real pipeline, for the visual verdict. Writes to
 * `build/lumos-previews/`: NEUTRAL at 200 px on the Dots style facing the viewer, the same on
 * Squares and wearing the atmosphere's skin, NEUTRAL turned by the IDLE spin, NEUTRAL gazing right,
 * the inline avatar at 20 px and magnified, and a contact sheet of the six expressions. The one assertion is the claim the form makes: the eyes read as
 * holes, darker than the cheek beside them, with nothing in the pipeline knowing about eyes.
 */
class LumosAvatarPreviewTest {
    @Test
    fun `the neutral avatar's eyes read as holes at 200 px on the dots style`() {
        save(
            render(ORB) {
                AvatarOrb(Avatar(), ORB.dp, SurfaceStyle.Dots, spin = false)
            },
            "lumos-avatar-neutral-dots-${ORB}px.png",
        )
        // Measured with the halo off: a hole lets the halo's glow through, and the glow is as bright as the cheek.
        val image = render(ORB) { AvatarOrb(Avatar(), ORB.dp, SurfaceStyle.Dots, spin = false, showHalo = false) }
        save(image, "lumos-avatar-neutral-dots-${ORB}px-no-halo.png")
        val pixels = image.toComposeImageBitmap().toPixelMap()

        val eye = meanLuminance(pixels, EYE_X, EYE_Y, PATCH)
        val otherEye = meanLuminance(pixels, ORB - EYE_X, EYE_Y, PATCH)
        val cheek = meanLuminance(pixels, ORB / 2, EYE_Y + 2 * PATCH, PATCH)
        println("[PHO-67] eye $eye, other eye $otherEye, cheek $cheek")
        assertTrue(eye < cheek * HOLE_RATIO, "the right eye ($eye) should be darker than the cheek ($cheek)")
        assertTrue(otherEye < cheek * HOLE_RATIO, "the left eye ($otherEye) should be darker than the cheek ($cheek)")
    }

    @Test
    fun `the avatar is written on squares and in its skin and turned and gazing and inline and per expression`() {
        val variants =
            listOf<Pair<String, @Composable () -> Unit>>(
                "squares-${ORB}px" to { AvatarOrb(Avatar(), ORB.dp, SurfaceStyle.Squares, spin = false) },
                "dots-${ORB}px-skin" to { AvatarOrb(Avatar(rigid = false), ORB.dp, SurfaceStyle.Dots, spin = false) },
                "dots-${ORB}px-idle-spin" to { AvatarOrb(Avatar(), ORB.dp, SurfaceStyle.Dots, spin = true) },
                "dots-${ORB}px-gaze-right" to { AvatarOrb(Avatar(), ORB.dp, SurfaceStyle.Dots, spin = false, gazeX = 1f) },
            )
        for ((name, content) in variants) save(render(ORB, content), "lumos-avatar-neutral-$name.png")

        val inline = Avatar().counted(Forms.inlineOrbCount(INLINE_ORB_PX))
        save(
            render(INLINE_ORB_PX) {
                AvatarOrb(inline, INLINE_ORB_PX.dp, SurfaceStyle.Dots, spin = false)
            },
            "lumos-avatar-inline-${INLINE_ORB_PX}px.png",
        )
        save(
            render(MAGNIFIED) { AvatarOrb(inline, MAGNIFIED.dp, SurfaceStyle.Dots, spin = false) },
            "lumos-avatar-inline-${INLINE_ORB_PX}px-magnified-${MAGNIFIED}px.png",
        )

        val expressions = AvatarExpression.entries
        val sheet =
            render(width = expressions.size * (ORB + GAP) - GAP, height = ORB) {
                Row(horizontalArrangement = Arrangement.spacedBy(GAP.dp)) {
                    for (expression in expressions) {
                        AvatarOrb(Avatar(expression), ORB.dp, SurfaceStyle.Dots, spin = false)
                    }
                }
            }
        save(sheet, "lumos-avatar-expressions-dots-${ORB}px.png")
    }

    /** One avatar on the IDLE atmosphere, [spin] off by zeroing its rotation so the face stays toward the viewer. */
    @Composable
    private fun AvatarOrb(
        form: LumosForm,
        size: Dp,
        style: SurfaceStyle,
        spin: Boolean,
        gazeX: Float = 0f,
        showHalo: Boolean = true,
    ) {
        val source =
            remember(form, spin, gazeX) {
                val atmosphere = if (spin) AtmospherePresets.IDLE else AtmospherePresets.IDLE.copy(rotationY = 0f)
                LiveFrameSource(atmosphere).also { live ->
                    live.builder.form = form
                    if (gazeX != 0f) live.setInput(LumosInput(pointer = Vector2(gazeX, 0f)))
                }
            }
        LumosOrb(
            source = source,
            config = LumosConfig(style = style, label = "Lumos, avatar"),
            modifier = Modifier.size(size),
            backgroundColor = BACKGROUND,
            showHalo = showHalo,
        )
    }

    private fun render(
        px: Int,
        content: @Composable () -> Unit,
    ): Image = render(px, px, content)

    private fun render(
        width: Int,
        height: Int,
        content: @Composable () -> Unit,
    ): Image {
        val scene = SteppedScene(width = width, height = height, density = Density(1f), content = content)
        try {
            return scene.renderUntil(POSE_NANOS)
        } finally {
            scene.close()
        }
    }

    private fun meanLuminance(
        pixels: PixelMap,
        centreX: Int,
        centreY: Int,
        patch: Int,
    ): Float {
        var sum = 0f
        for (y in centreY - patch / 2 until centreY + patch / 2) {
            for (x in centreX - patch / 2 until centreX + patch / 2) sum += pixels[x, y].luminance()
        }
        return sum / (patch * patch)
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
        const val PATCH = 10

        // The right eye's centre on a 200 px canvas: its direction projected straight on, +y up.
        val EYE_X = ((sin(Avatar.EYE_YAW) * cos(Avatar.EYE_PITCH) + 1f) * 0.5f * ORB).toInt()
        val EYE_Y = ((1f - (sin(Avatar.EYE_PITCH) + 1f) * 0.5f) * ORB).toInt()

        // A hole lets the background through: well under half the cheek's light.
        const val HOLE_RATIO = 0.5f

        // Two seconds in, like the forms preview, so the spinning variant shows how far the face turns.
        const val POSE_NANOS = 2_000_000_000L

        val BACKGROUND = Color(0xFF111111)
    }
}
