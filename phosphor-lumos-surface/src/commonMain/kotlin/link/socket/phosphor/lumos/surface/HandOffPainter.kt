package link.socket.phosphor.lumos.surface

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import link.socket.phosphor.lumos.compose.SurfacePainter
import link.socket.phosphor.lumos.frame.LumosCanvasFrame

/**
 * The hand-off over the orb's own painter: as the cells arrive, each grows
 * `× (1 + HAND_OFF_GROW · arrival)`, fades `× (1 − arrival)` and lerps toward [tint], on the same
 * curve the control's alpha rises on, then goes to [inner] to be drawn in the orb's style and
 * material. At [arrival] 0 it is [inner]. No allocation per cell: a `Color` is a value.
 */
internal class HandOffPainter(
    private val inner: SurfacePainter,
) : SurfacePainter {
    /** How far the cells have arrived, `0..1`; set once per frame before drawing. */
    var arrival: Float = 0f

    /** The colour to lerp toward on arrival, or null to keep each cell's own. */
    var tint: Color? = null

    override fun begin(
        scope: DrawScope,
        frame: LumosCanvasFrame,
    ) = inner.begin(scope, frame)

    override fun cell(
        index: Int,
        screenX: Float,
        screenY: Float,
        radiusPx: Float,
        rgba: Color,
        z: Float,
        dx: Float,
        dy: Float,
    ) {
        val arrival = arrival
        if (arrival <= 0f) {
            inner.cell(index, screenX, screenY, radiusPx, rgba, z, dx, dy)
            return
        }
        val tint = tint
        val toward = if (tint == null) rgba else lerp(rgba, tint.copy(alpha = rgba.alpha), arrival)
        val faded = toward.copy(alpha = toward.alpha * (1f - arrival))
        val grown = radiusPx * (1f + LumosSurfaceState.HAND_OFF_GROW * arrival)
        inner.cell(index, screenX, screenY, grown, faded, z, dx, dy)
    }

    override fun end() = inner.end()
}
