package link.socket.phosphor.field

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt
import link.socket.phosphor.math.Vector3

/**
 * A single voxel in a sphere lattice.
 *
 * Values are computed once when the lattice is built so renderers can iterate
 * the immutable voxel list without recomputing geometry per frame.
 */
data class Voxel(
    val gridX: Int,
    val gridY: Int,
    val gridZ: Int,
    val normalizedPos: Vector3,
    val unitDirection: Vector3,
    val theta: Float,
    val phi: Float,
    val distance: Float,
    val jitter: Vector3,
)

/**
 * A deterministic sphere-shaped voxel lattice centered on the grid origin.
 *
 * @property resolution Radius of the integer lattice in grid cells.
 */
class VoxelSphere(val resolution: Int) {
    init {
        require(resolution >= 0) { "resolution must be >= 0" }
    }

    val voxels: List<Voxel> = buildVoxels(resolution)

    val count: Int get() = voxels.size

    val worldScale: Float = TARGET_WORLD_SIZE / max(resolution, 1)

    fun rebuild(newResolution: Int): VoxelSphere = VoxelSphere(newResolution)

    companion object {
        private const val TARGET_WORLD_SIZE = 11f
        private const val RADIUS_INFLATION = 0.45f

        fun totalCount(resolution: Int): Int {
            require(resolution >= 0) { "resolution must be >= 0" }

            var count = 0
            for (x in -resolution..resolution) {
                for (y in -resolution..resolution) {
                    for (z in -resolution..resolution) {
                        if (isInsideSphere(x, y, z, resolution)) {
                            count++
                        }
                    }
                }
            }
            return count
        }

        private fun buildVoxels(resolution: Int): List<Voxel> =
            buildList(totalCount(resolution)) {
                val normalizationScale = max(resolution, 1).toFloat()
                for (x in -resolution..resolution) {
                    for (y in -resolution..resolution) {
                        for (z in -resolution..resolution) {
                            if (isInsideSphere(x, y, z, resolution)) {
                                add(createVoxel(x, y, z, normalizationScale))
                            }
                        }
                    }
                }
            }

        private fun isInsideSphere(
            x: Int,
            y: Int,
            z: Int,
            resolution: Int,
        ): Boolean {
            val radius = resolution + RADIUS_INFLATION
            val distance = sqrt((x * x + y * y + z * z).toFloat())
            return distance <= radius
        }

        private fun createVoxel(
            x: Int,
            y: Int,
            z: Int,
            normalizationScale: Float,
        ): Voxel {
            val position = Vector3(x.toFloat(), y.toFloat(), z.toFloat())
            val normalizedPos = position * (1f / normalizationScale)
            val distance = position.length()
            val unitDirection = normalizedPos.normalized()
            val theta = atan2(z.toFloat(), x.toFloat())
            val phi =
                if (distance > 0f) {
                    acos((y / distance).coerceIn(-1f, 1f))
                } else {
                    0f
                }

            return Voxel(
                gridX = x,
                gridY = y,
                gridZ = z,
                normalizedPos = normalizedPos,
                unitDirection = unitDirection,
                theta = theta,
                phi = phi,
                distance = distance,
                jitter = deterministicJitter(x, y, z),
            )
        }

        private fun deterministicJitter(
            x: Int,
            y: Int,
            z: Int,
        ): Vector3 =
            Vector3(
                hashToJitter(x, y, z, 0x21),
                hashToJitter(x, y, z, 0x43),
                hashToJitter(x, y, z, 0x65),
            )

        private fun hashToJitter(
            x: Int,
            y: Int,
            z: Int,
            salt: Int,
        ): Float {
            var hash = 0x811C9DC5.toInt()
            hash = mix(hash, x)
            hash = mix(hash, y)
            hash = mix(hash, z)
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

/**
 * Return true when this voxel's outward direction faces a viewer on the +Z axis.
 */
fun Voxel.facingCamera(
    orbQuaternion: Vector3,
    threshold: Float = 0.15f,
): Boolean = unitDirection.rotatedBy(orbQuaternion).z > threshold
