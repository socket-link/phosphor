package link.socket.phosphor.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import link.socket.phosphor.math.Vector2

class LumosInputTest {
    @Test
    fun `NONE points at nothing and presses nothing`() {
        assertNull(LumosInput.NONE.pointer)
        assertFalse(LumosInput.NONE.pressed)
        assertEquals(LumosInput.NONE, LumosInput())
    }

    @Test
    fun `a pointer past the orb's square is allowed`() {
        assertEquals(Vector2(1.5f, -2f), LumosInput(pointer = Vector2(1.5f, -2f)).pointer)
    }

    @Test
    fun `a non-finite pointer is rejected`() {
        assertFailsWith<IllegalArgumentException> { LumosInput(pointer = Vector2(Float.NaN, 0f)) }
        assertFailsWith<IllegalArgumentException> { LumosInput(pointer = Vector2(0f, Float.POSITIVE_INFINITY)) }
    }
}
