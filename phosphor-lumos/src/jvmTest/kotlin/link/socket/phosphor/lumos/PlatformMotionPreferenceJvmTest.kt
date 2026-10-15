package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertFalse

class PlatformMotionPreferenceJvmTest {
    @Test
    fun `the JVM never asks for reduced motion`() {
        assertFalse(PlatformMotionPreference.reduceMotion)
    }
}
