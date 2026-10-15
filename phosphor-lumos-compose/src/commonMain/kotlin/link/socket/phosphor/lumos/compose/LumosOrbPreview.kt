package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosGlyph
import link.socket.phosphor.palette.AtmospherePresets

/**
 * Preview composable for [LumosOrb]: three THINKING orbs side by side — one moving, one paused,
 * and one under reduced motion with a CHECK glyph held at full strength. Over time only the first
 * changes.
 *
 * Like [LumosCanvasPreview], a preview for a host project's preview pane or sample app, not a
 * test. Each orb steps a live scene of its own.
 */
@Composable
fun LumosOrbPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(PREVIEW_GAP)) {
        PreviewOrb(LumosConfig(label = "Lumos, thinking"))
        PreviewOrb(LumosConfig(paused = true, label = "Lumos, paused"))
        PreviewOrb(LumosConfig(reducedMotion = true, label = "Lumos, holding still"), glyph = LumosGlyph.CHECK)
    }
}

@Composable
private fun PreviewOrb(
    config: LumosConfig,
    glyph: LumosGlyph? = null,
) {
    val source =
        remember {
            LiveFrameSource(AtmospherePresets.THINKING).also { live ->
                // A second and a half in, so the paused orb holds a pose rather than phase zero.
                live.next(PREVIEW_WARM_UP_SECONDS)
                glyph?.let { live.builder.queueGlyph(it, durationSeconds = PREVIEW_GLYPH_SECONDS) }
            }
        }
    LumosOrb(
        source = source,
        config = config,
        modifier = Modifier.size(PREVIEW_SIZE),
        backgroundColor = PREVIEW_BACKGROUND,
    )
}

private val PREVIEW_SIZE = 200.dp
private val PREVIEW_GAP = 16.dp
private val PREVIEW_BACKGROUND = Color(0xFF111111)
private const val PREVIEW_WARM_UP_SECONDS = 1.5f
private const val PREVIEW_GLYPH_SECONDS = 60f
