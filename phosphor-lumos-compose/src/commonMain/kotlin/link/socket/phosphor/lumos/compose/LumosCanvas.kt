package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import link.socket.phosphor.lumos.GlyphEnvelope
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.Material
import link.socket.phosphor.lumos.MaterialStatus
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelGlyphState
import link.socket.phosphor.lumos.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.probe.FramePhase
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.lumos.probe.measure
import link.socket.phosphor.lumos.projection.PixelLattice
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState

/**
 * Renders a [LumosCanvasFrame] as a Compose Canvas.
 *
 * Each recomposition paints:
 * 1. An optional ambient halo (radial gradient behind the orb).
 * 2. All voxels back-to-front, each as the mark [style] asks for, made of [material]. No per-frame sort:
 *    [link.socket.phosphor.lumos.projection.PixelLattice] emits voxels z-ascending; only a
 *    hand-built, out-of-order frame is sorted here.
 * 3. An optional glyph overlay at the canvas center, faded by [GlyphEnvelope.visibility] of
 *    [VoxelGlyphState.progress].
 *
 * The overlay is the second of a glyph's two layers: the frame builder has already carved the
 * glyph into the voxels, and both layers fade on the same [GlyphEnvelope], so they rise and fall
 * together — as the terminal overlay does. Progress is the position in the glyph's display window;
 * the envelope fades in over its first 20% and out over its last 20%, so the overlay is gone by
 * the frame the glyph completes. The envelope is applied here at draw time, so recorded traces
 * replay with the same fade.
 *
 * The composable is [Stable]: identical [LumosCanvasFrame] values (structural equality via data class)
 * produce identical output, letting Compose's machinery skip recomposition when frames are unchanged.
 *
 * @param frame       Projected canvas frame, typically from [link.socket.phosphor.lumos.projection.PixelLattice.project].
 * @param modifier    Sizing and placement modifier; must include an explicit size (e.g. [androidx.compose.ui.unit.dp]).
 * @param backgroundColor Optional opaque background drawn before halo and voxels. Transparent by default.
 * @param showHalo    Paint the ambient breath-ring gradient behind the orb. Defaults to true.
 * @param showGlyphOverlay Overlay the active glyph when [LumosCanvasFrame.glyph] is non-null. Defaults to true.
 * @param probe Per-phase timing sink. Defaults to [FrameProbe.Disabled], which adds no per-paint
 *  cost; pass a [link.socket.phosphor.lumos.probe.RingBufferFrameProbe] to capture DRAW timings.
 * @param style The mark each voxel is drawn as; see [SurfaceStyle]. [SurfaceStyle.Squares], the
 *  default, draws exactly what the canvas drew before styles existed.
 * @param material What each voxel is made of; see [Material]. [Material.Flat], the default, is the
 *  style's own mark. [Material.Ink] and [Material.Neon] draw without a shader on every target;
 *  [Material.Metal] and [Material.Glass] are not drawn yet and fall back to [Material.Flat].
 * @param onMaterialStatus Told once per [material] whether this canvas draws it as asked
 *  ([MaterialStatus.Ready]) or fell back to [Material.Flat] ([MaterialStatus.Unavailable]).
 */
@Stable
@Composable
fun LumosCanvas(
    frame: LumosCanvasFrame,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    showHalo: Boolean = true,
    showGlyphOverlay: Boolean = true,
    probe: FrameProbe = FrameProbe.Disabled,
    style: SurfaceStyle = SurfaceStyle.Squares,
    material: Material = Material.Flat,
    onMaterialStatus: ((Material, MaterialStatus) -> Unit)? = null,
) {
    val painter = remember(style, material) { material.painter(style.painter()) }
    if (onMaterialStatus != null) {
        LaunchedEffect(material, onMaterialStatus) { onMaterialStatus(material, material.canvasStatus()) }
    }
    Canvas(modifier = modifier) {
        probe.measure(FramePhase.DRAW) {
            drawLumosFrame(frame, backgroundColor, showHalo, showGlyphOverlay, painter, material.haloBoost())
        }
    }
}

// The whole paint, separated from the composable so tests can draw it into an ImageBitmap.
internal fun DrawScope.drawLumosFrame(
    frame: LumosCanvasFrame,
    backgroundColor: Color = Color.Transparent,
    showHalo: Boolean = true,
    showGlyphOverlay: Boolean = true,
    painter: SurfacePainter = SquaresPainter(),
    haloBoost: Float = 0f,
) {
    if (backgroundColor != Color.Transparent) {
        drawRect(backgroundColor)
    }
    if (showHalo) drawHalo(frame.ambient, haloBoost)
    drawVoxels(frame, painter)
    if (showGlyphOverlay) {
        frame.glyph?.let { drawGlyph(it) }
    }
}

