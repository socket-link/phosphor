package link.socket.phosphor.lumos.dom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelGlyphState
import link.socket.phosphor.lumos.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.frame.LumosCanvasFrame.CanvasVoxel
import link.socket.phosphor.lumos.projection.PixelLattice
import link.socket.phosphor.palette.AtmospherePresets

/**
 * The attribute set [SvgOrbSurface] emits for a fixed frame, per style and tint, and how few writes
 * it takes to get there. The frame's numbers are chosen so every attribute is exact to two decimals
 * on every target.
 */
class SvgOrbSurfaceTest {
    @Test
    fun `squares emit the golden tree, halo and hidden glyph included`() {
        val (root, _) = draw(SurfaceStyle.Squares, SvgTint.PER_CELL, FRAME)

        assertEquals(
            "<svg viewBox=\"0 0 40 40\">" +
                "<defs><radialGradient id=\"orb-halo\">" +
                "<stop offset=\"0\" stop-color=\"rgb(128,64,255)\" stop-opacity=\"0.75\"/>" +
                "<stop offset=\"1\" stop-color=\"rgb(128,64,255)\" stop-opacity=\"0\"/>" +
                "</radialGradient></defs>" +
                "<circle cx=\"20\" cy=\"20\" fill=\"url(#orb-halo)\" r=\"22\" visibility=\"visible\"/>" +
                "<g>" +
                "<rect fill=\"rgb(255,128,0)\" fill-opacity=\"1\" height=\"4\" width=\"4\" x=\"8\" y=\"10\"/>" +
                "<rect fill=\"rgb(0,0,255)\" fill-opacity=\"0.5\" height=\"1\" width=\"1\" x=\"19.5\" y=\"19.5\"/>" +
                "<rect fill=\"rgb(51,102,153)\" fill-opacity=\"0.75\" " +
                "height=\"8\" width=\"8\" x=\"26.25\" y=\"4.5\"/>" +
                "</g>" +
                "<path stroke-linecap=\"round\" stroke-linejoin=\"round\" visibility=\"hidden\"/>" +
                "<circle visibility=\"hidden\"/>" +
                "</svg>",
            root.markup(),
        )
    }

    @Test
    fun `dots emit a circle per cell`() {
        val (root, _) = draw(SurfaceStyle.Dots, SvgTint.PER_CELL, FRAME)

        assertEquals(
            listOf(
                "<circle cx=\"10\" cy=\"12\" fill=\"rgb(255,128,0)\" fill-opacity=\"1\" r=\"2\"/>",
                "<circle cx=\"20\" cy=\"20\" fill=\"rgb(0,0,255)\" fill-opacity=\"0.5\" r=\"0.5\"/>",
                "<circle cx=\"30.25\" cy=\"8.5\" fill=\"rgb(51,102,153)\" fill-opacity=\"0.75\" r=\"4\"/>",
            ),
            cells(root),
        )
    }

    @Test
    fun `dashes emit a round-capped line along the spin direction, a dot without one`() {
        val (root, _) = draw(SurfaceStyle.Dashes, SvgTint.PER_CELL, FRAME)

        val caps = "stroke-linecap=\"round\""
        assertEquals(
            listOf(
                "<line stroke=\"rgb(255,128,0)\" $caps stroke-opacity=\"1\" stroke-width=\"1.8\" " +
                    "x1=\"8.2\" x2=\"11.8\" y1=\"12\" y2=\"12\"/>",
                "<line stroke=\"rgb(0,0,255)\" $caps stroke-opacity=\"0.5\" stroke-width=\"0.45\" " +
                    "x1=\"20\" x2=\"20\" y1=\"20\" y2=\"20\"/>",
                "<line stroke=\"rgb(51,102,153)\" $caps stroke-opacity=\"0.75\" stroke-width=\"3.6\" " +
                    "x1=\"32.05\" x2=\"28.45\" y1=\"1.3\" y2=\"15.7\"/>",
            ),
            cells(root),
        )
    }

    @Test
    fun `current colour paints every cell and the halo in CSS color, luminance times alpha as opacity`() {
        val (root, _) = draw(SurfaceStyle.Dots, SvgTint.CURRENT_COLOR, FRAME)

        assertEquals(
            listOf(
                "<circle cx=\"10\" cy=\"12\" fill=\"currentColor\" fill-opacity=\"0.57\" r=\"2\"/>",
                "<circle cx=\"20\" cy=\"20\" fill=\"currentColor\" fill-opacity=\"0.04\" r=\"0.5\"/>",
                "<circle cx=\"30.25\" cy=\"8.5\" fill=\"currentColor\" fill-opacity=\"0.28\" r=\"4\"/>",
            ),
            cells(root),
        )
        assertEquals(listOf("currentColor", "currentColor"), root.all("stop").map { it.attributes["stop-color"] })
    }

