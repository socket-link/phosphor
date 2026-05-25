package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals

class LumosRenderTargetTest {
    @Test
    fun `enum contains exactly the documented values`() {
        assertEquals(
            listOf(
                LumosRenderTarget.VOXEL_NATIVE,
                LumosRenderTarget.VOXEL_TERMINAL,
            ),
            LumosRenderTarget.entries.toList(),
        )
    }

    @Test
    fun `valueOf resolves each documented value`() {
        assertEquals(LumosRenderTarget.VOXEL_NATIVE, LumosRenderTarget.valueOf("VOXEL_NATIVE"))
        assertEquals(LumosRenderTarget.VOXEL_TERMINAL, LumosRenderTarget.valueOf("VOXEL_TERMINAL"))
    }
}
