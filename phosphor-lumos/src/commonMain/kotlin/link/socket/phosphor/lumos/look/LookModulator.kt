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
 * [t] is the look's shared clock in seconds — `FramePhases.lookSeconds`, which
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
 * for the recorded angles and playback adds a small offset to them, so a rim
 * fade sits off the true front by the carried offset, a few hundredths of a
 * radian.
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
