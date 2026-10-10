package link.socket.phosphor.trace

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.VoxelGlyphState

/**
 * Collapsing two read heads into one frame: what interpolates, what does not,
 * and what happens when the two cell lists cannot be paired.
 */
class FrameBlendTest {
    @Test
    fun weightZeroIsTheOutgoingFrame() {
        val blended = FrameBlend(frameOf(0f), frameOf(1f), weight = 0f).transduce()

        assertEquals(0f, blended.cells.single().x)
    }

    @Test
    fun weightOneIsTheIncomingFrame() {
        val blended = FrameBlend(frameOf(0f), frameOf(1f), weight = 1f).transduce()

        assertEquals(1f, blended.cells.single().x)
    }

    @Test
    fun everyCellChannelLerps() {
        val from =
            frameOf(
                cells = listOf(VoxelCell(x = 0f, y = 2f, z = 4f, scale = 0f, red = 0f, green = 1f, blue = 0.5f)),
            )
        val to =
            frameOf(
                cells = listOf(VoxelCell(x = 1f, y = 4f, z = 8f, scale = 1f, red = 1f, green = 0f, blue = 0.5f)),
            )

        val cell = FrameBlend(from, to, weight = 0.25f).transduce().cells.single()

        assertEquals(0.25f, cell.x)
        assertEquals(2.5f, cell.y)
        assertEquals(5f, cell.z)
        assertEquals(0.25f, cell.scale)
        assertEquals(0.25f, cell.red)
        assertEquals(0.75f, cell.green)
        assertEquals(0.5f, cell.blue)
    }

    @Test
    fun theWeightIsClampedIntoRange() {
        assertEquals(0f, FrameBlend(frameOf(0f), frameOf(1f), weight = -2f).transduce().cells.single().x)
        assertEquals(1f, FrameBlend(frameOf(0f), frameOf(1f), weight = 3f).transduce().cells.single().x)
    }

    // ---------------------------------------------------------------------
    // Alpha
    // ---------------------------------------------------------------------

    @Test
    fun twoAlphaFreeFramesBlendToAnAlphaFreeFrame() {
        val blended = FrameBlend(frameOf(0f), frameOf(1f), weight = 0.5f).transduce()

        assertNull(blended.cells.single().alpha)
    }

    @Test
    fun aNullAlphaBlendsAsFullyOpaque() {
        val transparent = frameOf(cells = listOf(cellWith(alpha = 0f)))
        val implied = frameOf(cells = listOf(cellWith(alpha = null)))

        val blended = FrameBlend(transparent, implied, weight = 0.5f).transduce()

        // null means "use 1.0", so halfway between 0.0 and an implied 1.0.
        assertEquals(0.5f, blended.cells.single().alpha)
    }

    // ---------------------------------------------------------------------
    // Ambient
    // ---------------------------------------------------------------------

    @Test
    fun glowChannelsLerp() {
        val from = frameOf(ambient = VoxelAmbient(0f, 0f, 0f, 0f, 0f, 0f, 0f))
        val to = frameOf(ambient = VoxelAmbient(1f, 1f, 1f, 1f, 0f, 0f, 0f))

        val ambient = FrameBlend(from, to, weight = 0.5f).transduce().ambient

        assertEquals(0.5f, ambient.glowRed)
        assertEquals(0.5f, ambient.glowGreen)
        assertEquals(0.5f, ambient.glowBlue)
        assertEquals(0.5f, ambient.glowIntensity)
    }

    @Test
    fun rotationTakesTheShortArcAcrossTheWrap() {
        // Two heads either side of 2π: 6.2 rad and 0.1 rad are 0.18 rad apart
        // the short way and 6.1 rad apart the long way. A straight lerp would
        // land near 3.15 and the orb would visibly spin backwards.
        val from = frameOf(ambient = VoxelAmbient(0f, 0f, 0f, 0f, 6.2f, 6.2f, 6.2f))
        val to = frameOf(ambient = VoxelAmbient(0f, 0f, 0f, 0f, 0.1f, 0.1f, 0.1f))

        val ambient = FrameBlend(from, to, weight = 0.5f).transduce().ambient

        assertNear(0.0084f, ambient.orbRotationX)
        assertNear(0.0084f, ambient.orbRotationY)
        assertNear(0.0084f, ambient.orbRotationZ)
    }

