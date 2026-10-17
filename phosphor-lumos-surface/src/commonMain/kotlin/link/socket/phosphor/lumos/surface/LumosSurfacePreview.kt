package link.socket.phosphor.lumos.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.palette.AtmospherePresets

/**
 * Preview composable for [LumosSurface]: the Turn-on moment at 400 × 260. An IDLE orb in its slot
 * at the top, a "Turn on" button at the bottom right, and after [PREVIEW_ASSEMBLE_AT_SECONDS] the
 * orb's cells burst, flow into the button and fade as it rises, tinted toward its colour.
 *
 * A preview for a host project's preview pane or sample app, not a test; `LumosSurfacePreviewTest`
 * records it frame by frame. The overload taking a [LumosSurfaceState] lets a host watch the
 * phase.
 */
@Composable
fun LumosSurfacePreview() {
    val state = remember { previewSurfaceState() }
    LumosSurfacePreview(state)
}

/**
 * [LumosSurfacePreview] on [state], assembling [assembleAfterSeconds] in; [onTurnOn] is the
 * button's click.
 */
@Composable
fun LumosSurfacePreview(
    state: LumosSurfaceState,
    assembleAfterSeconds: Float = PREVIEW_ASSEMBLE_AT_SECONDS,
    onTurnOn: () -> Unit = {},
) {
    LaunchedEffect(state) {
        delay((assembleAfterSeconds * 1000f).toLong().milliseconds)
        state.assembleInto(PREVIEW_TARGET, tint = PREVIEW_ACCENT)
    }
    LumosSurface(
        state = state,
        modifier = Modifier.size(PREVIEW_WIDTH, PREVIEW_HEIGHT).background(PREVIEW_BACKGROUND),
    ) {
        LumosSurfaceOrb(
            state = state,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = PREVIEW_ORB_TOP).size(PREVIEW_ORB_SIZE),
            showHalo = false,
        )
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(PREVIEW_PADDING)
                    .lumosTarget(PREVIEW_TARGET, cornerRadiusPx = PREVIEW_CORNER_PX)
                    .size(width = PREVIEW_BUTTON_WIDTH, height = PREVIEW_BUTTON_HEIGHT)
                    .background(PREVIEW_ACCENT, RoundedCornerShape(PREVIEW_CORNER_PX.dp))
                    .clickable(onClick = onTurnOn)
                    .semantics { role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                PREVIEW_LABEL,
                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
}

/** The preview's state: an IDLE orb at resolution 6, drawn as Dots, in a surface of its own. */
fun previewSurfaceState(): LumosSurfaceState =
    LumosSurfaceState(
        source = LiveFrameSource(AtmospherePresets.IDLE.copy(resolution = PREVIEW_RESOLUTION)),
        config = LumosConfig(style = SurfaceStyle.Dots),
    )

/** The preview's target id. */
const val PREVIEW_TARGET: String = "turn-on"

/** Seconds of orb before the preview assembles. */
const val PREVIEW_ASSEMBLE_AT_SECONDS: Float = 0.8f

internal const val PREVIEW_LABEL: String = "Turn on"
internal const val PREVIEW_RESOLUTION: Int = 6
internal const val PREVIEW_CORNER_PX: Float = 8f
internal val PREVIEW_WIDTH = 400.dp
internal val PREVIEW_HEIGHT = 260.dp
internal val PREVIEW_ORB_TOP = 36.dp
internal val PREVIEW_ORB_SIZE = 128.dp
internal val PREVIEW_PADDING = 24.dp
internal val PREVIEW_BUTTON_WIDTH = 128.dp
internal val PREVIEW_BUTTON_HEIGHT = 40.dp
internal val PREVIEW_BACKGROUND = Color(0xFF111318)
internal val PREVIEW_ACCENT = Color(0xFF4F6BFF)
