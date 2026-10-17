package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.LumosInput

/**
 * Preview composable for the pointer (PHO-83): three `interactive` THINKING orbs side by side —
 * nothing pointing, a pointer at the centre, and a pointer upper right — each settled under its
 * pointer, so the strip shows the cells parted around it. The pointer is handed to each scene
 * directly, as [Modifier.lumosPointer] would hand it from a real pointer.
 *
 * Like [LumosOrbPreview], a preview for a host project's preview pane or sample app, not a test.
 */
@Composable
fun LumosInputPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(INPUT_PREVIEW_GAP)) {
        for (pointer in INPUT_PREVIEW_POINTERS) PreviewInputOrb(pointer)
    }
}

@Composable
private fun PreviewInputOrb(pointer: Vector2?) {
    val source =
        remember(pointer) {
            LiveFrameSource(AtmospherePresets.THINKING, INPUT_PREVIEW_RENDER_CONFIG).also { live ->
                live.next(INPUT_PREVIEW_WARM_UP_SECONDS)
                live.setInput(LumosInput(pointer = pointer))
                // Long enough for the cells to part and come to rest around the pointer.
                repeat(INPUT_PREVIEW_SETTLE_FRAMES) { live.next(INPUT_PREVIEW_FRAME_SECONDS) }
            }
        }
    LumosOrb(
        source = source,
        config = LumosConfig(label = if (pointer == null) "Lumos, alone" else "Lumos, pointed at"),
        modifier = Modifier.size(INPUT_PREVIEW_SIZE),
        backgroundColor = INPUT_PREVIEW_BACKGROUND,
    )
}

/** Where each orb of the strip is pointed at, in the orb's view space; null for nothing pointing. */
val INPUT_PREVIEW_POINTERS: List<Vector2?> = listOf(null, Vector2(0f, 0f), Vector2(0.5f, 0.5f))

/** Each orb's size. */
val INPUT_PREVIEW_SIZE: Dp = 200.dp

/** The gap between orbs. */
val INPUT_PREVIEW_GAP: Dp = 16.dp

/** The strip's background. */
val INPUT_PREVIEW_BACKGROUND: Color = Color(0xFF111111)

private val INPUT_PREVIEW_RENDER_CONFIG = LumosRenderConfig(interactive = true)
private const val INPUT_PREVIEW_WARM_UP_SECONDS = 1.5f
private const val INPUT_PREVIEW_FRAME_SECONDS = 1f / 30f
private const val INPUT_PREVIEW_SETTLE_FRAMES = 45