    @Test
    fun `a still orb writes no attribute on its second frame`() {
        for (style in STYLES) {
            for (tint in SvgTint.entries) {
                val (root, surface) = draw(style, tint, FRAME)
                val document = root.document
                val before = document.writes

                surface.render(FRAME)
                surface.render(FRAME.copy(voxels = FRAME.voxels.map { it.copy() }))

                assertEquals(before, document.writes, "$style, $tint")
            }
        }
    }

    @Test
    fun `a live orb's unchanged frame costs nothing and an element per cell is made once`() {
        val live = LiveFrameSource(AtmospherePresets.THINKING).next(0.5f)
        val frame = PixelLattice(200, 200, voxelRadiusPx = 200 / 40f).project(live)
        val (root, surface) = draw(SurfaceStyle.Dots, SvgTint.PER_CELL, frame)
        val writes = root.document.writes

        surface.render(frame)

        assertEquals(frame.voxels.size, surface.elements)
        assertEquals(frame.voxels.size, root.all("g").single().children.size)
        assertEquals(writes, root.document.writes)
        assertTrue(frame.voxels.size > 1000, "a resolution-10 orb has thousands of cells")
    }

    @Test
    fun `surplus cells are hidden once and not rewritten while hidden`() {
        val (root, surface) = draw(SurfaceStyle.Dots, SvgTint.PER_CELL, FRAME)
        val circles = root.all("g").single().children
        val first = FRAME.voxels.first()

        surface.render(FRAME.copy(voxels = listOf(first)))
        assertEquals(listOf(null, "hidden", "hidden"), circles.map { it.attributes["visibility"] })
        val hiddenWrites = circles.drop(1).map { it.writes }

        surface.render(FRAME.copy(voxels = listOf(first.copy(screenX = 11f))))
        surface.render(FRAME.copy(voxels = listOf(first.copy(screenX = 12f))))
        assertEquals(hiddenWrites, circles.drop(1).map { it.writes })
        assertEquals("12", circles[0].attributes["cx"])

        // Shown again unchanged, each costs the one write that makes it visible.
        surface.render(FRAME.copy(voxels = listOf(first) + FRAME.voxels.drop(1)))
        assertEquals(hiddenWrites.map { it + 1 }, circles.drop(1).map { it.writes })
        assertEquals("visible", circles[1].attributes["visibility"])
    }

    @Test
    fun `every style draws a cell at least the minimum radius`() {
        val tiny = FRAME.copy(voxels = listOf(FRAME.voxels[1].copy(radiusPx = 0.1f)))

        assertEquals("0.45", cellAttribute(SurfaceStyle.Dots, tiny, "r"))
        assertEquals("0.9", cellAttribute(SurfaceStyle.Squares, tiny, "width"))
        assertEquals("0.41", cellAttribute(SurfaceStyle.Dashes, tiny, "stroke-width"))
    }

    @Test
    fun `an out-of-order frame is drawn back to front`() {
        val (root, _) = draw(SurfaceStyle.Dots, SvgTint.PER_CELL, FRAME.copy(voxels = FRAME.voxels.reversed()))

        assertEquals(listOf("10", "20", "30.25"), root.all("g").single().children.map { it.attributes["cx"] })
    }

    @Test
    fun `the view box follows the frame's size and is written once`() {
        val (root, surface) = draw(SurfaceStyle.Dots, SvgTint.PER_CELL, FRAME)
        val writes = root.writes

        surface.render(FRAME)
        assertEquals(writes, root.writes)

        surface.render(FRAME.copy(width = 80, height = 60))
        assertEquals("0 0 80 60", root.attributes["viewBox"])
        assertEquals("33", root.all("circle").first().attributes["r"])
    }

    @Test
    fun `no glow hides the halo, and no halo makes none`() {
        val (root, surface) = draw(SurfaceStyle.Dots, SvgTint.PER_CELL, FRAME)
        surface.render(FRAME.copy(ambient = FRAME.ambient.copy(glowIntensity = 0f)))
        assertEquals("hidden", root.all("circle").first().attributes["visibility"])

        val bare = FakeDocument().root()
        val plain = SvgOrbSurface(bare, bare.document, SurfaceStyle.Dots, SvgTint.PER_CELL, false, false, "orb")
        plain.render(FRAME)
        assertEquals(listOf("g"), bare.children.map { it.tag })
    }

    @Test
    fun `the check glyph is a stroked path at the glyph's colour, faded on the envelope`() {
        val glyph = VoxelGlyphState("CHECK", progress = 0.5f, red = 0.25f, green = 0.85f, blue = 0.45f)
        val (root, surface) = draw(SurfaceStyle.Dots, SvgTint.PER_CELL, FRAME.copy(glyph = glyph))
        val path = root.children.single { it.tag == "path" }

        assertEquals(
            "<path d=\"M14 20L18.8 24.8L26.24 14.96\" fill=\"none\" opacity=\"1\" stroke=\"rgb(64,217,115)\" " +
                "stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke-width=\"2.16\" visibility=\"visible\"/>",
            path.markup(),
        )
        assertEquals("hidden", root.children.last().attributes["visibility"], "a check has no dot")

        surface.render(FRAME.copy(glyph = glyph.copy(progress = 0.1f)))
        assertEquals("0.5", path.attributes["opacity"])

        surface.render(FRAME.copy(glyph = glyph.copy(progress = 1f)))
        assertEquals("hidden", path.attributes["visibility"])
    }