// Paints in list order, which is painter's order for any frame from PixelLattice.project.
// A hand-built frame that is out of order is sorted first (the only allocation on this path).
// Every style draws a cell at least SurfaceStyle.MIN_RADIUS_PX across: below it a cell rasterises
// to grey haze, and at 20 px most of the orb would.
internal fun DrawScope.drawVoxels(
    frame: LumosCanvasFrame,
    painter: SurfacePainter,
) {
    var voxels = frame.voxels
    for (i in 1 until voxels.size) {
        if (voxels[i].z < voxels[i - 1].z) {
            voxels = voxels.sortedBy { it.z }
            break
        }
    }
    painter.begin(this, frame)
    for (i in voxels.indices) {
        val v = voxels[i]
        val radiusPx = if (v.radiusPx < SurfaceStyle.MIN_RADIUS_PX) SurfaceStyle.MIN_RADIUS_PX else v.radiusPx
        painter.cell(i, v.screenX, v.screenY, radiusPx, Color(v.red, v.green, v.blue, v.alpha), v.z, v.dx, v.dy)
    }
    painter.end()
}

// Draws a radial gradient disk centered on the canvas to simulate the ambient breath ring.
// A material may widen it: a Neon orb's ambient reaches (1 + glow) times as far, so it falls off
// more slowly past the orb. Every preset's glow is already 1 at the centre, so alpha cannot lift.
private fun DrawScope.drawHalo(
    ambient: VoxelAmbient,
    boost: Float = 0f,
) {
    if (ambient.glowIntensity <= 0f) return
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = min(size.width, size.height) * 0.55f * (1f + boost)
    drawCircle(
        brush =
            Brush.radialGradient(
                colors =
                    listOf(
                        Color(ambient.glowRed, ambient.glowGreen, ambient.glowBlue, ambient.glowIntensity),
                        Color.Transparent,
                    ),
                center = center,
                radius = radius,
            ),
        radius = radius,
        center = center,
    )
}

// Draws the active glyph at the canvas center, alpha-scaled by the glyph envelope at its progress.
private fun DrawScope.drawGlyph(glyph: VoxelGlyphState) {
    val visibility = GlyphEnvelope.visibility(glyph.progress)
    if (visibility == 0f) return
    val cx = size.width / 2f
    val cy = size.height / 2f
    // Glyph spans 60% of the shorter canvas dimension; scale is half of that (maps [-1,1] → pixels).
    val scale = min(size.width, size.height) * 0.30f
    val color = Color(glyph.red, glyph.green, glyph.blue, visibility)
    val strokeWidth = scale * 0.18f
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (glyph.glyphName) {
        "CHECK" -> drawPath(checkPath(cx, cy, scale), color, style = stroke)
        "EXCLAIM" -> {
            drawPath(exclaimPath(cx, cy, scale), color, style = stroke)
            drawCircle(color, radius = strokeWidth / 2f, center = Offset(cx, cy + 0.55f * scale))
        }
        "QUESTION" -> {
            drawPath(questionPath(cx, cy, scale), color, style = stroke)
            drawCircle(color, radius = strokeWidth / 2f, center = Offset(cx, cy + 0.55f * scale))
        }
        "HEART" -> drawPath(heartPath(cx, cy, scale), color, style = Fill)
        "STAR" -> drawPath(starPath(cx, cy, scale), color, style = Fill)
        "LIGHTNING" -> drawPath(lightningPath(cx, cy, scale), color, style = stroke)
    }
}

// CHECK: two-segment downstroke forming a checkmark.
// Glyph-space (Y-up): (-0.50, 0.00) → (-0.10, -0.40) → (0.52, 0.42)
// Canvas transform: screenX = cx + glyphX * scale, screenY = cy − glyphY * scale.
private fun checkPath(
    cx: Float,
    cy: Float,
    scale: Float,
): Path =
    Path().apply {
        moveTo(cx - 0.50f * scale, cy)
        lineTo(cx - 0.10f * scale, cy + 0.40f * scale)
        lineTo(cx + 0.52f * scale, cy - 0.42f * scale)
    }

