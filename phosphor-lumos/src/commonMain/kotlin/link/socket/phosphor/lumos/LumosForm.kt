package link.socket.phosphor.lumos

/**
 * Where the orb's cells sit at rest: geometry as a sampler.
 *
 * A form answers "give me this many rest positions for this look at this moment"
 * with a [FormSample] in unit-sphere space, `[-1, 1]³`, plus the per-cell fields
 * [VoxelFrameBuilder] reads to pattern, bump, jitter and carve each cell. The
 * builder calls [sample] once per frame and emits one `VoxelCell` per sampled
 * position, in sample order, so index `i` is the same cell in every frame a
 * trace records and a form must keep its ordering stable across calls.
 *
 * [Forms.Lattice] is the default and today's only form: the sphere-shaped
 * integer lattice that `VoxelSphere` builds, which fixes its own count and
 * ignores the argument.
 *
 * Implementations are called from the render loop and must not allocate when
 * nothing has changed: compute a sample when the inputs it depends on change
 * and return the same instance until they do.
 */
fun interface LumosForm {
    /**
     * The form's rest positions and derived fields.
     *
     * @param count Cells the caller has budget for: the count of the lattice at
     *  the atmosphere's resolution, 4,801 at resolution 10. A form that fixes
     *  its own count, as the lattice does, may ignore it.
     * @param look The idiom the orb is wearing; see [LumosLook].
     * @param time The frame's time in seconds, for a form that moves on its own.
     *  The lattice ignores it.
     */
    fun sample(
        count: Int,
        look: LumosLook,
        time: Float,
    ): FormSample
}
