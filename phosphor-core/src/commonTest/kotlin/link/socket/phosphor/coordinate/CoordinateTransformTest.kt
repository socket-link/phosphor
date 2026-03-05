package link.socket.phosphor.coordinate

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.math.Vector3

class CoordinateTransformTest {
    private val worldWidth = 20f
    private val worldDepth = 15f
    private val epsilon = 0.0001f

    // --- Scalar round-trip tests ---

    @Test
    fun `toPositive then toCentered is identity`() {
        val values = listOf(-10f, -5f, 0f, 5f, 10f, 7.5f, -7.5f)
        for (v in values) {
            val positive = CoordinateTransform.toPositive(v, worldWidth, CoordinateSpace.WORLD_CENTERED)
            val roundTrip = CoordinateTransform.toCentered(positive, worldWidth, CoordinateSpace.WORLD_POSITIVE)
            assertTrue(
                abs(roundTrip - v) < epsilon,
                "round-trip centered->positive->centered for $v: got $roundTrip",
            )
        }
    }

    @Test
    fun `toCentered then toPositive is identity`() {
        val values = listOf(0f, 5f, 10f, 15f, 20f, 7.5f)
        for (v in values) {
            val centered = CoordinateTransform.toCentered(v, worldWidth, CoordinateSpace.WORLD_POSITIVE)
            val roundTrip = CoordinateTransform.toPositive(centered, worldWidth, CoordinateSpace.WORLD_CENTERED)
            assertTrue(
                abs(roundTrip - v) < epsilon,
                "round-trip positive->centered->positive for $v: got $roundTrip",
            )
        }
    }

    @Test
    fun `same-space conversion is identity`() {
        assertEquals(5f, CoordinateTransform.toPositive(5f, worldWidth, CoordinateSpace.WORLD_POSITIVE))
        assertEquals(-3f, CoordinateTransform.toCentered(-3f, worldWidth, CoordinateSpace.WORLD_CENTERED))
    }

    // --- Scalar value correctness ---

    @Test
    fun `centered origin maps to positive center`() {
        val result = CoordinateTransform.toPositive(0f, worldWidth, CoordinateSpace.WORLD_CENTERED)
        assertTrue(abs(result - 10f) < epsilon, "centered 0 -> positive 10, got $result")
    }

    @Test
    fun `positive origin maps to centered negative half`() {
        val result = CoordinateTransform.toCentered(0f, worldWidth, CoordinateSpace.WORLD_POSITIVE)
        assertTrue(abs(result - (-10f)) < epsilon, "positive 0 -> centered -10, got $result")
    }

    @Test
    fun `centered bounds map to positive bounds`() {
        val left = CoordinateTransform.toPositive(-10f, worldWidth, CoordinateSpace.WORLD_CENTERED)
        val right = CoordinateTransform.toPositive(10f, worldWidth, CoordinateSpace.WORLD_CENTERED)
        assertTrue(abs(left) < epsilon, "centered -10 -> positive 0, got $left")
        assertTrue(abs(right - 20f) < epsilon, "centered +10 -> positive 20, got $right")
    }

    // --- Vector2 tests ---

    @Test
    fun `Vector2 round-trip conversion`() {
        val original = Vector2(10f, 7.5f)
        val centered =
            CoordinateTransform.convert(
                original,
                worldWidth,
                worldDepth,
                from = CoordinateSpace.WORLD_POSITIVE,
                to = CoordinateSpace.WORLD_CENTERED,
            )
        val roundTrip =
            CoordinateTransform.convert(
                centered,
                worldWidth,
                worldDepth,
                from = CoordinateSpace.WORLD_CENTERED,
                to = CoordinateSpace.WORLD_POSITIVE,
            )
        assertTrue(abs(roundTrip.x - original.x) < epsilon, "x: ${roundTrip.x} != ${original.x}")
        assertTrue(abs(roundTrip.y - original.y) < epsilon, "y: ${roundTrip.y} != ${original.y}")
    }

    @Test
    fun `Vector2 same-space conversion returns same value`() {
        val v = Vector2(3f, 4f)
        val result =
            CoordinateTransform.convert(
                v,
                worldWidth,
                worldDepth,
                from = CoordinateSpace.WORLD_POSITIVE,
                to = CoordinateSpace.WORLD_POSITIVE,
            )
        assertEquals(v, result)
    }

    // --- Vector3 tests ---

    @Test
    fun `Vector3 conversion preserves Y`() {
        val original = Vector3(5f, 42f, -3f)
        val converted =
            CoordinateTransform.convert(
                original,
                worldWidth,
                worldDepth,
                from = CoordinateSpace.WORLD_CENTERED,
                to = CoordinateSpace.WORLD_POSITIVE,
            )
        assertEquals(42f, converted.y, "Y should be preserved during conversion")
    }

    @Test
    fun `Vector3 round-trip conversion`() {
        val original = Vector3(-5f, 2f, 3f)
        val positive =
            CoordinateTransform.convert(
                original,
                worldWidth,
                worldDepth,
                from = CoordinateSpace.WORLD_CENTERED,
                to = CoordinateSpace.WORLD_POSITIVE,
            )
        val roundTrip =
            CoordinateTransform.convert(
                positive,
                worldWidth,
                worldDepth,
                from = CoordinateSpace.WORLD_POSITIVE,
                to = CoordinateSpace.WORLD_CENTERED,
            )
        assertTrue(abs(roundTrip.x - original.x) < epsilon, "x: ${roundTrip.x} != ${original.x}")
        assertTrue(abs(roundTrip.y - original.y) < epsilon, "y: ${roundTrip.y} != ${original.y}")
        assertTrue(abs(roundTrip.z - original.z) < epsilon, "z: ${roundTrip.z} != ${original.z}")
    }

    // --- Edge cases ---

    @Test
    fun `negative values in positive space convert correctly`() {
        // This can happen if an agent is placed outside the world bounds
        val result = CoordinateTransform.toPositive(-15f, worldWidth, CoordinateSpace.WORLD_CENTERED)
        assertTrue(abs(result - (-5f)) < epsilon, "centered -15 -> positive -5, got $result")
    }

    @Test
    fun `zero world size does not crash`() {
        val result = CoordinateTransform.toPositive(0f, 0f, CoordinateSpace.WORLD_CENTERED)
        assertEquals(0f, result)
    }
}
