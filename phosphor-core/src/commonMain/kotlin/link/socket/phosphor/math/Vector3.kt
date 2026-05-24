package link.socket.phosphor.math

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 3D vector for position and direction calculations in world space.
 */
data class Vector3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(other: Vector3): Vector3 = Vector3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vector3): Vector3 = Vector3(x - other.x, y - other.y, z - other.z)

    operator fun times(scalar: Float): Vector3 = Vector3(x * scalar, y * scalar, z * scalar)

    operator fun unaryMinus(): Vector3 = Vector3(-x, -y, -z)

    fun dot(other: Vector3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vector3): Vector3 =
        Vector3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun lengthSquared(): Float = x * x + y * y + z * z

    fun normalized(): Vector3 {
        val len = length()
        return if (len > 0f) this * (1f / len) else ZERO
    }

    /**
     * Rotate this vector by X/Y/Z radians, in that order.
     *
     * This follows the same axis conventions as [Matrix4.rotateX] and [Matrix4.rotateY].
     */
    fun rotatedBy(rotation: Vector3): Vector3 {
        val xRotated = rotateX(rotation.x)
        val yRotated = xRotated.rotateY(rotation.y)
        return yRotated.rotateZ(rotation.z)
    }

    private fun rotateX(radians: Float): Vector3 {
        val c = cos(radians)
        val s = sin(radians)
        return Vector3(
            x = x,
            y = c * y - s * z,
            z = s * y + c * z,
        )
    }

    private fun rotateY(radians: Float): Vector3 {
        val c = cos(radians)
        val s = sin(radians)
        return Vector3(
            x = c * x + s * z,
            y = y,
            z = -s * x + c * z,
        )
    }

    private fun rotateZ(radians: Float): Vector3 {
        val c = cos(radians)
        val s = sin(radians)
        return Vector3(
            x = c * x - s * y,
            y = s * x + c * y,
            z = z,
        )
    }

    companion object {
        val ZERO = Vector3(0f, 0f, 0f)
        val UP = Vector3(0f, 1f, 0f)
        val FORWARD = Vector3(0f, 0f, 1f)
        val RIGHT = Vector3(1f, 0f, 0f)
    }
}
