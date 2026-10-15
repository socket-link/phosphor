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
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosForm
import link.socket.phosphor.lumos.counted
import link.socket.phosphor.palette.AtmospherePresets

/**
 * Preview composable for the form catalogue: every form in [Forms.All] as a hero orb on the IDLE
 * atmosphere, at [FORM_PREVIEW_SIZE], followed by the inline orb — the Fibonacci form at
 * [INLINE_ORB_SIZE] with [Forms.inlineOrbCount] cells, as Socket runs it.
 *
 * Like [LumosOrbPreview], for a host project's preview pane or sample app, not a test. Each orb
 * steps a live scene of its own, with the hero orbs sharing one clock, so the forms are seen
 * at the same moment of the same spin.
 */
@Composable
fun LumosFormsPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(FORM_PREVIEW_GAP)) {
        for ((name, form) in Forms.All) {
            FormOrb(form, FORM_PREVIEW_SIZE, LumosConfig(label = "Lumos, $name"))
        }
        FormOrb(
            form = Forms.Fibonacci.counted(Forms.inlineOrbCount(INLINE_ORB_PX)),
            size = INLINE_ORB_SIZE,
            config = LumosConfig(label = "Lumos, inline"),
        )
    }
}

/** One orb of the forms preview: [form] on the IDLE atmosphere, [size] on a side. */
@Composable
internal fun FormOrb(
    form: LumosForm,
    size: Dp,
    config: LumosConfig = LumosConfig(),
) {
    val source =
        remember(form) {
            LiveFrameSource(AtmospherePresets.IDLE).also { live -> live.builder.form = form }
        }
    LumosOrb(
        source = source,
        config = config,
        modifier = Modifier.size(size),
        backgroundColor = FORM_PREVIEW_BACKGROUND,
    )
}

/** The hero orb's side in the preview. */
val FORM_PREVIEW_SIZE: Dp = 200.dp

/** The inline orb's side: the 20 px Socket draws it at. */
const val INLINE_ORB_PX: Int = 20

/** [INLINE_ORB_PX] as a dp, for the preview's density of 1. */
val INLINE_ORB_SIZE: Dp = INLINE_ORB_PX.dp

internal val FORM_PREVIEW_GAP: Dp = 16.dp
internal val FORM_PREVIEW_BACKGROUND: Color = Color(0xFF111111)
