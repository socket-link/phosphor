package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.Material
import link.socket.phosphor.lumos.MaterialStatus
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame.CanvasVoxel
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.Oscilloscope
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * PHO-62 Task A: the material seam and the Neon pass construction, held to behaviour rather than
 * to pinned fingerprints — those come with Task D once the construction has its visual verdict.
 * Every render is written to `build/lumos-previews/`: a contact sheet of the materials over Dots
 * and Squares on the IDLE and WORKING looks, and a side-by-side of Neon's halo constructions.
 */
class MaterialSnapshotTest {
    @Test
    fun `flat draws exactly what the style drew before materials existed`() {
        for (style in STYLES) {
            val bare = render(LARGE_PX) { drawMaterial(idleFrame(LARGE_PX), style, null) }
            val flat = render(LARGE_PX) { drawMaterial(idleFrame(LARGE_PX), style, Material.Flat) }
            assertEquals(digest(bare), digest(flat), "${style.name()} under Flat differs from the bare style")
        }
    }

    @Test
    fun `metal and glass draw as flat and report unavailable`() {
        val flat = digest(render(LARGE_PX) { drawMaterial(idleFrame(LARGE_PX), SurfaceStyle.Dots, Material.Flat) })
        for (material in listOf(Material.Metal(), Material.Glass())) {
            val drawn = digest(render(LARGE_PX) { drawMaterial(idleFrame(LARGE_PX), SurfaceStyle.Dots, material) })
            assertEquals(flat, drawn, "$material should draw as Flat")
            assertEquals(MaterialStatus.Unavailable, material.canvasStatus())
        }
        for (material in listOf(Material.Flat, Material.Ink(), Material.Neon())) {
            assertEquals(MaterialStatus.Ready, material.canvasStatus())
        }
    }

    @Test
    fun `ink is deterministic and differs from flat on every style`() {
        for (style in STYLES) {
            val once = digest(render(LARGE_PX) { drawMaterial(idleFrame(LARGE_PX), style, Material.Ink()) })
            val twice = digest(render(LARGE_PX) { drawMaterial(idleFrame(LARGE_PX), style, Material.Ink()) })
            val flat = digest(render(LARGE_PX) { drawMaterial(idleFrame(LARGE_PX), style, Material.Flat) })
            assertEquals(once, twice, "${style.name()} under Ink should draw the same grain every frame")
            assertNotEquals(flat, once, "${style.name()} under Ink should not look flat")
        }
    }

    @Test
    fun `ink's soft edge reaches past the cell and its core stays inside it`() {
        val c = SINGLE_PX / 2
        val r = SINGLE_RADIUS.toInt()
        val flat = render(SINGLE_PX) { drawSingle(SurfaceStyle.Dots, Material.Flat) }
        val ink = render(SINGLE_PX) { drawSingle(SurfaceStyle.Dots, Material.Ink(grain = 0f)) }
        assertTrue(!flat.inked(c + r, c), "a flat cell should end at its radius")
        assertTrue(ink.inked(c + r, c), "an ink cell's soft edge should reach past its radius")
        assertTrue(ink.alphaAt(c + r, c) < ink.alphaAt(c, c) / 2, "the soft edge should be fainter than the core")
        assertTrue(ink.alphaAt(c, c) >= 250, "at grain 0 the core should be opaque")
    }

    @Test
    fun `ink grain lowers alpha and moves radius by at most its jitter`() {
        // Over many cells at grain 1, alpha falls by up to half and radius wanders by up to a fifth.
        val frame = idleFrame(LARGE_PX)
        val recorder = RecordingPainter()
        val painter = Material.Ink(grain = 1f).painter(recorder)
        render(LARGE_PX) { drawVoxels(frame, painter) }
        assertEquals(frame.voxels.size * 2, recorder.cells.size, "ink should draw every cell twice")
        val cores = recorder.cells.filterIndexed { i, _ -> i % 2 == 1 }
        for ((i, core) in cores.withIndex()) {
            val voxel = frame.voxels[i]
            val base = maxOf(voxel.radiusPx, SurfaceStyle.MIN_RADIUS_PX)
            val minR = base * (1f - Material.INK_RADIUS_JITTER) * Material.INK_CORE
            val maxR = base * (1f + Material.INK_RADIUS_JITTER) * Material.INK_CORE
            assertTrue(core.radius in minR..maxR, "cell $i core radius ${core.radius} outside $minR..$maxR")
            assertTrue(core.color.alpha <= voxel.alpha + 1e-4f, "cell $i core alpha rose")
            val floor = voxel.alpha * (1f - Material.INK_ALPHA_JITTER) - 1e-4f
            assertTrue(core.color.alpha >= floor, "cell $i core alpha fell too far")
        }
        assertTrue(cores.map { it.radius }.distinct().size > cores.size / 4, "grain should vary across cells")
    }

