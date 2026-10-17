package link.socket.phosphor.lumos.compose.material

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.compose.DotsPainter
import link.socket.phosphor.lumos.compose.SURFACE_PREVIEW_COLUMNS
import link.socket.phosphor.lumos.compose.SteppedScene
import link.socket.phosphor.lumos.compose.SurfacePainter
import link.socket.phosphor.lumos.counted
import link.socket.phosphor.lumos.compose.drawHalo
import link.socket.phosphor.lumos.compose.drawLumosFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame.CanvasVoxel
import link.socket.phosphor.lumos.compose.surfacePreviewFrames
import link.socket.phosphor.lumos.compose.surfacePreviewLattice
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.Oscilloscope
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode

/**
 * PHO-63 spike, desktop half. Renders metal and glass through Skia's CPU raster backend (the only
 * one a JVM test has), writes every render to `build/lumos-previews/material-*.png`, times the
 * per-cell and the batched (id-map) paths against flat Dots, and composes the canvas under each
 * [ShaderConstruction] to see whether building the effect at the wrong moment crashes.
 */
class MaterialShaderSpikeTest {
    @Test
    fun `metal and glass render at 200 px and 20 px on the IDLE and WORKING looks`() {
        val magnified = SMALL_PX * SMALL_SCALE
        val columnWidth = LARGE_PX + GAP + magnified
        val sheetWidth = GAP + SURFACE_PREVIEW_COLUMNS.size * (columnWidth + GAP)
        val sheetHeight = GAP + ROWS.size * (LARGE_PX + GAP)
        val frames = SURFACE_PREVIEW_COLUMNS.map { (atmosphere, look) -> surfacePreviewFrames(atmosphere, look) }
        val digests = mutableMapOf<String, String>()

        val sheet =
            render(sheetWidth, sheetHeight) {
                drawRect(SHEET_BACKGROUND)
                ROWS.forEachIndexed { row, (name, newPainter) ->
                    val top = GAP + row * (LARGE_PX + GAP)
                    frames.forEachIndexed { column, (large, small) ->
                        val left = GAP + column * (columnWidth + GAP)
                        val painter = newPainter()
                        inset(
                            left = left.toFloat(),
                            top = top.toFloat(),
                            right = (sheetWidth - left - LARGE_PX).toFloat(),
                            bottom = (sheetHeight - top - LARGE_PX).toFloat(),
                        ) {
                            drawLumosFrame(large, SHEET_BACKGROUND, showHalo = true, showGlyphOverlay = false, painter = painter)
                        }
                        val big = render(LARGE_PX) { drawLumosFrame(large, SHEET_BACKGROUND, true, false, newPainter()) }
                        val tiny = render(SMALL_PX) { drawLumosFrame(small, SHEET_BACKGROUND, true, false, newPainter()) }
                        save(big, "material-$name-$column-$LARGE_PX.png")
                        save(tiny, "material-$name-$column-$SMALL_PX.png")
                        digests["$name/$column/$LARGE_PX"] = digest(big)
                        digests["$name/$column/$SMALL_PX"] = digest(tiny)
                        assertTrue(big.inkedAnywhere(), "$name drew nothing at $LARGE_PX px in column $column")
                        assertTrue(tiny.inkedAnywhere(), "$name drew nothing at $SMALL_PX px in column $column")
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
        save(sheet, "material-contact-sheet.png")
        // Each material is its own picture, not Dots with a different name.
        for (column in SURFACE_PREVIEW_COLUMNS.indices) {
            assertNotEquals(digests["dots/$column/$LARGE_PX"], digests["metal/$column/$LARGE_PX"])
            assertNotEquals(digests["dots/$column/$LARGE_PX"], digests["glass/$column/$LARGE_PX"])
            assertNotEquals(digests["metal/$column/$LARGE_PX"], digests["glass/$column/$LARGE_PX"])
        }
    }

    @Test
    fun `the pointer uniform reaches both shaders and an unspecified pointer is inert`() {
        for (kind in MaterialKind.entries) {
            val none = render(SINGLE_PX) { drawSingle(kind, pointer = Offset.Unspecified) }
            val far = render(SINGLE_PX) { drawSingle(kind, pointer = Offset(-1e5f, -1e5f)) }
            val left = render(SINGLE_PX) { drawSingle(kind, pointer = Offset(4f, SINGLE_PX / 2f)) }
            val right = render(SINGLE_PX) { drawSingle(kind, pointer = Offset(SINGLE_PX - 4f, SINGLE_PX / 2f)) }
            save(none, "material-pointer-$kind-none.png")
            save(left, "material-pointer-$kind-left.png")
            save(right, "material-pointer-$kind-right.png")
            assertEquals(digest(none), digest(far), "$kind: a pointer far off-canvas should change nothing")
            assertNotEquals(digest(none), digest(left), "$kind: a pointer at the left edge should change the shading")
            assertNotEquals(digest(left), digest(right), "$kind: left and right pointers should differ")
        }
    }

    @Test
    fun `a platform without a runtime shader reports Unavailable and draws flat dots`() {
        val frame = idleFrame(LARGE_PX)
        val painter = MaterialPainter(MaterialKind.Metal, shaderFactory = { null })
        assertEquals(MaterialStatus.Unavailable, painter.status)
        val material = render(LARGE_PX) { drawLumosFrame(frame, SHEET_BACKGROUND, true, false, painter) }
        val dots = render(LARGE_PX) { drawLumosFrame(frame, SHEET_BACKGROUND, true, false, DotsPainter()) }
        assertEquals(digest(dots), digest(material), "the fallback should be Dots, byte for byte")

        val lazy = MaterialPainter(MaterialKind.Glass, lazyShader = true, shaderFactory = { null })
        assertEquals(MaterialStatus.Unknown, lazy.status, "a lazy painter does not know until it draws")
        render(LARGE_PX) { drawLumosFrame(frame, SHEET_BACKGROUND, true, false, lazy) }
        assertEquals(MaterialStatus.Unavailable, lazy.status)
    }

    @Test
    fun `every construction composes, draws three frames and reports Available`() {
        val frame = idleFrame(LARGE_PX)
        val report = StringBuilder()
        for (kind in MaterialKind.entries) {
            for (construction in ShaderConstruction.entries) {
                var status: MaterialStatus? = null
                val scene =
                    SteppedScene(width = LARGE_PX, height = LARGE_PX) {
                        LumosMaterialCanvas(
                            frame = frame,
                            kind = kind,
                            construction = construction,
                            modifier = Modifier.size(LARGE_PX.dp),
                            backgroundColor = SHEET_BACKGROUND,
                            onMaterialStatus = { status = it },
                        )
                    }
                try {
                    var image = scene.render()
                    repeat(2) { image = scene.renderNextFrame() }
                    val bitmap = image.toComposeImageBitmap()
                    save(bitmap, "material-construction-$kind-$construction.png")
                    assertTrue(bitmap.inkedAnywhere(), "$kind under $construction drew nothing")
                    assertEquals(MaterialStatus.Available, status, "$kind under $construction did not report Available")
                    report.append("| $kind | $construction | composed, 3 frames drawn, Available | no crash |\n")
                } finally {
                    scene.close()
                }
            }
        }
        println("[PHO-63] construction timing on desktop (Skia raster):\n$report")
        File(OUT_DIR).apply { mkdirs() }.resolve("material-constructions.md").writeText(report.toString())
    }

    @Test
    fun `reports draw time per approach at resolution 10 and at the inline orb's 80 cells`() {
        val scenarios =
            listOf(
                Scenario("resolution 10 @ 540 px", idleFrame(BENCH_PX), BENCH_PX),
                Scenario("80 cells @ 20 px", inlineFrame(SMALL_PX), SMALL_PX),
                Scenario("80 cells @ 200 px", inlineFrame(LARGE_PX), LARGE_PX),
            )
        val table = StringBuilder("| scenario | cells | approach | p50 ms | p95 ms |\n|---|---:|---|---:|---:|\n")
        for (scenario in scenarios) {
            val px = scenario.px
            val frame = scenario.frame
            val dots = DotsPainter()
            val metal = MaterialPainter(MaterialKind.Metal)
            val glass = MaterialPainter(MaterialKind.Glass, backdrop = canvasBackdrop(SHEET_BACKGROUND, true))
            val batched = IdMapMetalRenderer(px, px)
            val approaches: List<Pair<String, DrawScope.() -> Unit>> =
                listOf(
                    approach("Dots (flat)") { drawLumosFrame(frame, SHEET_BACKGROUND, true, false, dots) },
                    approach("Metal, per cell") { drawLumosFrame(frame, SHEET_BACKGROUND, true, false, metal) },
                    approach("Glass, per cell") { drawLumosFrame(frame, SHEET_BACKGROUND, true, false, glass) },
                    approach("Metal, id-map batched") {
                        drawRect(SHEET_BACKGROUND)
                        drawHalo(frame.ambient)
                        batched.draw(this, frame)
                    },
                )
            val bitmap = ImageBitmap(px, px)
            val canvas = Canvas(bitmap)
            val scope = CanvasDrawScope()
            for ((name, draw) in approaches) {
                val ms =
                    (0 until BENCH_ITERATIONS).map {
                        val t = System.nanoTime()
                        scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(px.toFloat(), px.toFloat()), draw)
                        (System.nanoTime() - t) / 1e6
                    }.drop(BENCH_WARM_UP).sorted()
                val p50 = "%.2f".format(ms[ms.size / 2])
                val p95 = "%.2f".format(ms[ms.size * 95 / 100])
                table.append("| ${scenario.name} | ${frame.voxels.size} | $name | $p50 | $p95 |\n")
                if (name.startsWith("Metal, id-map")) save(bitmap, "material-batched-${px}.png")
            }
        }
        println("[PHO-63] DRAW on desktop, Skia CPU raster, ${BENCH_ITERATIONS - BENCH_WARM_UP} timed frames:\n$table")
        File(OUT_DIR).apply { mkdirs() }.resolve("material-bench.md").writeText(table.toString())
    }

    @Test
    fun `the batched id-map path draws the same cells as the per-cell path`() {
        val frame = idleFrame(LARGE_PX)
        val perCell = render(LARGE_PX) { drawLumosFrame(frame, SHEET_BACKGROUND, false, false, MaterialPainter(MaterialKind.Metal)) }
        val batched =
            render(LARGE_PX) {
                drawRect(SHEET_BACKGROUND)
                IdMapMetalRenderer(LARGE_PX, LARGE_PX).draw(this, frame)
            }
        save(perCell, "material-metal-percell-$LARGE_PX.png")
        save(batched, "material-metal-batched-$LARGE_PX.png")
        // Same coverage to within the anti-aliased rim: count pixels brighter than the background.
        val a = perCell.litCount()
        val b = batched.litCount()
        println("[PHO-63] lit pixels: per-cell $a, batched $b")
        assertTrue(b in (a * 85 / 100)..(a * 115 / 100), "batched lit $b vs per-cell $a")
    }

    private fun approach(
        name: String,
        draw: DrawScope.() -> Unit,
    ): Pair<String, DrawScope.() -> Unit> = name to draw

    // --- the batched path ------------------------------------------------------------------

    /**
     * Question 3's second option: every cell in one `drawRect`. The CPU stamps each cell's index
     * into a canvas-sized id map in painter's order (later cells overwrite, which is exactly the
     * z-order a per-cell painter gets for free) and packs the cells' centres, radii and tints into
     * an N × 3 byte image; the fragment shader reads its cell through the id and shades the disc.
     * Two image uploads per frame; no instance id and no texture atlas needed.
     */
    private class IdMapMetalRenderer(
        private val width: Int,
        private val height: Int,
    ) {
        private val effect = RuntimeEffect.makeForShader(MaterialSksl.METAL_BATCHED)
        private val builder = RuntimeShaderBuilder(effect)
        private val idBytes = ByteArray(width * height * 4).also { for (i in 3 until it.size step 4) it[i] = OPAQUE }

        fun draw(
            scope: DrawScope,
            frame: LumosCanvasFrame,
            time: Float = frame.tick / 60f,
        ) {
            val voxels = frame.voxels
            val n = voxels.size
            require(n < 65536)
            for (i in idBytes.indices step 4) {
                idBytes[i] = 0
                idBytes[i + 1] = 0
            }
            val cellBytes = ByteArray(n * 3 * 4)
            for (i in 0 until n) {
                val v = voxels[i]
                val r = max(v.radiusPx, SurfaceStyle.MIN_RADIUS_PX)
                val cx16 = (v.screenX * 16f).roundToInt().coerceIn(0, 65535)
                val cy16 = (v.screenY * 16f).roundToInt().coerceIn(0, 65535)
                var o = i * 4
                cellBytes[o] = (cx16 shr 8).toByte()
                cellBytes[o + 1] = (cx16 and 255).toByte()
                cellBytes[o + 2] = (cy16 shr 8).toByte()
                cellBytes[o + 3] = OPAQUE
                o = (n + i) * 4
                cellBytes[o] = (cy16 and 255).toByte()
                cellBytes[o + 1] = (r * 4f).roundToInt().coerceIn(0, 255).toByte()
                cellBytes[o + 2] = (v.alpha * 255f).roundToInt().coerceIn(0, 255).toByte()
                cellBytes[o + 3] = OPAQUE
                o = (2 * n + i) * 4
                cellBytes[o] = (v.red * 255f).roundToInt().coerceIn(0, 255).toByte()
                cellBytes[o + 1] = (v.green * 255f).roundToInt().coerceIn(0, 255).toByte()
                cellBytes[o + 2] = (v.blue * 255f).roundToInt().coerceIn(0, 255).toByte()
                cellBytes[o + 3] = OPAQUE

                val id = i + 1
                val lo = (id and 255).toByte()
                val hi = (id shr 8).toByte()
                val rr = (r + 0.5f) * (r + 0.5f)
                val y0 = max(0, floor(v.screenY - r - 1f).toInt())
                val y1 = min(height - 1, ceil(v.screenY + r + 1f).toInt())
                val x0 = max(0, floor(v.screenX - r - 1f).toInt())
                val x1 = min(width - 1, ceil(v.screenX + r + 1f).toInt())
                for (y in y0..y1) {
                    val dy = y + 0.5f - v.screenY
                    for (x in x0..x1) {
                        val dx = x + 0.5f - v.screenX
                        if (dx * dx + dy * dy <= rr) {
                            val idx = (y * width + x) * 4
                            idBytes[idx] = lo
                            idBytes[idx + 1] = hi
                        }
                    }
                }
            }
            val idImage = Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), idBytes, width * 4)
            val cellImage = Image.makeRaster(ImageInfo(n, 3, ColorType.RGBA_8888, ColorAlphaType.OPAQUE), cellBytes, n * 4)
            val idShader = idImage.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.DEFAULT, null)
            val cellShader = cellImage.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.DEFAULT, null)
            builder.child("idMap", idShader)
            builder.child("cells", cellShader)
            builder.uniform("time", time)
            builder.uniform("lightAngle", MaterialSksl.DEFAULT_LIGHT_ANGLE)
            builder.uniform("pointer", MaterialCell.NO_POINTER, MaterialCell.NO_POINTER)
            builder.uniform("roughness", MaterialSksl.DEFAULT_ROUGHNESS)
            builder.uniform("chromatic", MaterialSksl.DEFAULT_CHROMATIC)
            val shader = builder.makeShader(null)
            scope.drawRect(brush = ShaderBrush(shader))
            shader.close()
            idShader.close()
            cellShader.close()
            idImage.close()
            cellImage.close()
        }

        private companion object {
            const val OPAQUE: Byte = 0xFF.toByte()
        }
    }

    // --- frames ----------------------------------------------------------------------------

    private class Scenario(
        val name: String,
        val frame: LumosCanvasFrame,
        val px: Int,
    )

    // The first frame of a short seeded capture, as PHO-61's snapshot test draws it.
    private fun idleFrame(px: Int): LumosCanvasFrame {
        val trace =
            Oscilloscope.capture(
                CaptureConfig(CaptureParams(atmosphere = AtmospherePresets.IDLE), seed = SEED, durationMs = 100L, fps = 20),
            ).getOrThrow()
        val frame = trace.toFrames().first()
        return surfacePreviewLattice(px, frame.resolution).project(frame)
    }

    // The inline orb: Forms.Fibonacci at a fixed 80 cells, as a 20 px host runs it.
    private fun inlineFrame(px: Int): LumosCanvasFrame {
        val frame =
            LiveFrameSource(AtmospherePresets.IDLE)
                .also {
                    it.builder.form = Forms.Fibonacci.counted(INLINE_CELLS)
                    it.builder.look = Look(LumosLook.BASE)
                }.next(WARM_UP_SECONDS)
        return surfacePreviewLattice(px, frame.resolution).project(frame)
    }

    private fun DrawScope.drawSingle(
        kind: MaterialKind,
        pointer: Offset,
    ) {
        val c = SINGLE_PX / 2f
        val voxel = CanvasVoxel(c, c, SINGLE_RADIUS, red = 0.35f, green = 0.45f, blue = 0.95f, alpha = 1f, z = 0f)
        val frame = LumosCanvasFrame(SINGLE_PX, SINGLE_PX, listOf(voxel), NO_AMBIENT, null, tick = 0L)
        val painter = MaterialPainter(kind, backdrop = canvasBackdrop(SHEET_BACKGROUND, false)).also { it.pointer = pointer }
        drawLumosFrame(frame, SHEET_BACKGROUND, showHalo = false, showGlyphOverlay = false, painter = painter)
        painter.close()
    }

    // --- rendering -------------------------------------------------------------------------

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

    private fun ImageBitmap.inkedAnywhere(): Boolean {
        val pixels = IntArray(width * height).also(::readPixels)
        val bg = SHEET_BACKGROUND_ARGB
        return pixels.any { it != bg }
    }

    private fun ImageBitmap.litCount(): Int {
        val pixels = IntArray(width * height).also(::readPixels)
        return pixels.count { ((it shr 16) and 0xff) + ((it shr 8) and 0xff) + (it and 0xff) > 3 * 0x11 + 24 }
    }

    private fun save(
        bitmap: ImageBitmap,
        name: String,
    ) {
        val image = Image.makeFromBitmap(bitmap.asSkiaBitmap())
        val bytes = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
        File(OUT_DIR).apply { mkdirs() }.resolve(name).writeBytes(bytes)
    }

    private companion object {
        const val OUT_DIR = "build/lumos-previews"
        const val SEED = 63L
        const val LARGE_PX = 200
        const val SMALL_PX = 20
        const val SMALL_SCALE = 4
        const val GAP = 16
        const val SINGLE_PX = 160
        const val SINGLE_RADIUS = 60f
        const val BENCH_PX = 540
        const val BENCH_ITERATIONS = 300
        const val BENCH_WARM_UP = 100
        const val INLINE_CELLS = 80
        const val WARM_UP_SECONDS = 2.3f
        val SHEET_BACKGROUND = Color(0xFF111111)
        const val SHEET_BACKGROUND_ARGB = 0xFF111111.toInt()
        val NO_AMBIENT = VoxelAmbient(0f, 0f, 0f, 0f, 0f, 0f, 0f)

        val ROWS: List<Pair<String, () -> SurfacePainter>> =
            listOf(
                "dots" to { DotsPainter() },
                "metal" to { MaterialPainter(MaterialKind.Metal) },
                "glass" to { MaterialPainter(MaterialKind.Glass, backdrop = canvasBackdrop(SHEET_BACKGROUND, true)) },
            )
    }
}
