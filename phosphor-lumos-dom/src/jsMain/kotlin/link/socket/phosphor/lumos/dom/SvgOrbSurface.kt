package link.socket.phosphor.lumos.dom

import kotlin.math.min
import link.socket.phosphor.lumos.GlyphEnvelope
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelGlyphState
import link.socket.phosphor.lumos.frame.LumosCanvasFrame

/**
 * Draws a [LumosCanvasFrame] into an `<svg>`, layer for layer what `LumosCanvas` paints:
 *
 * 1. The ambient halo, one `<radialGradient>` behind the orb, `0.55 ×` the shorter side across.
 * 2. One element per cell, back to front, as [style] draws it; see [SvgCellPainter].
 * 3. The active glyph as a `<path>` overlay — and a `<circle>` for the dot of `!` and `?` — at the
 *    centre, faded by [GlyphEnvelope.visibility] of its progress.
 *
 * What makes it cheap enough to run a frame at a time, from Thinking Orbs' `OrbRender`:
 *
 * - Elements are made once and kept, then updated by attribute.
 * - Every attribute goes through a per-element string cache ([CachedNode]); an unchanged value is
 *   never written, so a still orb costs no DOM writes a frame.
 * - A frame with fewer cells hides the surplus once with `visibility="hidden"` and remembers it,
 *   so a hidden cell costs nothing on later frames.
 * - Every cell is drawn at least [SurfaceStyle.MIN_RADIUS_PX] across.
 * - The `viewBox` is the frame's size in CSS pixels, so the browser scales the vectors and they
 *   stay sharp under pinch zoom without a redraw.
 *
 * The surface adds its own children after whatever the `<svg>` already holds and removes only
 * those in [dispose].
 *
 * @param svg The `<svg>` to draw into.
 * @param factory Makes the surface's elements.
 * @param style The mark each cell is drawn as.
 * @param tint Each cell's own colour, or CSS `currentColor` for every cell, halo and glyph.
 * @param showHalo Draw the halo.
 * @param showGlyph Draw the glyph overlay.
 * @param id Unique on the page: the halo gradient is referenced by `url(#<id>-halo)`.
 */