// EXCLAIM: vertical stem; dot is drawn separately as a filled circle below the stem.
// Glyph-space stem: y = 0.35 (top) to y = −0.35 (bottom of stem, above dot gap).
private fun exclaimPath(
    cx: Float,
    cy: Float,
    scale: Float,
): Path =
    Path().apply {
        moveTo(cx, cy - 0.35f * scale)
        lineTo(cx, cy + 0.35f * scale)
    }

// QUESTION: arc bowl (≈175° clockwise sweep) plus a short hook dropping from the open end.
// Arc center glyph-space: (0.02, 0.20), radius 0.32. Original Y-up arc start ≈ −0.15 rad maps
// to +8.6° in canvas Y-down. Dot drawn separately below the hook.
private fun questionPath(
    cx: Float,
    cy: Float,
    scale: Float,
): Path =
    Path().apply {
        val arcCx = cx + 0.02f * scale
        val arcCy = cy - 0.20f * scale
        val arcR = 0.32f * scale
        arcTo(
            rect = Rect(arcCx - arcR, arcCy - arcR, arcCx + arcR, arcCy + arcR),
            startAngleDegrees = 8.6f,
            sweepAngleDegrees = 170.0f,
            forceMoveTo = true,
        )
        // Short hook drops from the open end of the arc toward canvas center.
        lineTo(cx + 0.02f * scale, cy + 0.24f * scale)
    }

// HEART: two cubic-bezier lobes, filled. Tip at glyph (0, −0.52), center notch at (0, −0.18).
// Left lobe sweeps left to (±0.62, 0.18) control points; right lobe mirrors.
private fun heartPath(
    cx: Float,
    cy: Float,
    scale: Float,
): Path =
    Path().apply {
        moveTo(cx, cy + 0.52f * scale)
        // Left lobe: from bottom tip up and over to the center-top notch.
        cubicTo(
            cx - 0.62f * scale,
            cy + 0.18f * scale,
            cx - 0.62f * scale,
            cy - 0.30f * scale,
            cx,
            cy - 0.18f * scale,
        )
        // Right lobe: from center-top notch back down to the bottom tip.
        cubicTo(
            cx + 0.62f * scale,
            cy - 0.30f * scale,
            cx + 0.62f * scale,
            cy + 0.18f * scale,
            cx,
            cy + 0.52f * scale,
        )
        close()
    }

