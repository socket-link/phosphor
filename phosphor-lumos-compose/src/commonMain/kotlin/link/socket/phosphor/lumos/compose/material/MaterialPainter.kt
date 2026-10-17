package link.socket.phosphor.lumos.compose.material

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.compose.DotsPainter
import link.socket.phosphor.lumos.compose.SurfacePainter
import link.socket.phosphor.lumos.compose.drawHalo
import link.socket.phosphor.lumos.compose.drawLumosFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.probe.FramePhase
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.lumos.probe.measure

/** What a glass painter refracts: painted into an offscreen bitmap the size of the canvas. */
internal typealias BackdropPainter = DrawScope.(LumosCanvasFrame) -> Unit

/**
 * PHO-63 spike: draws every cell as a disc under a material shader brush, one `drawCircle` per
 * cell with the cell's uniforms — the per-cell batching option of question 3. Where the platform
 * has no shader ([createMaterialShader] returns null) it draws [DotsPainter]'s flat discs instead
 * and reports [MaterialStatus.Unavailable]: the fallback rule a build ticket would adopt.
 *
 * @param lazyShader Build the shader on the first [begin] rather than in the constructor — the
 *  "lazily on first draw" construction of question 1.
 * @param backdrop For [MaterialKind.Glass], what to refract. Re-painted when the canvas size or the
 *  frame's ambient changes, so a breathing halo costs a bitmap per breath step, not per frame.
 * @param shaderFactory Test seam: stands in for the platform's shader, or for its absence.
 */
internal class MaterialPainter(
    private val kind: MaterialKind,
    private val lazyShader: Boolean = false,
    private val backdrop: BackdropPainter? = null,
    private val shaderFactory: (MaterialKind) -> MaterialShader? = ::createMaterialShader,
) : SurfacePainter {
    var status: MaterialStatus by mutableStateOf(MaterialStatus.Unknown)
        private set

    var lightAngle: Float = MaterialSksl.DEFAULT_LIGHT_ANGLE
    var pointer: Offset = Offset.Unspecified
    var timeSeconds: (LumosCanvasFrame) -> Float = { it.tick / TICKS_PER_SECOND }

    private var shader: MaterialShader? = null
    private var created = false
    private val fallback = DotsPainter()
    private var scope: DrawScope? = null
    private val cell = MaterialCell()
    private var backdropBitmap: ImageBitmap? = null
    private var backdropKey: BackdropKey? = null

    init {
        if (!lazyShader) ensureShader()
    }

    private fun ensureShader(): MaterialShader? {
        if (!created) {
            created = true
            shader = shaderFactory(kind)
            status = if (shader == null) MaterialStatus.Unavailable else MaterialStatus.Available
        }
        return shader
    }

    override fun begin(
        scope: DrawScope,
        frame: LumosCanvasFrame,
    ) {
        this.scope = scope
        fallback.begin(scope, frame)
        val s = ensureShader() ?: return
        cell.time = timeSeconds(frame)
        cell.lightAngle = lightAngle
        cell.pointerX = if (pointer.isSpecified()) pointer.x else MaterialCell.NO_POINTER
        cell.pointerY = if (pointer.isSpecified()) pointer.y else MaterialCell.NO_POINTER
        if (kind == MaterialKind.Glass) refreshBackdrop(scope, frame, s)
    }

    private fun refreshBackdrop(
        scope: DrawScope,
        frame: LumosCanvasFrame,
        shader: MaterialShader,
    ) {
        val width = scope.size.width.toInt().coerceAtLeast(1)
        val height = scope.size.height.toInt().coerceAtLeast(1)
        val key = BackdropKey(width, height, frame.ambient)
        if (key == backdropKey) return
        backdropKey = key
        val paint = backdrop
        val bitmap =
            if (paint == null) {
                null
            } else {
                ImageBitmap(width, height).also { bmp ->
                    CanvasDrawScope().draw(scope, scope.layoutDirection, androidx.compose.ui.graphics.Canvas(bmp), scope.size) { paint(frame) }
                }
            }
        backdropBitmap = bitmap
        shader.setBackdrop(bitmap)
    }

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
        val s = shader
        if (s == null) {
            fallback.cell(index, screenX, screenY, radiusPx, rgba, z, dx, dy)
            return
        }
        cell.centerX = screenX
        cell.centerY = screenY
        cell.radius = radiusPx
        cell.red = rgba.red
        cell.green = rgba.green
        cell.blue = rgba.blue
        cell.alpha = rgba.alpha
        scope?.drawCircle(brush = s.brush(cell), radius = radiusPx, center = Offset(screenX, screenY))
    }

    override fun end() {
        fallback.end()
        scope = null
    }

    fun close() {
        shader?.close()
        shader = null
    }

    private fun Offset.isSpecified(): Boolean = this != Offset.Unspecified

    private data class BackdropKey(
        val width: Int,
        val height: Int,
        val ambient: VoxelAmbient,
    )

    private companion object {
        const val TICKS_PER_SECOND = 60f
    }
}

/** The backdrop a canvas with [backgroundColor] and its halo would show behind the orb. */
internal fun canvasBackdrop(
    backgroundColor: Color,
    showHalo: Boolean,
): BackdropPainter =
    { frame ->
        if (backgroundColor != Color.Transparent) drawRect(backgroundColor)
        if (showHalo) drawHalo(frame.ambient)
    }

/** Where the shader is built, relative to composition — the SIGILL question. */
internal enum class ShaderConstruction {
    /** A new painter, and so a new effect, on every composition; never remembered, never closed. */
    InComposition,

    /** Built inside `remember`, once per kind. */
    InRemember,

    /** Remembered, but the effect itself is compiled inside the first draw. */
    OnFirstDraw,
}

/**
 * PHO-63 spike: `LumosCanvas` with a material in place of a style, parameterised by where its
 * shader is built. Reports the painter's [MaterialStatus] once it is known.
 */
@Composable
internal fun LumosMaterialCanvas(
    frame: LumosCanvasFrame,
    kind: MaterialKind,
    construction: ShaderConstruction,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    showHalo: Boolean = true,
    lightAngle: Float = MaterialSksl.DEFAULT_LIGHT_ANGLE,
    pointer: Offset = Offset.Unspecified,
    probe: FrameProbe = FrameProbe.Disabled,
    onMaterialStatus: (MaterialStatus) -> Unit = {},
) {
    val backdrop = canvasBackdrop(backgroundColor, showHalo)
    val painter =
        when (construction) {
            ShaderConstruction.InComposition -> MaterialPainter(kind, backdrop = backdrop)
            ShaderConstruction.InRemember -> remember(kind) { MaterialPainter(kind, backdrop = backdrop) }
            ShaderConstruction.OnFirstDraw -> remember(kind) { MaterialPainter(kind, lazyShader = true, backdrop = backdrop) }
        }
    if (construction != ShaderConstruction.InComposition) {
        DisposableEffect(painter) { onDispose { painter.close() } }
    }
    painter.lightAngle = lightAngle
    painter.pointer = pointer
    val status = painter.status
    LaunchedEffect(status) { if (status != MaterialStatus.Unknown) onMaterialStatus(status) }
    Canvas(modifier = modifier) {
        probe.measure(FramePhase.DRAW) {
            drawLumosFrame(frame, backgroundColor, showHalo, showGlyphOverlay = false, painter = painter)
        }
    }
}