    @Test
    fun `neon's halo reaches past the cell, its filament is white, and its passes are layered`() {
        val c = SINGLE_PX / 2
        val glow = 0.5f
        val haloReach = (SINGLE_RADIUS * (1f + Material.NEON_HALO_SPREAD * glow) * 0.8f).toInt()
        val envelopeReach = (SINGLE_RADIUS * (1f + Material.NEON_ENVELOPE_SPREAD * glow) * 0.9f).toInt()
        for (halo in listOf(NeonHalo.Stepped, NeonHalo.Gradient)) {
            val neon = render(SINGLE_PX) { drawSingle(SurfaceStyle.Dots, Material.Neon(glow), halo, blue()) }
            assertTrue(neon.inked(c + haloReach, c), "$halo halo should reach 2 × glow radii out")
            assertTrue(!neon.inked(c + haloReach + 4, c), "$halo halo should stop at its radius")
            assertTrue(
                neon.alphaAt(c + envelopeReach, c) > neon.alphaAt(c + haloReach, c),
                "the envelope should be denser than the halo",
            )
            assertTrue(
                neon.redAt(c, c) > 200 && neon.greenAt(c, c) > 200,
                "$halo filament should be white at the centre",
            )
            assertTrue(neon.redAt(c + envelopeReach, c) < 80, "$halo envelope should keep the tint")
        }
        val none = render(SINGLE_PX) { drawSingle(SurfaceStyle.Dots, Material.Neon(glow), NeonHalo.None, blue()) }
        assertTrue(!none.inked(c + haloReach, c), "the two-pass fallback should have no halo")
        assertTrue(none.inked(c + envelopeReach, c), "the two-pass fallback should keep the envelope")

        // Two cells, back then front: the front one's filament sits on top of the back one's halo,
        // and the back one's filament is still white because no halo is drawn after any filament.
        val recorder = RecordingPainter()
        val painter = Material.Neon(glow).painter(recorder)
        render(SINGLE_PX) {
            drawVoxels(
                LumosCanvasFrame(
                    SINGLE_PX,
                    SINGLE_PX,
                    listOf(blue().copy(screenX = 20f, z = -1f), blue().copy(screenX = 40f, z = 1f)),
                    NO_AMBIENT,
                    null,
                    tick = 0L,
                ),
                painter,
            )
        }
        assertEquals(listOf(20f, 40f, 20f, 40f), recorder.cells.map { it.x }, "envelopes for both, then filaments")
        assertEquals(
            listOf(false, false, true, true),
            recorder.cells.map { it.color.red > 0.99f },
            "filaments are white, envelopes tinted",
        )
    }

    @Test
    fun `neon adds where halos overlap`() {
        val one = render(SINGLE_PX) { drawSingle(SurfaceStyle.Dots, Material.Neon(1f), NeonHalo.Stepped, blue()) }
        val two =
            render(SINGLE_PX) {
                drawVoxels(
                    LumosCanvasFrame(
                        SINGLE_PX,
                        SINGLE_PX,
                        listOf(blue().copy(screenX = SINGLE_PX / 2f - 8f), blue().copy(screenX = SINGLE_PX / 2f + 8f)),
                        NO_AMBIENT,
                        null,
                        tick = 0L,
                    ),
                    Material.Neon(1f).painter(SurfaceStyle.Dots.painter()),
                )
            }
        val probeX = SINGLE_PX / 2
        val probeY = SINGLE_PX / 2 + 22
        assertTrue(one.alphaAt(probeX, probeY) > 0, "the probe should lie in one cell's halo")
        assertTrue(two.alphaAt(probeX, probeY) > one.alphaAt(probeX, probeY), "two halos should be brighter than one")
    }

    @Test
    fun `neon lifts the orb's ambient halo by its glow`() {
        // The ambient alone, so no voxel sits over the probe.
        val frame = idleFrame(LARGE_PX).copy(voxels = emptyList())
        val flat = render(LARGE_PX) { drawAmbient(frame, haloBoost = 0f) }
        val lifted = render(LARGE_PX) { drawAmbient(frame, haloBoost = 1f) }
        val c = LARGE_PX / 2
        assertTrue(flat.luminanceAt(c, c) > SHEET_BACKGROUND_LUMINANCE, "the ambient should show over the background")
        assertTrue(lifted.luminanceAt(c, c) > flat.luminanceAt(c, c), "a boosted ambient should brighten")
        assertEquals(0f, Material.Flat.haloBoost())
        assertEquals(0.5f, Material.Neon(0.5f).haloBoost())
    }