internal class SvgOrbSurface(
    private val svg: SvgNode,
    private val factory: SvgFactory,
    val style: SurfaceStyle,
    val tint: SvgTint,
    showHalo: Boolean = true,
    showGlyph: Boolean = true,
    id: String = nextSurfaceId(),
) {
    private val counter = WriteCounter()
    private val painter = style.svgPainter(tint)
    private val added = ArrayList<SvgNode>()

    private val halo: HaloLayer? = if (showHalo) HaloLayer("$id-halo") else null
    private val cellGroup: SvgNode = append(factory.create("g"))
    private val glyph: GlyphLayer? = if (showGlyph) GlyphLayer() else null

    private val cells = ArrayList<CachedNode>()
    private var viewBox: String? = null

    /** Attribute writes this surface has made, creation included. */
    val attributeWrites: Long get() = counter.writes

    /** Attribute writes skipped because the value was already on the element. */
    val attributeSkips: Long get() = counter.skips

    /** Cell elements made so far; never shrinks. */
    val elements: Int get() = cells.size

    fun render(frame: LumosCanvasFrame) {
        val box = "0 0 ${frame.width} ${frame.height}"
        if (box != viewBox) {
            svg.setAttribute("viewBox", box)
            counter.writes++
            viewBox = box
        }
        halo?.render(frame)
        renderCells(frame)
        glyph?.render(frame)
    }

    /** Take every element the surface added back out of the `<svg>`, and the `viewBox` with them. */
    fun dispose() {
        for (node in added) svg.removeChild(node)
        added.clear()
        cells.clear()
        if (viewBox != null) svg.removeAttribute("viewBox")
        viewBox = null
    }

    // Paints in list order, which is painter's order for any frame from PixelLattice.project. A
    // hand-built frame that is out of order is sorted first, as LumosCanvas does.
    private fun renderCells(frame: LumosCanvasFrame) {
        var voxels = frame.voxels
        for (i in 1 until voxels.size) {
            if (voxels[i].z < voxels[i - 1].z) {
                voxels = voxels.sortedBy { it.z }
                break
            }
        }
        val count = voxels.size
        while (cells.size < count) {
            val cell = CachedNode(factory.create(painter.tag), SvgCellPainter.SLOTS, counter)
            painter.prepare(cell)
            cellGroup.appendChild(cell.node)
            cells += cell
        }
        painter.begin(frame)
        for (i in 0 until count) {
            val v = voxels[i]
            val cell = cells[i]
            cell.show()
            val radiusPx = if (v.radiusPx < SurfaceStyle.MIN_RADIUS_PX) SurfaceStyle.MIN_RADIUS_PX else v.radiusPx
            painter.cell(cell, v.screenX, v.screenY, radiusPx, v.red, v.green, v.blue, v.alpha, v.z, v.dx, v.dy)
        }
        painter.end()
        for (i in count until cells.size) cells[i].hide()
    }

    private fun append(node: SvgNode): SvgNode {
        svg.appendChild(node)
        added += node
        return node
    }

    private fun colourOf(
        red: Float,
        green: Float,
        blue: Float,
    ): String = if (tint == SvgTint.CURRENT_COLOR) CURRENT_COLOR else svgRgb(red, green, blue)

    // The breath ring: a circle filled with a radial gradient from the glow colour at the ambient's
    // intensity to nothing at its rim. LumosCanvas fades to transparent black, which darkens the rim
    // on a light page; this fades the glow colour itself.
    private inner class HaloLayer(
        private val gradientId: String,
    ) {
        private val inner: CachedNode
        private val outer: CachedNode
        private val disc: CachedNode

        init {
            val defs = append(factory.create("defs"))
            val gradient = factory.create("radialGradient")
            gradient.setAttribute("id", gradientId)
            counter.writes++
            defs.appendChild(gradient)
            inner = CachedNode(factory.create("stop"), HALO_SLOTS, counter)
            outer = CachedNode(factory.create("stop"), HALO_SLOTS, counter)
            inner.setOnce("offset", "0")
            outer.setOnce("offset", "1")
            outer.setOnce("stop-opacity", "0")
            gradient.appendChild(inner.node)
            gradient.appendChild(outer.node)
            disc = CachedNode(append(factory.create("circle")), HALO_SLOTS, counter)
            disc.setOnce("fill", "url(#$gradientId)")
            disc.hide()
        }

        fun render(frame: LumosCanvasFrame) {
            val ambient: VoxelAmbient = frame.ambient
            if (ambient.glowIntensity <= 0f) {
                disc.hide()
                return
            }
            val colour = colourOf(ambient.glowRed, ambient.glowGreen, ambient.glowBlue)
            inner.set(0, "stop-color", colour)
            inner.set(1, "stop-opacity", svgNumber(ambient.glowIntensity))
            outer.set(0, "stop-color", colour)
            disc.set(0, "cx", svgNumber(frame.width / 2f))
            disc.set(1, "cy", svgNumber(frame.height / 2f))
            disc.set(2, "r", svgNumber(min(frame.width, frame.height) * HALO_RADIUS))
            disc.show()
        }
    }

    // The glyph overlay: a path, stroked or filled as LumosCanvas draws that glyph, and a dot for the
    // two glyphs that have one. Both are at the glyph's colour and fade on the envelope as one.
    private inner class GlyphLayer {
        private val mark = CachedNode(append(factory.create("path")), GLYPH_SLOTS, counter)
        private val dot = CachedNode(append(factory.create("circle")), GLYPH_SLOTS, counter)

        init {
            mark.setOnce("stroke-linecap", "round")
            mark.setOnce("stroke-linejoin", "round")
            mark.hide()
            dot.hide()
        }

        fun render(frame: LumosCanvasFrame) {
            val state: VoxelGlyphState? = frame.glyph
            val visibility = if (state == null) 0f else GlyphEnvelope.visibility(state.progress)
            val geometry = state?.let { GlyphPaths.of(it.glyphName, frame.width, frame.height) }
            if (state == null || geometry == null || visibility == 0f) {
                mark.hide()
                dot.hide()
                return
            }
            val colour = colourOf(state.red, state.green, state.blue)
            val opacity = svgNumber(visibility)
            mark.set(0, "d", geometry.d)
            if (geometry.filled) {
                mark.set(1, "fill", colour)
                mark.set(2, "stroke", "none")
            } else {
                mark.set(1, "fill", "none")
                mark.set(2, "stroke", colour)
                mark.set(3, "stroke-width", geometry.strokeWidth)
            }
            mark.set(4, "opacity", opacity)
            mark.show()

            val dotAt = geometry.dot
            if (dotAt == null) {
                dot.hide()
                return
            }
            dot.set(0, "cx", dotAt.cx)
            dot.set(1, "cy", dotAt.cy)
            dot.set(2, "r", dotAt.r)
            dot.set(3, "fill", colour)
            dot.set(4, "opacity", opacity)
            dot.show()
        }
    }

    private companion object {
        const val CURRENT_COLOR = "currentColor"
        const val HALO_RADIUS = 0.55f
        const val HALO_SLOTS = 3
        const val GLYPH_SLOTS = 5
    }
}

private var surfaces = 0

// Gradient ids are document-wide, so every surface on the page takes its own.
private fun nextSurfaceId(): String = "phosphor-lumos-${++surfaces}"
