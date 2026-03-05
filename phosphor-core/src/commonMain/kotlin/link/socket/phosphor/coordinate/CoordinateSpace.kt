package link.socket.phosphor.coordinate

import link.socket.phosphor.math.Vector2
import link.socket.phosphor.math.Vector3

/**
 * Defines the two coordinate spaces used by Phosphor's rendering pipeline.
 *
 * - [WORLD_CENTERED]: Origin at center of the world. Coordinates range from
 *   `-size/2` to `+size/2`. Used by [CognitiveWaveform.worldPosition] and
 *   the 3D camera/projection pipeline.
 *
 * - [WORLD_POSITIVE]: Origin at the corner (minimum). Coordinates range from
 *   `0` to `size`. Used by the waveform grid sampling loop and substrate
 *   density lookups.
 */
enum class CoordinateSpace {
    /** Origin at center, e.g. -w/2 .. +w/2 */
    WORLD_CENTERED,

    /** Origin at corner, e.g. 0 .. w */
    WORLD_POSITIVE,
}

/**
 * Converts positions between [CoordinateSpace.WORLD_CENTERED] and
 * [CoordinateSpace.WORLD_POSITIVE].
 *
 * All conversions are symmetric and round-trip safe:
 * ```
 * toCentered(toPositive(x, size), size) == x
 * toPositive(toCentered(x, size), size) == x
 * ```
 */
object CoordinateTransform {
    /**
     * Convert a scalar from [from] space to [CoordinateSpace.WORLD_POSITIVE].
     *
     * @param pos The coordinate value to convert
     * @param worldSize The extent of the axis (width or depth)
     * @param from The source coordinate space
     * @return The coordinate in WORLD_POSITIVE space
     */
    fun toPositive(
        pos: Float,
        worldSize: Float,
        from: CoordinateSpace,
    ): Float =
        when (from) {
            CoordinateSpace.WORLD_POSITIVE -> pos
            CoordinateSpace.WORLD_CENTERED -> pos + worldSize / 2f
        }

    /**
     * Convert a scalar from [from] space to [CoordinateSpace.WORLD_CENTERED].
     *
     * @param pos The coordinate value to convert
     * @param worldSize The extent of the axis (width or depth)
     * @param from The source coordinate space
     * @return The coordinate in WORLD_CENTERED space
     */
    fun toCentered(
        pos: Float,
        worldSize: Float,
        from: CoordinateSpace,
    ): Float =
        when (from) {
            CoordinateSpace.WORLD_CENTERED -> pos
            CoordinateSpace.WORLD_POSITIVE -> pos - worldSize / 2f
        }

    /**
     * Convert a [Vector2] position between coordinate spaces.
     *
     * @param pos The 2D position to convert (x maps to world-X, y maps to world-Z)
     * @param worldWidth Extent of the X axis
     * @param worldDepth Extent of the Z axis
     * @param from Source coordinate space
     * @param to Target coordinate space
     * @return Converted position
     */
    fun convert(
        pos: Vector2,
        worldWidth: Float,
        worldDepth: Float,
        from: CoordinateSpace,
        to: CoordinateSpace,
    ): Vector2 {
        if (from == to) return pos
        return when (to) {
            CoordinateSpace.WORLD_POSITIVE ->
                Vector2(
                    toPositive(pos.x, worldWidth, from),
                    toPositive(pos.y, worldDepth, from),
                )
            CoordinateSpace.WORLD_CENTERED ->
                Vector2(
                    toCentered(pos.x, worldWidth, from),
                    toCentered(pos.y, worldDepth, from),
                )
        }
    }

    /**
     * Convert a [Vector3] position between coordinate spaces.
     * Only X and Z are converted; Y (height) is preserved.
     *
     * @param pos The 3D position to convert
     * @param worldWidth Extent of the X axis
     * @param worldDepth Extent of the Z axis
     * @param from Source coordinate space
     * @param to Target coordinate space
     * @return Converted position with Y unchanged
     */
    fun convert(
        pos: Vector3,
        worldWidth: Float,
        worldDepth: Float,
        from: CoordinateSpace,
        to: CoordinateSpace,
    ): Vector3 {
        if (from == to) return pos
        return when (to) {
            CoordinateSpace.WORLD_POSITIVE ->
                Vector3(
                    toPositive(pos.x, worldWidth, from),
                    pos.y,
                    toPositive(pos.z, worldDepth, from),
                )
            CoordinateSpace.WORLD_CENTERED ->
                Vector3(
                    toCentered(pos.x, worldWidth, from),
                    pos.y,
                    toCentered(pos.z, worldDepth, from),
                )
        }
    }
}
