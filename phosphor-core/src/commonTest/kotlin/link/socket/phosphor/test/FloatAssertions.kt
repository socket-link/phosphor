package link.socket.phosphor.test

import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal fun assertFloatEquals(
    expected: Float,
    actual: Float?,
    tolerance: Float = 1e-4f,
) {
    assertEquals(expected, assertNotNull(actual), absoluteTolerance = tolerance)
}
