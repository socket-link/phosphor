package link.socket.phosphor.lumos.surface

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import link.socket.phosphor.lumos.compose.LocalLookClock
import link.socket.phosphor.lumos.compose.drawVoxels
import link.socket.phosphor.lumos.compose.painter

/**
 * The [LumosSurfaceState] of the nearest [LumosSurface]. [lumosTarget] and
 * [LumosSurfaceOrb] read it; reading it with no surface above throws, saying so.
 */
val LocalLumosSurfaceState: ProvidableCompositionLocal<LumosSurfaceState> =
    staticCompositionLocalOf { error(NO_SURFACE_MESSAGE) }

internal const val NO_SURFACE_MESSAGE: String =
    "lumosTarget needs a LumosSurface above it: targets and the overlay must share one surface, " +
        "so that their bounds are measured in the same pixels."

/**
 * The root under which targets, the orb's slot and the overlay share one coordinate space.
 *
 * A `Box`: [content] is laid out as in any box, and the overlay — a surface-sized canvas that
 * draws the cells while they fly — is its last child, above every target and below any `Popup`
 * or `Dialog`, which are windows of their own. The overlay carries no semantics and takes no
 * pointer input, so a control under it keeps focus, click and its role; it is composed only
 * while [LumosSurfaceState.phase] is [SurfacePhase.Assembling], and so is the frame loop that
 * steps the source. Once the control is solid nothing in this tree ticks.
 *
 * Targets are measured relative to this box, so the surface need not be the composition's root;
 * but a target outside it has no surface to fly on, and [lumosTarget] throws.
 *
 * Mid-flight, a host whose lifecycle drops below `STARTED` and a resize of the surface both
 * [LumosSurfaceState.cancel]: the control snaps on. A host without a lifecycle, such as
 * `ImageComposeScene`, is not gated.
 *
 * Live only: a pixel form depends on layout, so a surface morph is never recorded to a `.vxt`.
 *
 * @param state The assembly's state; remember it across recompositions.
 * @param modifier Sizing and placement of the surface.
 * @param content Targets, the orb's slot and whatever else shares the space.
 */
@Composable
fun LumosSurface(
    state: LumosSurfaceState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val clock = LocalLookClock.current
    remember(state, clock) { state.attach(clock) }

    val started = hostLifecycle()?.currentStateAsState()?.value?.isAtLeast(Lifecycle.State.STARTED) ?: true
    val assembling = state.phase is SurfacePhase.Assembling

    LaunchedEffect(state, assembling, started) {
        if (!assembling) return@LaunchedEffect
        if (!started) {
            state.cancel()
            return@LaunchedEffect
        }
        while (true) withFrameNanos(state.onFrame)
    }

    CompositionLocalProvider(LocalLumosSurfaceState provides state) {
        Box(
            modifier =
                modifier
                    .onGloballyPositioned { state.surfacePositioned(it.positionInRoot()) }
                    .onSizeChanged { state.surfaceSized(it.width, it.height) },
        ) {
            content()
            if (assembling) Overlay(state)
        }
    }
}

// The cells in flight, drawn with the orb's own style and material and the hand-off on top.
// Reads the frame and the arrival at draw time, so a new frame redraws this canvas and nothing else.
@Composable
private fun BoxScope.Overlay(state: LumosSurfaceState) {
    val style = state.config.style
    val material = state.config.material
    val painter = remember(style, material) { HandOffPainter(material.painter(style.painter())) }
    Canvas(modifier = Modifier.matchParentSize().clearAndSetSemantics { }) {
        val frame = state.canvasFrame ?: return@Canvas
        painter.arrival = state.arrival
        painter.tint = state.tint
        drawVoxels(frame, painter)
    }
}

/**
 * The lifecycle of the host this composition runs in, or null when the host provides none.
 *
 * Off Android, reading `LocalLifecycleOwner.current` where no owner was provided throws, and
 * not every host provides one — a window or a view controller does, `ImageComposeScene` does not.
 * The composition-local map reads the same value without a composable call, so the read can be
 * guarded. The same helper `LumosOrb` keeps, private to it.
 */
@Composable
private fun hostLifecycle(): Lifecycle? {
    val locals = currentComposer.currentCompositionLocalMap
    return remember(locals) {
        try {
            locals[LocalLifecycleOwner].lifecycle
        } catch (_: IllegalStateException) {
            null
        }
    }
}
