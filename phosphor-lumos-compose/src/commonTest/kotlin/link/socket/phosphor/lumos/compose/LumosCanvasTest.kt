package link.socket.phosphor.lumos.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelGlyphState
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame

/**
 * Unit tests for [LumosCanvas] composable semantics.
 *
 * Pure-Compose UI rendering tests are not practical cross-platform, so this suite covers:
 *  1. @Stable annotation contract — identical [LumosCanvasFrame] values produce equal inputs,
 *     enabling Compose's skip-recomposition optimization.
 *  2. Behavioral correctness of frame data that the composable consumes.
 *
 * The @Stable annotation on [LumosCanvas] itself is verified by inspection; the data-class
 * structural equality tested here is the necessary precondition for that contract to hold.
 */
class LumosCanvasTest {
    private val ambient =
        VoxelAmbient(
            glowRed = 0.2f,
            glowGreen = 0.3f,
            glowBlue = 0.9f,
            glowIntensity = 0.8f,
            orbRotationX = 0f,
            orbRotationY = 0f,
            orbRotationZ = 0f,
        )

    private val voxel =
        LumosCanvasFrame.CanvasVoxel(
            screenX = 100f,
            screenY = 100f,
            radiusPx = 5f,
            red = 0.4f,
            green = 0.5f,
            blue = 0.9f,
            alpha = 1f,
            z = 0f,
        )

    // Identical LumosCanvasFrame instances must be structurally equal so Compose can skip
    // recomposition when the upstream frame hasn't changed — the core @Stable contract.
    @Test
    fun `identical frames are equal`() {
        val frame1 = LumosCanvasFrame(200, 200, listOf(voxel), ambient, null, tick = 42L)
        val frame2 = LumosCanvasFrame(200, 200, listOf(voxel), ambient, null, tick = 42L)
        assertEquals(frame1, frame2)
    }

    // A changed tick (monotonic counter advancing each render loop) must produce a different
    // frame so that Compose triggers recomposition and the new voxels are painted.
    @Test
    fun `frames with different ticks are not equal`() {
        val frame1 = LumosCanvasFrame(200, 200, listOf(voxel), ambient, null, tick = 1L)
        val frame2 = LumosCanvasFrame(200, 200, listOf(voxel), ambient, null, tick = 2L)
        assertNotEquals(frame1, frame2)
    }

    // Changed voxels must also produce inequality so animated orbs trigger recomposition.
    @Test
    fun `frames with different voxels are not equal`() {
        val voxel2 = voxel.copy(screenX = 110f)
        val frame1 = LumosCanvasFrame(200, 200, listOf(voxel), ambient, null, tick = 0L)
        val frame2 = LumosCanvasFrame(200, 200, listOf(voxel2), ambient, null, tick = 0L)
        assertNotEquals(frame1, frame2)
    }

    // Glyph progress of 0.0 and 1.0 must produce distinct frames so the fade envelope
    // drives recomposition correctly.
    @Test
    fun `frames with different glyph progress are not equal`() {
        val glyphHidden = VoxelGlyphState("CHECK", progress = 0.0f, red = 0.3f, green = 0.9f, blue = 0.4f)
        val glyphVisible = glyphHidden.copy(progress = 1.0f)
        val frame1 = LumosCanvasFrame(200, 200, emptyList(), ambient, glyphHidden, tick = 0L)
        val frame2 = LumosCanvasFrame(200, 200, emptyList(), ambient, glyphVisible, tick = 0L)
        assertNotEquals(frame1, frame2)
    }

    // Frame with a null glyph differs from the same frame with an active glyph.
    @Test
    fun `frame with null glyph differs from frame with active glyph`() {
        val glyph = VoxelGlyphState("STAR", progress = 1.0f, red = 0.9f, green = 0.8f, blue = 0.1f)
        val frameNoGlyph = LumosCanvasFrame(200, 200, emptyList(), ambient, null, tick = 0L)
        val frameWithGlyph = LumosCanvasFrame(200, 200, emptyList(), ambient, glyph, tick = 0L)
        assertNotEquals(frameNoGlyph, frameWithGlyph)
    }
}