    @Test
    fun aBlendedRotationStaysInsideOneTurn() {
        val from = frameOf(ambient = VoxelAmbient(0f, 0f, 0f, 0f, 6.28f, 0.01f, 3.14f))
        val to = frameOf(ambient = VoxelAmbient(0f, 0f, 0f, 0f, 0.01f, 6.28f, 0.0f))

        for (step in 0..10) {
            val ambient = FrameBlend(from, to, weight = step / 10f).transduce().ambient
            assertTrue(ambient.orbRotationX in 0f..TWO_PI, "x out of turn: ${ambient.orbRotationX}")
            assertTrue(ambient.orbRotationY in 0f..TWO_PI, "y out of turn: ${ambient.orbRotationY}")
            assertTrue(ambient.orbRotationZ in 0f..TWO_PI, "z out of turn: ${ambient.orbRotationZ}")
        }
    }

    // ---------------------------------------------------------------------
    // Glyph
    // ---------------------------------------------------------------------

    @Test
    fun theHeavierSideOwnsTheGlyph() {
        // A glyph name is discrete — there is no frame halfway between a CHECK
        // and a SPARK — so the glyph is picked rather than interpolated.
        val from = frameOf(0f, glyph = VoxelGlyphState("CHECK", progress = 0.9f, red = 1f, green = 0f, blue = 0f))
        val to = frameOf(1f, glyph = VoxelGlyphState("SPARK", progress = 0.1f, red = 0f, green = 1f, blue = 0f))

        assertEquals("CHECK", FrameBlend(from, to, weight = 0.49f).transduce().glyph?.glyphName)
        assertEquals("SPARK", FrameBlend(from, to, weight = 0.5f).transduce().glyph?.glyphName)
        assertSame(from.glyph, FrameBlend(from, to, weight = 0f).transduce().glyph)
    }

    // ---------------------------------------------------------------------
    // Alignment
    // ---------------------------------------------------------------------

    @Test
    fun unpairedCellListsCutRatherThanBlend() {
        val from = frameOf(cells = List(3) { cellWith(alpha = null) })
        val to = frameOf(cells = List(2) { cellWith(alpha = null) })
        val blend = FrameBlend(from, to, weight = 0.4f)

        assertFalse(blend.isAligned)
        // Returning an input frame outright, not a reconstruction of it: a cut
        // costs nothing and holds the identical instance.
        assertSame(from, blend.transduce())
        assertSame(to, blend.copy(weight = 0.6f).transduce())
    }

    @Test
    fun equalCellCountsAreAligned() {
        assertTrue(FrameBlend(frameOf(0f), frameOf(1f), weight = 0f).isAligned)
    }

    private companion object {
        const val TWO_PI: Float = (2.0 * PI).toFloat()
        const val TOLERANCE: Float = 1e-3f

        fun assertNear(
            expected: Float,
            actual: Float,
        ) {
            assertTrue(
                abs(expected - actual) <= TOLERANCE,
                "expected $expected but was $actual",
            )
        }

        fun cellWith(alpha: Float?): VoxelCell =
            VoxelCell(x = 0f, y = 0f, z = 0f, scale = 1f, red = 0f, green = 0f, blue = 0f, alpha = alpha)

        fun frameOf(
            signature: Float = 0f,
            cells: List<VoxelCell> = listOf(cellWith(alpha = null).copy(x = signature)),
            ambient: VoxelAmbient = VoxelAmbient(0f, 0f, 0f, 0f, 0f, 0f, 0f),
            glyph: VoxelGlyphState? = null,
        ): VoxelFrame =
            VoxelFrame(
                tick = 0L,
                timestampEpochMillis = 0L,
                resolution = 2,
                cells = cells,
                ambient = ambient,
                glyph = glyph,
            )
    }
}