    @Test
    fun `exclaim and question carry a dot, heart and star are filled, and every glyph has a path`() {
        for (name in listOf("CHECK", "EXCLAIM", "QUESTION", "HEART", "STAR", "LIGHTNING")) {
            val glyph = VoxelGlyphState(name, progress = 0.5f, red = 1f, green = 1f, blue = 1f)
            val (root, _) = draw(SurfaceStyle.Dots, SvgTint.PER_CELL, FRAME.copy(glyph = glyph))
            val path = root.children.single { it.tag == "path" }
            val dot = root.children.last()

            assertTrue(path.attributes.getValue("d").startsWith("M"), name)
            val filled = name == "HEART" || name == "STAR"
            assertEquals(if (filled) "rgb(255,255,255)" else "none", path.attributes["fill"], name)
            val dotted = name == "EXCLAIM" || name == "QUESTION"
            assertEquals(if (dotted) "visible" else "hidden", dot.attributes["visibility"], name)
            if (dotted) {
                assertEquals(
                    "<circle cx=\"20\" cy=\"26.6\" fill=\"rgb(255,255,255)\" opacity=\"1\" r=\"1.08\" " +
                        "visibility=\"visible\"/>",
                    dot.markup(),
                )
            }
        }
    }

    @Test
    fun `an unknown glyph draws no overlay`() {
        val glyph = VoxelGlyphState("SPIRAL", progress = 0.5f, red = 1f, green = 1f, blue = 1f)
        val (root, _) = draw(SurfaceStyle.Dots, SvgTint.PER_CELL, FRAME.copy(glyph = glyph))

        assertNull(root.children.single { it.tag == "path" }.attributes["d"])
    }

    @Test
    fun `dispose takes out every element and the view box, and leaves the rest of the svg`() {
        val root = FakeDocument().root()
        val theirs = FakeNode("title", root.document)
        root.appendChild(theirs)
        val surface = SvgOrbSurface(root, root.document, SurfaceStyle.Dots, SvgTint.PER_CELL, id = "orb")
        surface.render(FRAME)

        surface.dispose()

        assertEquals(listOf(theirs), root.children)
        assertNull(root.attributes["viewBox"])
    }

    @Test
    fun `numbers are short, two decimals, no exponent`() {
        assertEquals(
            listOf("0", "8", "19.5", "-1.8", "0.05", "-0.05", "1234.57", "0", "0"),
            listOf(0f, 8f, 19.5f, -1.8f, 0.05f, -0.05f, 1234.567f, Float.NaN, 0.004f).map(::svgNumber),
        )
    }

    private fun draw(
        style: SurfaceStyle,
        tint: SvgTint,
        frame: LumosCanvasFrame,
    ): Pair<FakeNode, SvgOrbSurface> {
        val root = FakeDocument().root()
        val surface = SvgOrbSurface(root, root.document, style, tint, id = "orb")
        surface.render(frame)
        return root to surface
    }

    private fun cells(root: FakeNode): List<String> = root.all("g").single().children.map { it.markup() }

    private fun cellAttribute(
        style: SurfaceStyle,
        frame: LumosCanvasFrame,
        name: String,
    ): String? {
        val (root, _) = draw(style, SvgTint.PER_CELL, frame)
        return root.all("g").single().children.single().attributes[name]
    }

    private companion object {
        val STYLES = listOf<SurfaceStyle>(SurfaceStyle.Squares, SurfaceStyle.Dots, SurfaceStyle.Dashes)

        // Three cells back to front: a full one with a sideways spin, a half one with none, and a
        // big one spinning down and back.
        val FRAME =
            LumosCanvasFrame(
                width = 40,
                height = 40,
                voxels =
                    listOf(
                        CanvasVoxel(10f, 12f, 2f, 1f, 0.5f, 0f, 1f, z = -0.5f, dx = 0.5f, dy = 0f),
                        CanvasVoxel(20f, 20f, 0.5f, 0f, 0f, 1f, 0.5f, z = 0f),
                        CanvasVoxel(30.25f, 8.5f, 4f, 0.2f, 0.4f, 0.6f, 0.75f, z = 0.5f, dx = -0.25f, dy = 1f),
                    ),
                ambient =
                    VoxelAmbient(
                        glowRed = 0.5f,
                        glowGreen = 0.25f,
                        glowBlue = 1f,
                        glowIntensity = 0.75f,
                        orbRotationX = 0f,
                        orbRotationY = 0f,
                        orbRotationZ = 0f,
                    ),
                tick = 0L,
            )
    }
}