// STAR: 5-point polygon with 10 vertices alternating outer radius 0.58 and inner radius 0.25.
// First outer vertex at angle π/2 (top), advancing by π/5 per vertex. Y-axis inverted for canvas.
private fun starPath(
    cx: Float,
    cy: Float,
    scale: Float,
): Path =
    Path().apply {
        val outerR = 0.58f * scale
        val innerR = 0.25f * scale
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outerR else innerR
            val angle = (PI / 2.0 + i * PI / 5.0).toFloat()
            val x = cx + cos(angle) * r
            val y = cy - sin(angle) * r
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

// LIGHTNING: three connected segments forming a zigzag bolt.
// Glyph-space (Y-up): (0.24, 0.52) → (−0.08, 0.03) → (0.14, 0.03) → (−0.24, −0.52)
private fun lightningPath(
    cx: Float,
    cy: Float,
    scale: Float,
): Path =
    Path().apply {
        moveTo(cx + 0.24f * scale, cy - 0.52f * scale)
        lineTo(cx - 0.08f * scale, cy - 0.03f * scale)
        lineTo(cx + 0.14f * scale, cy - 0.03f * scale)
        lineTo(cx - 0.24f * scale, cy + 0.52f * scale)
    }

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

private fun previewAmbient(
    red: Float,
    green: Float,
    blue: Float,
) = link.socket.phosphor.lumos.VoxelAmbient(
    glowRed = red,
    glowGreen = green,
    glowBlue = blue,
    glowIntensity = 0.8f,
    orbRotationX = 0f,
    orbRotationY = 0f,
    orbRotationZ = 0f,
)

private fun previewVoxels(
    canvasSize: Float,
    red: Float,
    green: Float,
    blue: Float,
): List<LumosCanvasFrame.CanvasVoxel> {
    val cx = canvasSize / 2f
    val cy = canvasSize / 2f
    val orbR = canvasSize * 0.32f
    val voxelR = canvasSize * 0.035f
    val steps = 32
    return buildList {
        for (i in 0 until steps) {
            val angle = 2.0 * PI * i / steps
            val x = cx + orbR * cos(angle).toFloat()
            val y = cy + orbR * sin(angle).toFloat()
            add(
                LumosCanvasFrame.CanvasVoxel(
                    screenX = x,
                    screenY = y,
                    radiusPx = voxelR,
                    red = red,
                    green = green,
                    blue = blue,
                    alpha = 0.85f,
                    z = 0f,
                ),
            )
        }
        // Inner ring for density
        val innerR = orbR * 0.6f
        for (i in 0 until steps / 2) {
            val angle = 2.0 * PI * i / (steps / 2)
            val x = cx + innerR * cos(angle).toFloat()
            val y = cy + innerR * sin(angle).toFloat()
            add(
                LumosCanvasFrame.CanvasVoxel(
                    screenX = x,
                    screenY = y,
                    radiusPx = voxelR,
                    red = red * 0.7f,
                    green = green * 0.7f,
                    blue = blue * 0.7f,
                    alpha = 0.65f,
                    z = -0.3f,
                ),
            )
        }
    }
}

private fun atmosphereFrame(
    canvasSize: Int,
    red: Float,
    green: Float,
    blue: Float,
    glyphName: String? = null,
    glyphRed: Float = 1f,
    glyphGreen: Float = 1f,
    glyphBlue: Float = 1f,
    glyphProgress: Float = 0.5f,
): LumosCanvasFrame =
    LumosCanvasFrame(
        width = canvasSize,
        height = canvasSize,
        voxels = previewVoxels(canvasSize.toFloat(), red, green, blue),
        ambient = previewAmbient(red, green, blue),
        glyph =
            glyphName?.let {
                VoxelGlyphState(
                    it,
                    progress = glyphProgress,
                    red = glyphRed,
                    green = glyphGreen,
                    blue = glyphBlue,
                )
            },
        tick = 0L,
    )

/**
 * Preview composable demonstrating [LumosCanvas] across all five atmosphere presets and
 * each of the six canonical glyphs at full intensity (progress = 0.5, the middle of the hold —
 * at progress 1.0 the glyph has faded out).
 *
 * Intended for visual review in a host project preview or sample app. Not a test — simply
 * drives the composable with hardcoded [LumosCanvasFrame] values representative of each preset.
 *
 * Atmosphere colour mapping (approximated from [link.socket.phosphor.palette.AtmospherePresets]):
 *  - IDLE      blue-indigo   primaryHue≈250
 *  - LISTENING cyan          primaryHue≈195
 *  - THINKING  deep-indigo   primaryHue≈244
 *  - UNCERTAIN amber-orange  primaryHue≈32
 *  - READY     blue-cyan     primaryHue≈249
 */
@Composable
fun LumosCanvasPreview() {
    val size = 200
    val atmospheres =
        listOf(
            "IDLE" to atmosphereFrame(size, red = 0.25f, green = 0.30f, blue = 0.90f),
            "LISTENING" to atmosphereFrame(size, red = 0.10f, green = 0.72f, blue = 0.90f),
            "THINKING" to atmosphereFrame(size, red = 0.18f, green = 0.18f, blue = 0.85f),
            "UNCERTAIN" to atmosphereFrame(size, red = 0.92f, green = 0.52f, blue = 0.10f),
            "READY" to atmosphereFrame(size, red = 0.28f, green = 0.45f, blue = 0.95f),
        )
    val glyphs =
        listOf(
            "CHECK" to atmosphereFrame(size, 0.25f, 0.85f, 0.45f, "CHECK", 0.25f, 0.85f, 0.45f),
            "EXCLAIM" to atmosphereFrame(size, 0.95f, 0.60f, 0.10f, "EXCLAIM", 0.95f, 0.60f, 0.10f),
            "QUESTION" to atmosphereFrame(size, 0.65f, 0.30f, 0.90f, "QUESTION", 0.65f, 0.30f, 0.90f),
            "HEART" to atmosphereFrame(size, 0.90f, 0.25f, 0.50f, "HEART", 0.90f, 0.25f, 0.50f),
            "STAR" to atmosphereFrame(size, 0.95f, 0.82f, 0.15f, "STAR", 0.95f, 0.82f, 0.15f),
            "LIGHTNING" to atmosphereFrame(size, 0.35f, 0.45f, 0.95f, "LIGHTNING", 0.35f, 0.45f, 0.95f),
        )

    // Render all atmosphere + glyph preview frames via the composable.
    // In a host project, wrap each in a Column/Row with a Text label for visual inspection.
    for ((_, frame) in atmospheres + glyphs) {
        LumosCanvas(
            frame = frame,
            backgroundColor = Color(0xFF111111),
            modifier = Modifier,
        )
    }
}

// ---------------------------------------------------------------------------
// Surface style preview
// ---------------------------------------------------------------------------

/**
 * Preview composable for the surface styles: one row per [SurfaceStyle] in [SURFACE_PREVIEW_STYLES]
 * order, each showing the columns of [SURFACE_PREVIEW_COLUMNS] — an IDLE orb in the BASE look, then
 * a THINKING orb in the WORKING look with its ring mid-run — at 200 px and then at 20 px, Thinking
 * Orbs' size, where a style either reads or does not. Each column is one live scene stepped to
 * [SURFACE_PREVIEW_WARM_UP_SECONDS] and projected once at each size, so every row draws the same frames.
 *
 * Like [LumosCanvasPreview], a preview for a host's preview pane or sample app, not a test. Sized
 * in dp, so at a density other than 1 the canvases are larger than the frames projected for them.
 * `LumosSurfaceStylePreviewTest` renders it to `build/lumos-previews/` on the JVM.
 */
@Composable
fun LumosSurfaceStylePreview() {
    val frames =
        remember { SURFACE_PREVIEW_COLUMNS.map { (atmosphere, look) -> surfacePreviewFrames(atmosphere, look) } }
    Column(verticalArrangement = Arrangement.spacedBy(SURFACE_PREVIEW_GAP.dp)) {
        for (style in SURFACE_PREVIEW_STYLES) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SURFACE_PREVIEW_GAP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((large, small) in frames) {
                    LumosCanvas(
                        frame = large,
                        modifier = Modifier.size(SURFACE_PREVIEW_LARGE_PX.dp),
                        backgroundColor = SURFACE_PREVIEW_BACKGROUND,
                        style = style,
                    )
                    LumosCanvas(
                        frame = small,
                        modifier = Modifier.size(SURFACE_PREVIEW_SMALL_PX.dp),
                        backgroundColor = SURFACE_PREVIEW_BACKGROUND,
                        style = style,
                    )
                }
            }
        }
    }
}

