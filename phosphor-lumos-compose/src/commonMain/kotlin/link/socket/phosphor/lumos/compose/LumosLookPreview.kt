package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.palette.AtmospherePresets

/**
 * Preview composable for the first four looks (PHO-58): base with its rim fade,
 * working, searching and reasoning, each as a THINKING orb at 200 px on the
 * first row and at 20 px — the inline size — on the second. Every orb steps a
 * live scene of its own and wears its look through [link.socket.phosphor.lumos.VoxelFrameBuilder.look].
 *
 * Like [LumosOrbPreview], a preview for a host project's preview pane or sample
 * app, not a test.
 */
@Composable
fun LumosLookPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(LOOK_PREVIEW_GAP)) {
        Row(horizontalArrangement = Arrangement.spacedBy(LOOK_PREVIEW_GAP)) {
            for (look in LOOK_PREVIEW_LOOKS) PreviewLookOrb(look, LOOK_PREVIEW_LARGE)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(LOOK_PREVIEW_GAP)) {
            for (look in LOOK_PREVIEW_LOOKS) PreviewLookOrb(look, LOOK_PREVIEW_SMALL)
        }
    }
}

@Composable
private fun PreviewLookOrb(
    look: LumosLook,
    size: Dp,
) {
    val source =
        remember(look) {
            LiveFrameSource(AtmospherePresets.THINKING, renderConfig = LumosRenderConfig(rimFade = true)).also { live ->
                live.builder.look = Look(look)
                live.next(LOOK_PREVIEW_WARM_UP_SECONDS)
            }
        }
    LumosOrb(
        source = source,
        config = LumosConfig(label = "Lumos, ${look.name.lowercase()}"),
        modifier = Modifier.size(size),
        backgroundColor = LOOK_PREVIEW_BACKGROUND,
    )
}

/** The looks the preview shows, in row order. */
val LOOK_PREVIEW_LOOKS: List<LumosLook> =
    listOf(LumosLook.BASE, LumosLook.WORKING, LumosLook.SEARCHING, LumosLook.REASONING)

/** The hero size on the preview's first row. */
val LOOK_PREVIEW_LARGE: Dp = 200.dp

/** The inline size on the preview's second row. */
val LOOK_PREVIEW_SMALL: Dp = 20.dp

/** The gap between orbs. */
val LOOK_PREVIEW_GAP: Dp = 16.dp

private val LOOK_PREVIEW_BACKGROUND = Color(0xFF111111)
private const val LOOK_PREVIEW_WARM_UP_SECONDS = 1.5f
