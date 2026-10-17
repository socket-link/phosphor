package link.socket.phosphor.lumos.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import link.socket.phosphor.lumos.compose.LumosOrb
import link.socket.phosphor.lumos.probe.FrameProbe

/**
 * The orb's place on a [LumosSurface].
 *
 * While [LumosSurfaceState.phase] is [SurfacePhase.Orb] this composes a `LumosOrb` on the state's
 * source and config, which steps and draws it as any orb. Its bounds are published as the
 * state's viewport, so the cells leave from where the orb is laid out. The frame an assembly
 * starts flying, the orb leaves the composition — its loop ends, and the surface's overlay takes
 * the source over — and the slot stays, empty and the same size, so nothing around it reflows
 * and the orb has somewhere to come back to on [LumosSurfaceState.release].
 *
 * Give the slot a size, as a `LumosOrb` needs one; the orb fills it. The orb's halo stops with
 * the orb: the overlay draws cells only.
 *
 * @param state The surface's state, found through [LumosSurface].
 * @param modifier Sizing and placement; must give the slot a size.
 * @param backgroundColor Optional opaque background behind the orb while it is here.
 * @param showHalo Paint the ambient breath-ring gradient behind the orb while it is here.
 * @param showGlyphOverlay Overlay the active glyph when the frame carries one.
 * @param probe Per-phase timing sink, forwarded to the orb.
 */
@Composable
fun LumosSurfaceOrb(
    state: LumosSurfaceState,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    showHalo: Boolean = true,
    showGlyphOverlay: Boolean = true,
    probe: FrameProbe = FrameProbe.Disabled,
) {
    Box(modifier = modifier.onGloballyPositioned { state.slotPositioned(it.boundsInRoot()) }) {
        if (state.phase is SurfacePhase.Orb) {
            LumosOrb(
                source = state.source,
                config = state.config,
                modifier = Modifier.matchParentSize(),
                backgroundColor = backgroundColor,
                showHalo = showHalo,
                showGlyphOverlay = showGlyphOverlay,
                probe = probe,
            )
        }
    }
}
