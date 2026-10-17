package link.socket.phosphor.lumos.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import link.socket.phosphor.lumos.Material
import link.socket.phosphor.lumos.MaterialStatus
import link.socket.phosphor.lumos.frame.LumosCanvasFrame

/**
 * The painter that draws [this] material over [inner], the style's own painter. [Material.Flat]
 * is [inner] itself; a material [LumosCanvas] cannot draw is [inner] too, and [canvasStatus]
 * says so. A new instance each call, so remember it.
 */
fun Material.painter(inner: SurfacePainter): SurfacePainter =
    when (this) {
        Material.Flat -> inner
        is Material.Ink -> InkPainter(inner, this)
        is Material.Neon -> NeonPainter(inner, this)
        is Material.Metal, is Material.Glass -> inner
    }

/** Whether [LumosCanvas] draws [this] material as asked, or falls back to [Material.Flat]. */
internal fun Material.canvasStatus(): MaterialStatus =
    when (this) {
        Material.Flat, is Material.Ink, is Material.Neon -> MaterialStatus.Ready
        is Material.Metal, is Material.Glass -> MaterialStatus.Unavailable
    }

/** How much a material widens the orb's ambient halo: a [Material.Neon]'s glow, nothing otherwise. */
internal fun Material.haloBoost(): Float = if (this is Material.Neon) glow else 0f

/**
 * Ink: paper grain. Each cell's alpha and radius wander by a hash of its index, scaled by the
 * grain, and the cell is drawn twice through the style's painter — a soft edge at
 * [Material.INK_EDGE] radii and [Material.INK_EDGE_ALPHA] of the alpha, then the core at
 * [Material.INK_CORE] radii. Per cell, no buffering, no allocation: the hash is integer
 * arithmetic and [Color] is a value class.
 */
internal class InkPainter(
    private val inner: SurfacePainter,
    private val material: Material.Ink,
) : SurfacePainter {
    override fun begin(
        scope: DrawScope,
        frame: LumosCanvasFrame,
    ) = inner.begin(scope, frame)

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
        val bits = Material.grainBits(index)
        val grain = material.grain
        val radius = radiusPx * (1f + grain * Material.INK_RADIUS_JITTER * (2f * Material.grainRadius(bits) - 1f))
        val fade = 1f - grain * Material.INK_ALPHA_JITTER * Material.grainAlpha(bits)
        val alpha = (rgba.alpha * material.intensity * fade).coerceIn(0f, 1f)
        val edge = rgba.copy(alpha = alpha * Material.INK_EDGE_ALPHA)
        inner.cell(index, screenX, screenY, radius * Material.INK_EDGE, edge, z, dx, dy)
        inner.cell(index, screenX, screenY, radius * Material.INK_CORE, rgba.copy(alpha = alpha), z, dx, dy)
    }

    override fun end() = inner.end()
}

/**
 * How a [NeonPainter] draws its halo pass; see each entry. The default is [None]: at 540 px and
 * resolution 10 the halo pass alone costs more than the whole 50 ms frame at 20 fps (PHO-62
 * measured stepped at 127 ms p95 against 45 ms for the two passes), so the canvas draws the
 * envelope and filament and leaves the glow to the orb's widened ambient halo.
 */
internal enum class NeonHalo {
    /**
     * Two concentric discs of the tint, each at half the halo alpha, the outer at the full halo
     * radius and the inner halfway in, added with [BlendMode.Plus]. No brush, so nothing is
     * allocated per cell; a two-step fall-off stands in for the gradient at cell sizes.
     */
    Stepped,

    /**
     * One [Brush.radialGradient] disc per cell from the tint to transparent, added with
     * [BlendMode.Plus]. The true fall-off, at the cost of a brush — a shader — per cell per
     * frame, since the gradient is centred on the cell and coloured by it.
     */
    Gradient,

    /** No halo pass: envelope and filament only, the two-pass fallback. */
    None,
}

/**
 * Neon: an emissive tube just inside the cell. Cells are buffered as they arrive and drawn on
 * [end] in up to three passes over the frame, as Dots layers them — every halo beneath every
 * envelope, every envelope beneath every filament — so a filament is never buried under a
 * neighbour's halo. The halo pass is off by default; see [NeonHalo].
 *
 * 1. A halo of the tint, [Material.NEON_HALO_SPREAD] × glow radii past the cell at
 *    [Material.NEON_HALO_ALPHA] × intensity of the cell's alpha, added with [BlendMode.Plus] so
 *    overlapping halos brighten. Drawn by this painter as a disc whatever the style; see [NeonHalo].
 * 2. An envelope through the style's painter, [Material.NEON_ENVELOPE_SPREAD] × glow radii past
 *    the cell at [Material.NEON_ENVELOPE_ALPHA] × intensity.
 * 3. A white filament through the style's painter, [Material.NEON_FILAMENT] radii, at intensity.
 *
 * The buffers are flat arrays reused across frames and grown only when a frame is larger than any
 * before it; a frame of the same size allocates nothing.
 *
 * [filamentAlpha] scales the filament's alpha below the recipe's `intensity`, for the visual
 * verdict: a lattice at resolution 10 overlaps each point of the orb with some ten cells, and ten
 * white filaments at full alpha saturate it to white; at a fraction they add up to white only
 * where the orb is densest and the tint shows through elsewhere.
 */
