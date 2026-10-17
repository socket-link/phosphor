package link.socket.phosphor.lumos.compose

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
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
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
 * PHO-61: each [SurfaceStyle] is a pure function of the frame. One known frame — the first of a
 * seeded IDLE capture — is drawn in every style at 200 px and at 20 px and held to a checked-in
 * snapshot.
 *
 * The snapshot is a text fingerprint, not a PNG: the render is cut into cells (10 px at 200 px,
 * 2 px at 20 px), each cell's mean luminance is one hex digit, and a cell may differ from the
 * pin by one digit, so a rasteriser's anti-aliasing can differ between the Mac that pinned these
 * and the Linux runner that checks them without the test turning on the OS. The exact pixel
 * digest is printed beside it, for the record. Every render is also written to
 * `build/lumos-previews/`, with a contact sheet of the styles at both sizes on the preview's
 * columns: an IDLE orb in the BASE look and a THINKING orb in the WORKING look.
 */
class SurfaceStyleSnapshotTest {
    @Test
    fun `every style matches its fingerprint at 200 px and at 20 px`() {
        val failures = mutableListOf<String>()
        for (style in SURFACE_PREVIEW_STYLES) {
            for ((px, cellPx) in listOf(LARGE_PX to LARGE_CELL_PX, SMALL_PX to SMALL_CELL_PX)) {
                val bitmap = render(px) { drawStyle(idleFrame(px), style, showHalo = false) }
                save(bitmap, "surface-${style.name()}-$px.png")
                val actual = fingerprint(bitmap, cellPx)
                println("[PHO-61] ${style.name()} at $px px: digest ${digest(bitmap)}\n${actual.joinToString("\n")}")
                val expected = SNAPSHOTS[style to px]
                if (expected == null) {
                    failures += "${style.name()} at $px px has no pinned fingerprint; pin the rows printed above"
                } else if (!matches(expected, actual)) {
                    failures += "${style.name()} at $px px drifted from its fingerprint:\n" +
                        expected.zip(actual).joinToString("\n") { (e, a) -> "$e   $a" }
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n\n"))
    }

    @Test
    fun `dashes lie along the spin direction and as far as it carries them`() {
        val vertical = render(SINGLE_PX) { drawSingle(SurfaceStyle.Dashes, dx = 0f, dy = 1f) }
        val horizontal = render(SINGLE_PX) { drawSingle(SurfaceStyle.Dashes, dx = 1f, dy = 0f) }
        val slow = render(SINGLE_PX) { drawSingle(SurfaceStyle.Dashes, dx = 0.5f, dy = 0f) }
        val none = render(SINGLE_PX) { drawSingle(SurfaceStyle.Dashes, dx = 0f, dy = 0f) }
        val c = SINGLE_PX / 2
        val reach = (SurfaceStyle.DASH_HALF_LENGTH * SINGLE_RADIUS * 0.9f).toInt()
        val halfReach = (SurfaceStyle.DASH_HALF_LENGTH * SINGLE_RADIUS * 0.5f * 0.9f).toInt()

        assertTrue(vertical.inked(c, c + reach), "a vertical dash should reach below its centre")
        assertTrue(!vertical.inked(c + reach, c), "a vertical dash should not reach beside its centre")
        assertTrue(horizontal.inked(c + reach, c), "a horizontal dash should reach beside its centre")
        assertTrue(!horizontal.inked(c, c + reach), "a horizontal dash should not reach below its centre")
        assertTrue(slow.inked(c + halfReach, c), "a cell carried at half speed should reach half as far")
        assertTrue(!slow.inked(c + reach, c), "a cell carried at half speed should not reach full length")
        assertTrue(none.inked(c, c), "a cell with no direction should still be a dot")
        assertTrue(!none.inked(c + halfReach, c), "a cell with no direction should not streak")
        assertEquals(1, listOf(vertical, horizontal, slow, none).map(::digest).count { it == digest(none) })
    }

    @Test
    fun `every style holds a cell to the minimum radius`() {
        // A cell a tenth of a pixel across would rasterise to nothing; at the floor it leaves ink.
        for (style in SURFACE_PREVIEW_STYLES) {
            val bitmap = render(SINGLE_PX) { drawSingle(style, dx = 1f, dy = 0f, radiusPx = 0.1f) }
            assertTrue(bitmap.inked(SINGLE_PX / 2, SINGLE_PX / 2), "${style.name()} drew nothing at the floor")
        }
    }

    @Test
    fun `contact sheet of every style on the IDLE and WORKING looks at 200 px and 20 px`() {
        // Columns per preset: the 200 px render, then the 20 px render blown up 4× with no
        // filtering so its pixels can be counted. Rows follow SURFACE_PREVIEW_STYLES.
        val magnified = SMALL_PX * SMALL_SCALE
        val columnWidth = LARGE_PX + GAP + magnified
        val sheetWidth = GAP + SURFACE_PREVIEW_COLUMNS.size * (columnWidth + GAP)
        val sheetHeight = GAP + SURFACE_PREVIEW_STYLES.size * (LARGE_PX + GAP)
        val frames = SURFACE_PREVIEW_COLUMNS.map { (atmosphere, look) -> surfacePreviewFrames(atmosphere, look) }

        val sheet =
            render(sheetWidth, sheetHeight) {
                drawRect(SHEET_BACKGROUND)
                SURFACE_PREVIEW_STYLES.forEachIndexed { row, style ->
                    val top = GAP + row * (LARGE_PX + GAP)
                    frames.forEachIndexed { column, (large, small) ->
                        val left = GAP + column * (columnWidth + GAP)
                        inset(
                            left = left.toFloat(),
                            top = top.toFloat(),
                            right = (sheetWidth - left - LARGE_PX).toFloat(),
                            bottom = (sheetHeight - top - LARGE_PX).toFloat(),
                        ) {
                            drawStyle(large, style, showHalo = true)
                        }
                        val tiny = render(SMALL_PX) { drawStyle(small, style, showHalo = true) }
                        drawImage(
                            image = tiny,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(SMALL_PX, SMALL_PX),
                            dstOffset = IntOffset(left + LARGE_PX + GAP, top + (LARGE_PX - magnified) / 2),
                            dstSize = IntSize(magnified, magnified),
                            filterQuality = FilterQuality.None,
                        )
                        // And at its true size, under the blow-up.
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
        save(sheet, "surface-styles-contact-sheet.png")
    }

    @Test
    fun `reports draw time per style at resolution 10`() {
        val frame = idleFrame(BENCH_PX)
        val bitmap = ImageBitmap(BENCH_PX, BENCH_PX)
        val canvas = Canvas(bitmap)
        for (style in SURFACE_PREVIEW_STYLES) {
            val painter = style.painter()
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
                "[PHO-61] ${style.name()}: ${frame.voxels.size} voxels at $BENCH_PX px, " +
                    "DRAW p50=${"%.2f".format(ms[ms.size / 2])} ms p95=${"%.2f".format(ms[ms.size * 95 / 100])} ms",
            )
        }
    }

    // --- rendering -------------------------------------------------------------------------

    private fun DrawScope.drawStyle(
        frame: LumosCanvasFrame,
        style: SurfaceStyle,
        showHalo: Boolean,
    ) = drawLumosFrame(
        frame,
        SHEET_BACKGROUND,
        showHalo = showHalo,
        showGlyphOverlay = false,
        painter = style.painter(),
    )

    private fun DrawScope.drawSingle(
        style: SurfaceStyle,
        dx: Float,
        dy: Float,
        radiusPx: Float = SINGLE_RADIUS,
    ) {
        val c = SINGLE_PX / 2f
        val voxel = CanvasVoxel(c, c, radiusPx, red = 1f, green = 1f, blue = 1f, alpha = 1f, z = 0f, dx = dx, dy = dy)
        drawVoxels(LumosCanvasFrame(SINGLE_PX, SINGLE_PX, listOf(voxel), NO_AMBIENT, null, tick = 0L), style.painter())
    }

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

    // --- fingerprints ----------------------------------------------------------------------

    // One hex digit per cell: the cell's mean luminance in sixteenths.
    private fun fingerprint(
        bitmap: ImageBitmap,
        cellPx: Int,
    ): List<String> {
        val w = bitmap.width
        val pixels = IntArray(w * bitmap.height).also(bitmap::readPixels)
        return (0 until bitmap.height / cellPx).map { cy ->
            buildString {
                for (cx in 0 until w / cellPx) {
                    var sum = 0L
                    for (y in cy * cellPx until (cy + 1) * cellPx) {
                        for (x in cx * cellPx until (cx + 1) * cellPx) {
                            val argb = pixels[y * w + x]
                            val r = (argb shr 16) and 0xff
                            val g = (argb shr 8) and 0xff
                            val b = argb and 0xff
                            sum += (299 * r + 587 * g + 114 * b) / 1000
                        }
                    }
                    append(HEX[(sum / (cellPx * cellPx) * 16 / 256).toInt()])
                }
            }
        }
    }

    private fun matches(
        expected: List<String>,
        actual: List<String>,
    ): Boolean =
        expected.size == actual.size &&
            expected.zip(actual).all { (e, a) ->
                e.length == a.length && e.indices.all { abs(HEX.indexOf(e[it]) - HEX.indexOf(a[it])) <= 1 }
            }

    // FNV-1a over the pixels: exact, for the record, not asserted.
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

    private fun ImageBitmap.inked(
        x: Int,
        y: Int,
    ): Boolean = (asSkiaBitmap().getColor(x, y) ushr 24) and 0xff > 0

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
        const val SEED = 61L
        const val LARGE_PX = 200
        const val LARGE_CELL_PX = 10
        const val SMALL_PX = 20
        const val SMALL_CELL_PX = 2
        const val SMALL_SCALE = 4
        const val GAP = 16
        const val SINGLE_PX = 64
        const val SINGLE_RADIUS = 10f
        const val BENCH_PX = 540
        const val BENCH_ITERATIONS = 300
        const val BENCH_WARM_UP = 100
        const val HEX = "0123456789abcdef"
        val SHEET_BACKGROUND = Color(0xFF111111)
        val NO_AMBIENT = VoxelAmbient(0f, 0f, 0f, 0f, 0f, 0f, 0f)

        // Pinned fingerprints: rows of hex digits, one per 10 px cell at 200 px and per 2 px cell
        // at 20 px, of each style's render of the seeded IDLE frame on a 0x111111 background.
        // Re-pin from the rows the test prints when a style is meant to change.
        val SNAPSHOTS: Map<Pair<SurfaceStyle, Int>, List<String>> =
            mapOf(
                (SurfaceStyle.Squares to 200) to
                    listOf(
                        "112786567668a6896621",
                        "1146565556769bab9791",
                        "574755556689abab8761",
                        "796555557889aabba995",
                        "66565556878aabbbbaaa",
                        "875555667779aabbbba8",
                        "6655556677799abbbbbb",
                        "7555556668899aabbb9a",
                        "555555667779aaaabbba",
                        "55555566677889abbbba",
                        "755555667788aaabbbba",
                        "755555567789aaabbbba",
                        "7655556678999bbabbbb",
                        "776555557889aaaabbbb",
                        "6555556678799abbbbb9",
                        "6565556677899babbbb9",
                        "5765555688889bbbbab5",
                        "246556566788abbbba43",
                        "64455776679bbb9aaa48",
                        "11256666887bab9aa921",
                    ),
                (SurfaceStyle.Squares to 20) to
                    listOf(
                        "1565679a73",
                        "865579ab96",
                        "765678abb9",
                        "6556789bba",
                        "5556789abb",
                        "655678abba",
                        "655689aabb",
                        "555678abba",
                        "565678abb6",
                        "34668ab994",
                    ),
                (SurfaceStyle.Dots to 200) to
                    listOf(
                        "11269656766895796521",
                        "1136565556749bab9781",
                        "463765556689abaa8661",
                        "6965556578899bbba894",
                        "65666556878aabbbb99a",
                        "875665667779aabbba97",
                        "6755556667799bbbbbab",
                        "8555655668899aabbb89",
                        "555565667779aaaabbba",
                        "65555566678889abbbba",
                        "665655667788aabbbbba",
                        "755555557789aaabbbaa",
                        "7655556678999bbabbbb",
                        "786555557889aaabbbab",
                        "6555556678799abbbbb8",
                        "6575556677899bbbbbb8",
                        "4765556678889bbbb9b4",
                        "135556566778ababba32",
                        "53455876779abb9a9937",
                        "11256666777b9b8aa821",
                    ),
                (SurfaceStyle.Dots to 20) to
                    listOf(
                        "1466668962",
                        "656679aa75",
                        "765678aba8",
                        "755679abb9",
                        "655678aaba",
                        "665678abba",
                        "765679aaba",
                        "665678abb8",
                        "466678ab94",
                        "236679a873",
                    ),
                (SurfaceStyle.Dashes to 200) to
                    listOf(
                        "11146666765452573211",
                        "112566455662897a8451",
                        "3414666666899a974341",
                        "38766666789a9aab8762",
                        "66677556778aabbba657",
                        "867765677788aaabba75",
                        "786555656779aabbbbaa",
                        "8665656667889aabba76",
                        "565555677778aaabbba9",
                        "66655566677889abba99",
                        "665755667888aaabbba8",
                        "767655567789aaabbb99",
                        "5655556678899abbbbaa",
                        "577555567889aaaabba9",
                        "6655556678889ababb85",
                        "5575656678899abbbb95",
                        "2666666688789bbbb9b6",
                        "1135566667789ba8a711",
                        "21254775679aa9786514",
                        "11245675656979676611",
                    ),
                (SurfaceStyle.Dashes to 20) to
                    listOf(
                        "1465668952",
                        "666679aa84",
                        "765678aba8",
                        "7556789bb9",
                        "6556789aba",
                        "655678aaba",
                        "665679aaba",
                        "665678abb9",
                        "465678aba5",
                        "13667aa872",
                    ),
            )
    }
}