/** The styles [LumosSurfaceStylePreview] shows, top to bottom. */
internal val SURFACE_PREVIEW_STYLES: List<SurfaceStyle> =
    listOf(SurfaceStyle.Squares, SurfaceStyle.Dots, SurfaceStyle.Dashes)

/** The columns [LumosSurfaceStylePreview] shows, left to right: a preset wearing a look. */
internal val SURFACE_PREVIEW_COLUMNS: List<Pair<AtmosphereState, LumosLook>> =
    listOf(AtmospherePresets.IDLE to LumosLook.BASE, AtmospherePresets.THINKING to LumosLook.WORKING)

internal const val SURFACE_PREVIEW_LARGE_PX = 200
internal const val SURFACE_PREVIEW_SMALL_PX = 20
internal const val SURFACE_PREVIEW_GAP = 16
internal val SURFACE_PREVIEW_BACKGROUND = Color(0xFF111111)

/**
 * How far into a live scene the preview frames are taken: past phase zero, and 0.6 s into the
 * working ring's second 1.7 s period, so the ring is halfway down the sphere.
 */
internal const val SURFACE_PREVIEW_WARM_UP_SECONDS = 2.3f

/** A live scene of [atmosphere] wearing [look], stepped to the warm-up, projected at the large and the small size. */
internal fun surfacePreviewFrames(
    atmosphere: AtmosphereState,
    look: LumosLook,
): Pair<LumosCanvasFrame, LumosCanvasFrame> {
    val frame =
        LiveFrameSource(atmosphere)
            .also { it.builder.look = Look(look) }
            .next(SURFACE_PREVIEW_WARM_UP_SECONDS)
    return surfacePreviewLattice(SURFACE_PREVIEW_LARGE_PX, frame.resolution).project(frame) to
        surfacePreviewLattice(SURFACE_PREVIEW_SMALL_PX, frame.resolution).project(frame)
}

// Half a lattice cell per voxel, as OrbStepper sizes it.
internal fun surfacePreviewLattice(
    px: Int,
    resolution: Int,
): PixelLattice = PixelLattice(widthPx = px, heightPx = px, voxelRadiusPx = px / (4f * resolution))