internal class NeonPainter(
    private val inner: SurfacePainter,
    private val material: Material.Neon,
    private val halo: NeonHalo = NeonHalo.None,
    private val filamentAlpha: Float = 1f,
) : SurfacePainter {
    private var scope: DrawScope? = null
    private var count = 0
    private var indices = IntArray(INITIAL_CAPACITY)
    private var xs = FloatArray(INITIAL_CAPACITY)
    private var ys = FloatArray(INITIAL_CAPACITY)
    private var radii = FloatArray(INITIAL_CAPACITY)
    private var zs = FloatArray(INITIAL_CAPACITY)
    private var dxs = FloatArray(INITIAL_CAPACITY)
    private var dys = FloatArray(INITIAL_CAPACITY)
    private var colors = LongArray(INITIAL_CAPACITY)

    private val haloSpread = 1f + Material.NEON_HALO_SPREAD * material.glow
    private val envelopeSpread = 1f + Material.NEON_ENVELOPE_SPREAD * material.glow
    private val haloAlpha = Material.NEON_HALO_ALPHA * material.intensity
    private val envelopeAlpha = Material.NEON_ENVELOPE_ALPHA * material.intensity

    override fun begin(
        scope: DrawScope,
        frame: LumosCanvasFrame,
    ) {
        this.scope = scope
        count = 0
        ensureCapacity(frame.voxels.size)
        inner.begin(scope, frame)
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
        if (count == xs.size) ensureCapacity(count * 2)
        indices[count] = index
        xs[count] = screenX
        ys[count] = screenY
        radii[count] = radiusPx
        zs[count] = z
        dxs[count] = dx
        dys[count] = dy
        colors[count] = rgba.value.toLong()
        count++
    }

    override fun end() {
        val scope = scope
        if (scope != null) {
            if (halo != NeonHalo.None) {
                for (i in 0 until count) {
                    val tint = Color(colors[i].toULong())
                    val alpha = (tint.alpha * haloAlpha).coerceIn(0f, 1f)
                    drawHalo(scope, xs[i], ys[i], radii[i] * haloSpread, tint, alpha)
                }
            }
            for (i in 0 until count) {
                val tint = Color(colors[i].toULong())
                val alpha = (tint.alpha * envelopeAlpha).coerceIn(0f, 1f)
                val envelope = tint.copy(alpha = alpha)
                inner.cell(indices[i], xs[i], ys[i], radii[i] * envelopeSpread, envelope, zs[i], dxs[i], dys[i])
            }
            for (i in 0 until count) {
                val alpha = (Color(colors[i].toULong()).alpha * material.intensity * filamentAlpha).coerceIn(0f, 1f)
                val filament = Color.White.copy(alpha = alpha)
                inner.cell(indices[i], xs[i], ys[i], radii[i] * Material.NEON_FILAMENT, filament, zs[i], dxs[i], dys[i])
            }
        }
        inner.end()
        count = 0
        this.scope = null
    }

    private fun drawHalo(
        scope: DrawScope,
        x: Float,
        y: Float,
        radius: Float,
        tint: Color,
        alpha: Float,
    ) {
        val center = Offset(x, y)
        when (halo) {
            NeonHalo.Stepped -> {
                val step = tint.copy(alpha = alpha * 0.5f)
                scope.drawCircle(color = step, radius = radius, center = center, blendMode = BlendMode.Plus)
                scope.drawCircle(color = step, radius = radius * 0.5f, center = center, blendMode = BlendMode.Plus)
            }
            NeonHalo.Gradient ->
                scope.drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors = listOf(tint.copy(alpha = alpha), tint.copy(alpha = 0f)),
                            center = center,
                            radius = radius,
                        ),
                    radius = radius,
                    center = center,
                    blendMode = BlendMode.Plus,
                )
            NeonHalo.None -> Unit
        }
    }

    private fun ensureCapacity(size: Int) {
        if (size <= xs.size) return
        indices = indices.copyOf(size)
        xs = xs.copyOf(size)
        ys = ys.copyOf(size)
        radii = radii.copyOf(size)
        zs = zs.copyOf(size)
        dxs = dxs.copyOf(size)
        dys = dys.copyOf(size)
        colors = colors.copyOf(size)
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}
