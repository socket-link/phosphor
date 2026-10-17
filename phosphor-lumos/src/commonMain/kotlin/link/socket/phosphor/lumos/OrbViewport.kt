package link.socket.phosphor.lumos

/**
 * Where the orb's unit sphere sits in root pixels: the pixel-to-unit transform.
 *
 * `[-1, 1]` maps to `centre ± radiusPx`, Compose orientation, Y down: unit `y = +1` is
 * [radiusPx] above [centerYPx]. A projection given a viewport draws the orb at this size and
 * place on a canvas larger than itself, so cells can leave the orb and cross the rest of it.
 *
 * @param centerXPx Horizontal centre of the orb, in pixels from the canvas's left edge. Must be finite.
 * @param centerYPx Vertical centre of the orb, in pixels from the canvas's top edge. Must be finite.
 * @param radiusPx Pixels from the centre to the unit sphere's rim. Must be finite and > 0.
 */
data class OrbViewport(
    val centerXPx: Float,
    val centerYPx: Float,
    val radiusPx: Float,
) {
    init {
        require(centerXPx.isFinite()) { "centerXPx must be finite, got $centerXPx" }
        require(centerYPx.isFinite()) { "centerYPx must be finite, got $centerYPx" }
        require(radiusPx.isFinite() && radiusPx > 0f) { "radiusPx must be finite and > 0, got $radiusPx" }
    }
}
