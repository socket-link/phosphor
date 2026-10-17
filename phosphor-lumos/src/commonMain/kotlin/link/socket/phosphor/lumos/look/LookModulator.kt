package link.socket.phosphor.lumos.look

/**
 * A look as a per-cell function of view space and time.
 *
 * `VoxelFrameBuilder` calls [beginFrame] once per frame, then [modulate] for
 * every cell with the cell rotated into a [ViewPoint], and applies what the
 * modulator wrote to the [CellModulation]: radius onto the cell's scale, alpha
 * onto its alpha, extent and the screen-plane push onto its rest position, lit
 * onto its colour. This runs after the form's positions and before the
 * atmosphere's pulse, squash and bump, in unit-sphere view space.
 *
 * `t` is the look's shared clock in seconds — `FramePhases.lookSeconds`, which
 * `LookClock` aligns across every orb of one look — so two orbs in one look at
 * one time draw the same modulation. A look with state of its own (the
 * reasoning walk) advances it in [beginFrame] as a function of that clock,
 * replaying forward from where it was and from the start on a rewind, so the
 * state is still a function of `t`.
 *
 * Neither call may allocate: the builder reuses its [ViewPoint] and
 * [CellModulation], and a modulator's tables are built once by [Looks.forLook].
 *
 * Under rotation carry (`:phosphor-trace`), a look's modulation was computed
 * for the recorded angles and playback adds the carried offset to them, so a
 * rim fade sits off the true front by that offset: the loop's net yaw per pass
 * it has wrapped, which only a loop of whole turns keeps at zero.
 */
fun interface LookModulator {
    /**
     * Once per frame, before any cell. A look with per-frame work — a lens to
     * place, a walk to advance — does it here against the clock and the frame's
     * [rotation]. Nothing by default.
     */
    fun beginFrame(
        t: Float,
        rotation: ViewRotation,
    ) {
    }

    /**
     * The orb's yaw at [t] in radians, when the look owns it, or null to keep the
     * atmosphere's (`FramePhases.orbRotationY`), which every look but retrying
     * does.
     *
     * A look-owned yaw replaces the atmosphere's for the whole frame: the
     * [ViewRotation] [beginFrame] and [modulate] see, the glyph's facing test,
     * and `VoxelAmbient.orbRotationY`, so the surfaces turn the orb by it at
     * projection and a trace records it where rotation carry reads it. It is a
     * function of [t] alone, so every orb of the look turns alike, and lies in
     * `0..2π` as the atmosphere's does. Called once per frame before
     * [beginFrame], and twice more for the rate the spin carries cells at
     * ([ViewPoint.dx]).
     */
    fun yaw(t: Float): Float? = null

    /**
     * Write what the look does to cell [index], seen as [view] at time [t], into
     * [out], which arrives reset.
     */
    fun modulate(
        index: Int,
        view: ViewPoint,
        t: Float,
        out: CellModulation,
    )
}
