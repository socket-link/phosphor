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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.counted
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
 * The Turn-on moment at Socket's hero size: `LumosTabOrb`'s 160 px orb on the presets'
 * resolution 10, 4,801 cells, into the same 128 × 40 button. A cell every 1.1 px on the button.
 */
@Composable
fun LumosSurfaceHeroPreview() {
    val state = remember { heroSurfaceState() }
    LumosSurfacePreview(state, orbSize = HERO_ORB_SIZE, orbTop = HERO_ORB_TOP)
}

/**
 * The Turn-on moment at Socket's inline size: the 20 px orb on `Forms.Fibonacci` at 80 cells,
 * into the same 128 × 40 button, with half its cells on the outline so the edge still reads.
 */
@Composable
fun LumosSurfaceInlinePreview() {
    val state = remember { inlineSurfaceState() }
    LumosSurfacePreview(state, orbSize = INLINE_ORB_SIZE, orbTop = INLINE_ORB_TOP)
}

/**
 * [LumosSurfacePreview] on [state], assembling [assembleAfterSeconds] in; [onTurnOn] is the
 * button's click.
 *
 * @param orbSize The orb's slot, top centre; 128 dp by default.
 * @param orbTop The slot's distance from the top.
 * @param surplus What the assembly does with the cells the button does not need.
 */
@Composable
fun LumosSurfacePreview(
    state: LumosSurfaceState,
    assembleAfterSeconds: Float = PREVIEW_ASSEMBLE_AT_SECONDS,
    onTurnOn: () -> Unit = {},
    orbSize: Dp = PREVIEW_ORB_SIZE,
    orbTop: Dp = PREVIEW_ORB_TOP,
    surplus: SurfaceSurplus = SurfaceSurplus.Keep,
) {
    LaunchedEffect(state) {
        delay((assembleAfterSeconds * 1000f).toLong().milliseconds)
        state.assembleInto(PREVIEW_TARGET, tint = PREVIEW_ACCENT, surplus = surplus)
    }
    LumosSurface(
        state = state,
        modifier = Modifier.size(PREVIEW_WIDTH, PREVIEW_HEIGHT).background(PREVIEW_BACKGROUND),
    ) {
        LumosSurfaceOrb(
            state = state,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = orbTop).size(orbSize),
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

/** [LumosSurfaceHeroPreview]'s state: an IDLE orb at the presets' resolution 10, drawn in [style]. */
fun heroSurfaceState(style: SurfaceStyle = SurfaceStyle.Dots): LumosSurfaceState =
    LumosSurfaceState(
        source = LiveFrameSource(AtmospherePresets.IDLE.copy(resolution = HERO_RESOLUTION)),
        config = LumosConfig(style = style),
    )

/** [LumosSurfaceInlinePreview]'s state: an IDLE orb on the inline orb's 80-cell Fibonacci sphere, drawn in [style]. */
fun inlineSurfaceState(style: SurfaceStyle = SurfaceStyle.Dots): LumosSurfaceState =
    LumosSurfaceState(
        source =
            LiveFrameSource(AtmospherePresets.IDLE).also { live ->
                live.builder.form = Forms.Fibonacci.counted(Forms.inlineOrbCount(INLINE_ORB_PX))
            },
        config = LumosConfig(style = style),
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
internal const val HERO_RESOLUTION: Int = 10
internal val HERO_ORB_TOP = 12.dp
internal val HERO_ORB_SIZE = 160.dp
internal const val INLINE_ORB_PX: Int = 20
internal val INLINE_ORB_TOP = 90.dp
internal val INLINE_ORB_SIZE = INLINE_ORB_PX.dp
internal val PREVIEW_PADDING = 24.dp
internal val PREVIEW_BUTTON_WIDTH = 128.dp
internal val PREVIEW_BUTTON_HEIGHT = 40.dp
internal val PREVIEW_BACKGROUND = Color(0xFF111318)
internal val PREVIEW_ACCENT = Color(0xFF4F6BFF)
