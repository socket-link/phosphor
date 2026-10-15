package link.socket.phosphor.lumos

import link.socket.phosphor.field.VoxelSphere

/**
 * The catalogue of [LumosForm]s. One entry today; PHO-56 fills it.
 */
object Forms {
    /**
     * Today's geometry: the sphere-shaped integer lattice [VoxelSphere] builds,
     * every grid point within `resolution + 0.45` of the origin, normalized by
     * `max(resolution, 1)` into unit space.
     *
     * The count is fixed by [resolution] — 1 at 0, 171 at 3, 1,141 at 6, 4,801
     * at 10 — and the `count`, look and time passed to [sample] are ignored. The
     * sample is built once and the same instance is returned on every call, so
     * the lattice costs nothing per frame.
     *
     * @property resolution Radius of the lattice in grid cells; `>= 0`.
     * @throws IllegalArgumentException when [resolution] is negative.
     */
    class Lattice(val resolution: Int) : LumosForm {
        /** The lattice itself, for callers that read voxels directly. */
        val sphere: VoxelSphere = VoxelSphere(resolution)

        /** Cells in the lattice; what [sample] returns regardless of the count asked for. */
        val count: Int get() = sphere.count

        private val resting: FormSample = FormSample.fromLattice(sphere)

        override fun sample(
            count: Int,
            look: LumosLook,
            time: Float,
        ): FormSample = resting

        override fun toString(): String = "Forms.Lattice(resolution=$resolution)"
    }
}