    @Test
    fun `the canvas tells a listener once whether it draws the material`() {
        val heard = mutableListOf<Pair<Material, MaterialStatus>>()
        val frame = idleFrame(SMALL_PX)
        val scene =
            SteppedScene(width = SMALL_PX, height = SMALL_PX) {
                LumosCanvas(
                    frame = frame,
                    modifier = Modifier.size(SMALL_PX.dp),
                    material = Material.Glass(),
                    onMaterialStatus = { material, status -> heard += material to status },
                )
            }
        try {
            scene.render()
            scene.renderNextFrame()
        } finally {
            scene.close()
        }
        assertEquals(listOf<Pair<Material, MaterialStatus>>(Material.Glass() to MaterialStatus.Unavailable), heard)
    }

    @Test
    fun `contact sheet of every material over dots and squares on the IDLE and WORKING looks`() {
        // Rows: Flat, Ink, Neon (stepped halo), Neon (gradient halo). Columns per (style, preset): the
        // 200 px render, then the 20 px render blown up 4× unfiltered, as the style sheet lays it out.
        val magnified = SMALL_PX * SMALL_SCALE
        val columnWidth = LARGE_PX + GAP + magnified
        val columns =
            SHEET_STYLES.flatMap { style ->
                SURFACE_PREVIEW_COLUMNS.map { (atmosphere, look) -> style to surfacePreviewFrames(atmosphere, look) }
            }
        val sheetWidth = GAP + columns.size * (columnWidth + GAP)
        val sheetHeight = GAP + SHEET_ROWS.size * (LARGE_PX + GAP)
        val sheet =
            render(sheetWidth, sheetHeight) {
                drawRect(SHEET_BACKGROUND)
                SHEET_ROWS.forEachIndexed { row, (material, halo) ->
                    val top = GAP + row * (LARGE_PX + GAP)
                    columns.forEachIndexed { column, (style, frames) ->
                        val (large, small) = frames
                        val left = GAP + column * (columnWidth + GAP)
                        inset(
                            left = left.toFloat(),
                            top = top.toFloat(),
                            right = (sheetWidth - left - LARGE_PX).toFloat(),
                            bottom = (sheetHeight - top - LARGE_PX).toFloat(),
                        ) {
                            drawMaterial(large, style, material, halo, showHalo = true)
                        }
                        val tiny = render(SMALL_PX) { drawMaterial(small, style, material, halo, showHalo = true) }
                        drawImage(
                            image = tiny,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(SMALL_PX, SMALL_PX),
                            dstOffset = IntOffset(left + LARGE_PX + GAP, top + (LARGE_PX - magnified) / 2),
                            dstSize = IntSize(magnified, magnified),
                            filterQuality = FilterQuality.None,
                        )
                        drawImage(
                            image = tiny,
                            topLeft =
                                Offset(
                                    (left + LARGE_PX + GAP).toFloat(),
                                    (top + (LARGE_PX + magnified) / 2 + GAP).toFloat(),
                                ),
                        )
                    }
                }
            }
        save(sheet, "materials-contact-sheet.png")
    }

    @Test
    fun `side by side of neon's halo constructions and glow on dots at 200 px`() {
        // Columns: Flat, Neon with no halo (two passes), stepped halo, gradient halo; rows: glow 0.25, 0.5, 1.
        val columns =
            listOf<Pair<String, (Float) -> Pair<Material?, NeonHalo>>>(
                "flat" to { _ -> Material.Flat to NeonHalo.None },
                "two-pass" to { g -> Material.Neon(g) to NeonHalo.None },
                "stepped" to { g -> Material.Neon(g) to NeonHalo.Stepped },
                "gradient" to { g -> Material.Neon(g) to NeonHalo.Gradient },
            )
        val glows = listOf(0.25f, 0.5f, 1f)
        val (large, _) = surfacePreviewFrames(AtmospherePresets.THINKING, LumosLook.WORKING)
        val sheetWidth = GAP + columns.size * (LARGE_PX + GAP)
        val sheetHeight = GAP + glows.size * (LARGE_PX + GAP)
        val sheet =
            render(sheetWidth, sheetHeight) {
                drawRect(SHEET_BACKGROUND)
                glows.forEachIndexed { row, glow ->
                    val top = GAP + row * (LARGE_PX + GAP)
                    columns.forEachIndexed { column, (_, pick) ->
                        val (material, halo) = pick(glow)
                        val left = GAP + column * (LARGE_PX + GAP)
                        inset(
                            left = left.toFloat(),
                            top = top.toFloat(),
                            right = (sheetWidth - left - LARGE_PX).toFloat(),
                            bottom = (sheetHeight - top - LARGE_PX).toFloat(),
                        ) {
                            drawMaterial(large, SurfaceStyle.Dots, material, halo, showHalo = true)
                        }
                    }
                }
            }
        save(sheet, "neon-passes-side-by-side.png")
    }

