package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformMotionPreferenceTest {
    @Test
    fun `the platform preference reads without throwing and holds between reads`() {
        val first = PlatformMotionPreference.reduceMotion

        assertEquals(first, PlatformMotionPreference.reduceMotion)
    }
}
