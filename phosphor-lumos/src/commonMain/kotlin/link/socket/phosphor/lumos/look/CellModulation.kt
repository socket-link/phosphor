package link.socket.phosphor.lumos.look

/**
 * What a look does to one cell.
 *
 * Every field is [reset] before each cell; a look writes only what it changes.
 * Multipliers compose with the atmosphere's own: the builder applies them in
 * unit-sphere space before jitter, bump, pulse and squash, so the skin keeps
 * riding the body. One instance per builder; never retained.
 */
class CellModulation {
    /** Multiplier on `VoxelCell.scale`. The ring's crest grows cells to 1.6; the lens's to 1.9. */
    var radius: Float = 1f

    /**
     * The cell's alpha, 0..1, where the atmosphere gives none: the rim ramp, the
     * dim outside the lens, the walk's 0.5 rest. A look that lights a cell raises
     * this to at least [lit]. 1 leaves the cell as it was.
     */
    var alpha: Float = 1f

    /**
     * Radial multiplier on the rest position, along the cell's own direction.
     * Rotation-invariant, so it applies in rest space as `position × extent`: the
     * lens swells cells out to 1.12, the ring's wake pulls them in to 0.92.
     */
    var extent: Float = 1f

    /**
     * Screen-plane push in view space, unit-sphere units: the lens's magnifier
     * spread, 0.35 of a cell's offset from the lens centre. The builder carries it
     * back to rest space through the inverse rotation, so after projection it is
     * exactly this on screen.
     */
    var ox: Float = 0f

    /** Screen-plane push in view space; see [ox]. */
    var oy: Float = 0f

    /**
     * How lit the cell is, 0..1: the ring's crest, the lens's interior, the
     * walk's head and tail. The builder lifts the cell's colour toward white by
     * this much of `LIT_MIX`, so a lit cell reads on a colour surface as it did
     * on Thinking Orbs' monochrome one, where "light fully" meant opacity 1.
     */
    var lit: Float = 0f

    /**
     * Mix toward [from]: every field becomes `from · (1 − weight) + this · weight`,
     * so at 0 this is [from] exactly and at 1 it is unchanged. The look blend's
     * per-cell step; [from] is the outgoing look's modulation.
     */
    fun mixFrom(
        from: CellModulation,
        weight: Float,
    ) {
        val keep = 1f - weight
        radius = from.radius * keep + radius * weight
        alpha = from.alpha * keep + alpha * weight
        extent = from.extent * keep + extent * weight
        ox = from.ox * keep + ox * weight
        oy = from.oy * keep + oy * weight
        lit = from.lit * keep + lit * weight
    }

    /** Back to "leave the cell as it is". */
    fun reset() {
        radius = 1f
        alpha = 1f
        extent = 1f
        ox = 0f
        oy = 0f
        lit = 0f
    }
}