    @Test
    fun `reports draw time per material at resolution 10`() {
        val frame = idleFrame(BENCH_PX)
        val bitmap = ImageBitmap(BENCH_PX, BENCH_PX)
        val canvas = Canvas(bitmap)
        val dots = SurfaceStyle.Dots
        val squares = SurfaceStyle.Squares
        val cases =
            listOf<Pair<String, SurfacePainter>>(
                "flat/dots" to dots.painter(),
                "ink/dots" to Material.Ink().painter(dots.painter()),
                "neon-two-pass/dots" to NeonPainter(dots.painter(), Material.Neon(), NeonHalo.None),
                "neon-stepped/dots" to NeonPainter(dots.painter(), Material.Neon(), NeonHalo.Stepped),
                "neon-gradient/dots" to NeonPainter(dots.painter(), Material.Neon(), NeonHalo.Gradient),
                "neon-stepped/squares" to NeonPainter(squares.painter(), Material.Neon(), NeonHalo.Stepped),
                "ink/squares" to Material.Ink().painter(squares.painter()),
            )
        for ((name, painter) in cases) {
            val scope = CanvasDrawScope()
            val ms =
                (0 until BENCH_ITERATIONS).map {
                    val t = System.nanoTime()
                    scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(BENCH_PX.toFloat(), BENCH_PX.toFloat())) {
                        drawVoxels(frame, painter)
                    }
                    (System.nanoTime() - t) / 1e6
                }.drop(BENCH_WARM_UP).sorted()
            println(
                "[PHO-62] $name: ${frame.voxels.size} voxels at $BENCH_PX px, " +
                    "DRAW p50=${"%.2f".format(ms[ms.size / 2])} ms p95=${"%.2f".format(ms[ms.size * 95 / 100])} ms",
            )
        }
    }

    // --- rendering -------------------------------------------------------------------------

    private fun DrawScope.drawMaterial(
        frame: LumosCanvasFrame,
        style: SurfaceStyle,
        material: Material?,
        halo: NeonHalo = NeonHalo.Stepped,
        showHalo: Boolean = false,
    ) {
        val inner = style.painter()
        val painter =
            when (material) {
                null -> inner
                is Material.Neon -> NeonPainter(inner, material, halo)
                else -> material.painter(inner)
            }
        drawLumosFrame(
            frame,
            SHEET_BACKGROUND,
            showHalo = showHalo,
            showGlyphOverlay = false,
            painter,
            material?.haloBoost() ?: 0f,
        )
    }

    private fun DrawScope.drawAmbient(
        frame: LumosCanvasFrame,
        haloBoost: Float,
    ) = drawLumosFrame(
        frame,
        SHEET_BACKGROUND,
        showHalo = true,
        showGlyphOverlay = false,
        SurfaceStyle.Dots.painter(),
        haloBoost,
    )

    private fun DrawScope.drawSingle(
        style: SurfaceStyle,
        material: Material,
        halo: NeonHalo = NeonHalo.Stepped,
        voxel: CanvasVoxel = white(),
    ) {
        val inner = style.painter()
        val painter = if (material is Material.Neon) NeonPainter(inner, material, halo) else material.painter(inner)
        drawVoxels(LumosCanvasFrame(SINGLE_PX, SINGLE_PX, listOf(voxel), NO_AMBIENT, null, tick = 0L), painter)
    }

    private fun white(): CanvasVoxel {
        val c = SINGLE_PX / 2f
        return CanvasVoxel(c, c, SINGLE_RADIUS, red = 1f, green = 1f, blue = 1f, alpha = 1f, z = 0f)
    }

    private fun blue(): CanvasVoxel = white().copy(red = 0f, green = 0f)

    private fun idleFrame(px: Int): LumosCanvasFrame = capturedFrame(AtmospherePresets.IDLE, px)

    // The first frame of a short seeded capture: the same voxels every run, on every machine.
    private fun capturedFrame(
        atmosphere: AtmosphereState,
        px: Int,
    ): LumosCanvasFrame {
        val trace =
            Oscilloscope.capture(
                CaptureConfig(CaptureParams(atmosphere = atmosphere), seed = SEED, durationMs = 100L, fps = 20),
            ).getOrThrow()
        val frame = trace.toFrames().first()
        return surfacePreviewLattice(px, frame.resolution).project(frame)
    }

    private fun render(
        px: Int,
        draw: DrawScope.() -> Unit,
    ): ImageBitmap = render(px, px, draw)

    private fun render(
        width: Int,
        height: Int,
        draw: DrawScope.() -> Unit,
    ): ImageBitmap {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(width.toFloat(), height.toFloat()),
            block = draw,
        )
        return bitmap
    }

    /** A painter that records every cell it is handed, for tests of what a material asks the style to draw. */
    private class RecordingPainter : SurfacePainter {
        data class Cell(
            val index: Int,
            val x: Float,
            val y: Float,
            val radius: Float,
            val color: Color,
        )

        val cells = mutableListOf<Cell>()

        override fun begin(
            scope: DrawScope,
            frame: LumosCanvasFrame,
        ) = Unit

        override fun cell(
            index: Int,
            screenX: Float,
            screenY: Float,
            radiusPx: Float,
            rgba: Color,
            z: Float,
            dx: Float,
            dy: Float,
        ) {
            cells += Cell(index, screenX, screenY, radiusPx, rgba)
        }

        override fun end() = Unit
    }

    // --- pixels ----------------------------------------------------------------------------

    // FNV-1a over the pixels: exact, for the record.
    private fun digest(bitmap: ImageBitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height).also(bitmap::readPixels)
        var h = -0x340d631b7bdddcdbL
        for (p in pixels) {
            for (shift in 0..24 step 8) {
                h = (h xor ((p shr shift) and 0xff).toLong()) * 0x100000001b3L
            }
        }
        return h.toULong().toString(16).padStart(16, '0')
    }

    private fun ImageBitmap.argbAt(
        x: Int,
        y: Int,
    ): Int = asSkiaBitmap().getColor(x, y)

    private fun ImageBitmap.alphaAt(
        x: Int,
        y: Int,
    ): Int = (argbAt(x, y) ushr 24) and 0xff

    private fun ImageBitmap.redAt(
        x: Int,
        y: Int,
    ): Int = (argbAt(x, y) shr 16) and 0xff

    private fun ImageBitmap.greenAt(
        x: Int,
        y: Int,
    ): Int = (argbAt(x, y) shr 8) and 0xff

    private fun ImageBitmap.blueAt(
        x: Int,
        y: Int,
    ): Int = argbAt(x, y) and 0xff

    private fun ImageBitmap.luminanceAt(
        x: Int,
        y: Int,
    ): Int = (299 * redAt(x, y) + 587 * greenAt(x, y) + 114 * blueAt(x, y)) / 1000

    private fun ImageBitmap.inked(
        x: Int,
        y: Int,
    ): Boolean = alphaAt(x, y) > 0

    private fun save(
        bitmap: ImageBitmap,
        name: String,
    ) {
        val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
        val bytes = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
        File("build/lumos-previews").apply { mkdirs() }.resolve(name).writeBytes(bytes)
    }

    private fun SurfaceStyle.name(): String = this::class.simpleName!!.lowercase()

    private companion object {
        const val SEED = 62L
        const val LARGE_PX = 200
        const val SMALL_PX = 20
        const val SMALL_SCALE = 4
        const val GAP = 16
        const val SINGLE_PX = 96
        const val SINGLE_RADIUS = 10f
        const val BENCH_PX = 540
        const val BENCH_ITERATIONS = 300
        const val BENCH_WARM_UP = 100
        val SHEET_BACKGROUND = Color(0xFF111111)
        const val SHEET_BACKGROUND_LUMINANCE = 0x11
        val NO_AMBIENT = VoxelAmbient(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val STYLES = listOf(SurfaceStyle.Dots, SurfaceStyle.Squares, SurfaceStyle.Dashes)
        val SHEET_STYLES = listOf(SurfaceStyle.Dots, SurfaceStyle.Squares)
        val SHEET_ROWS: List<Pair<Material, NeonHalo>> =
            listOf(
                Material.Flat to NeonHalo.None,
                Material.Ink() to NeonHalo.None,
                Material.Neon() to NeonHalo.Stepped,
                Material.Neon() to NeonHalo.Gradient,
            )
    }
}
