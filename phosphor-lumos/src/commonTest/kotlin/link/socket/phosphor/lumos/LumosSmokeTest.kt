package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import link.socket.phosphor.signal.CognitivePhase

class LumosSmokeTest {
    @Test
    fun `lumos package compiles with core dependency`() {
        assertEquals(CognitivePhase.NONE, CognitivePhase.valueOf("NONE"))
    }
}
