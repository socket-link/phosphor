package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LookTest {
    @Test
    fun `key is the look's name, with the variant after a dash when it is not the default`() {
        assertEquals("base", Look(LumosLook.BASE).key)
        assertEquals("working", Look(LumosLook.WORKING).key)
        assertEquals("working-gyro", Look(LumosLook.WORKING, "gyro").key)
        assertEquals("compacting-fuse", Look(LumosLook.COMPACTING, "fuse").key)
    }

    @Test
    fun `a variant the look does not offer is rejected`() {
        assertFailsWith<IllegalArgumentException> { Look(LumosLook.BASE, "gyro") }
        assertFailsWith<IllegalArgumentException> { Look(LumosLook.WORKING, "twins") }
    }

    @Test
    fun `every look offers the default variant first`() {
        for (look in LumosLook.entries) {
            assertEquals(Look.DEFAULT_VARIANT, look.variants.first(), "$look")
            Look(look)
        }
    }

    @Test
    fun `periods are the idioms' beats and zero where nothing is clock-driven`() {
        assertEquals(0f, Look(LumosLook.BASE).periodSeconds)
        assertEquals(1.7f, Look(LumosLook.WORKING).periodSeconds)
        assertEquals(1.8f, Look(LumosLook.SEARCHING).periodSeconds)
        assertEquals(0.22f, Look(LumosLook.REASONING).periodSeconds)
        assertEquals(0f, Look(LumosLook.WAITING).periodSeconds, "not yet ported, so nothing is clock-driven")
    }
}
