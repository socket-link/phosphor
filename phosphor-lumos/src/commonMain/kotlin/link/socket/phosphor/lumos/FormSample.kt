package link.socket.phosphor.lumos

import kotlin.math.acos
import kotlin.math.atan2
import link.socket.phosphor.field.VoxelSphere
import link.socket.phosphor.math.Vector3

/**
 * A sampled [LumosForm]: the rest positions of the orb's cells and the fields
 * derived from them, index-aligned.
 *
 * The builder reads every field by index each frame, so they are stored as
 * parallel lists and arrays rather than as one object per cell. A form with a
 * closed-form derivation passes every field to the constructor; one that only
 * knows where its cells sit calls `derive`.
 *
 * Fields are defined so the default lattice reproduces today's `Voxel` values
 * bit for bit:
 *
 * - [positions] — rest positions in unit-sphere space, `[-1, 1]³`.
 * - [unitDirections] — the outward surface normal, `position.normalized()`;
 *   the zero vector for a cell at the origin, which the bump then leaves in place.
 * - [theta] — azimuth about the Y axis, `atan2(z, x)`, in radians.
 * - [phi] — polar angle from +Y, `acos(y / |p|)`, in radians; `0` at the origin.
 * - [distance] — radius in lattice cells, not unit space: for the lattice it is
 *   the integer grid length, `0` to `resolution + 0.45`, and the PULSE pattern
 *   reads it as about 1.4 radial wavelengths across the orb at resolution 10.
 *   A form in unit space scales its unit radius by the lattice's radius to
 *   match; see `derive`.
 * - [jitter] — a deterministic per-cell offset in `[-0.5, 0.5]³` that
 *   `AtmosphereState.noise` scales. The lattice hashes grid coordinates;
 *   `derive` hashes the index.
 *
 * Not a data class: the arrays compare by reference. Compare fields.
 *
 * @throws IllegalArgumentException when the fields are not all the same length.
 */
class FormSample(
    val positions: List<Vector3>,
    val unitDirections: List<Vector3>,
    val theta: FloatArray,
    val phi: FloatArray,
    val distance: FloatArray,
    val jitter: List<Vector3>,
) {
    /** Number of cells. Index `i` in every field is the same cell. */
    val count: Int get() = positions.size

    init {
        require(
            unitDirections.size == count &&
                theta.size == count &&
                phi.size == count &&
                distance.size == count &&
                jitter.size == count,
        ) {
            "every FormSample field must have ${positions.size} entries: unitDirections ${unitDirections.size}, " +
                "theta ${theta.size}, phi ${phi.size}, distance ${distance.size}, jitter ${jitter.size}"
        }
    }

    companion object {
        /**
         * Derive every field from [positions] alone, as `VoxelSphere` derives
         * them from a grid position.
         *
         * @param positions Rest positions in unit-sphere space.
         * @param radiusScale Lattice cells per unit of radius, so `distance` reads
         *  in the same cells the lattice's does. `max(resolution, 1)` matches the
         *  lattice at that resolution; the default leaves `distance` in unit space.
         * @throws IllegalArgumentException when [radiusScale] is not positive.
         */
        fun derive(
            positions: List<Vector3>,
            radiusScale: Float = 1f,
        ): FormSample {
            require(radiusScale > 0f) { "radiusScale must be > 0, was $radiusScale" }
            val count = positions.size
            val unitDirections = ArrayList<Vector3>(count)
            val theta = FloatArray(count)
            val phi = FloatArray(count)
            val distance = FloatArray(count)
            val jitter = ArrayList<Vector3>(count)
            positions.forEachIndexed { index, position ->
                val length = position.length()
                unitDirections += position.normalized()
                theta[index] = atan2(position.z, position.x)
                phi[index] = if (length > 0f) acos((position.y / length).coerceIn(-1f, 1f)) else 0f
                distance[index] = length * radiusScale
                jitter += indexJitter(index)
            }
            return FormSample(positions, unitDirections, theta, phi, distance, jitter)
        }

        /**
         * The lattice's own fields, copied from its voxels so nothing is
         * recomputed: a lattice sample equals `VoxelSphere.voxels` index for index.
         */
        internal fun fromLattice(sphere: VoxelSphere): FormSample {
            val voxels = sphere.voxels
            return FormSample(
                positions = voxels.map { it.normalizedPos },
                unitDirections = voxels.map { it.unitDirection },
                theta = FloatArray(voxels.size) { voxels[it].theta },
                phi = FloatArray(voxels.size) { voxels[it].phi },
                distance = FloatArray(voxels.size) { voxels[it].distance },
                jitter = voxels.map { it.jitter },
            )
        }

        private fun indexJitter(index: Int): Vector3 =
            Vector3(
                hashToJitter(index, 0x21),
                hashToJitter(index, 0x43),
                hashToJitter(index, 0x65),
            )

        /** The lattice's FNV-seeded hash, fed the cell index instead of grid coordinates. */
        private fun hashToJitter(
            index: Int,
            salt: Int,
        ): Float {
            var hash = 0x811C9DC5.toInt()
            hash = mix(hash, index)
            hash = mix(hash, salt)
            hash = hash xor (hash ushr 16)
            hash *= 0x7FEB352D
            hash = hash xor (hash ushr 15)
            hash *= 0x846CA68B.toInt()
            hash = hash xor (hash ushr 16)

            val normalized = (hash ushr 1) / Int.MAX_VALUE.toFloat()
            return normalized - 0.5f
        }

        private fun mix(
            hash: Int,
            value: Int,
        ): Int = (hash xor value) * 0x01000193
    }
}
