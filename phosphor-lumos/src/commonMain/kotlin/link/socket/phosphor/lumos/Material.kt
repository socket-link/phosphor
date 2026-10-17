package link.socket.phosphor.lumos

import kotlinx.serialization.Serializable

/**
 * What a cell is made of: the same mark as a [SurfaceStyle], with a different feeling.
 *
 * A style decides the shape a voxel leaves on the surface; a material decides how that shape
 * takes the light. The two are orthogonal: any material dresses any style. Framework-free;
 * each surface adapter realises a material in its own primitives. `LumosCanvas` in
 * `:phosphor-lumos-compose` reads it today, wrapping the style's painter so that every cell is
 * drawn once per pass the material asks for. The catalogue follows Dots' `ParticleMaterial`,
 * ported in two steps (PHO-62 now; metal and glass with T11 of PHO-51):
 *
 * - [Flat], the default, is what every orb drew before materials existed.
 * - [Ink] is paper grain: each cell's alpha and radius are jittered by a hash of its index,
 *   scaled by [Ink.grain], and the cell is drawn twice, a soft edge under an opaque core.
 * - [Neon] is an emissive tube just inside the cell: a tinted envelope and a white filament,
 *   in passes over the frame so every envelope lies beneath every filament, and the orb's
 *   ambient halo widened by the glow. A per-cell halo pass is in the recipe too; the canvas
 *   measured it over its frame budget and leaves it off (PHO-62).
 * - [Metal] and [Glass] are declared so a consumer can write the type today. No surface draws
 *   them yet: a surface that cannot draws them as [Flat] and reports [MaterialStatus.Unavailable].
 *
 * The constants on the companion are the material recipe in cell radii and alpha fractions, so
 * a terminal adapter can honour the same proportions a canvas does.
 */
@Serializable
sealed interface Material {
    /** The style's own mark, unchanged. The default. */
    @Serializable
    data object Flat : Material

    /**
     * Paper grain. [grain] in 0..1 is how far a cell's alpha and radius may wander from the
     * frame's: at 0 every cell keeps both and gains only the soft edge. [intensity] multiplies
     * every alpha the material draws.
     */
    @Serializable
    data class Ink(
        val grain: Float = 0.5f,
        val intensity: Float = 1f,
    ) : Material {
        init {
            require(grain in 0f..1f) { "grain must be in 0..1, was $grain" }
            require(intensity >= 0f && intensity.isFinite()) { "intensity must be >= 0, was $intensity" }
        }
    }

    /**
     * An emissive tube. [glow] in 0..1 is the halo's reach past the cell, in [NEON_HALO_SPREAD]
     * radii at 1, and the envelope's in [NEON_ENVELOPE_SPREAD] radii; it also widens the orb's
     * ambient halo by the same fraction. [intensity] multiplies every alpha the material draws.
     */
    @Serializable
    data class Neon(
        val glow: Float = 0.5f,
        val intensity: Float = 1f,
    ) : Material {
        init {
            require(glow in 0f..1f) { "glow must be in 0..1, was $glow" }
            require(intensity >= 0f && intensity.isFinite()) { "intensity must be >= 0, was $intensity" }
        }
    }

    /**
     * Brushed metal lit from [lightDegrees]: declared for consumers, drawn by no surface yet
     * (T11 of PHO-51). A surface asked for it draws [Flat] and reports [MaterialStatus.Unavailable].
     */
    @Serializable
    data class Metal(
        val roughness: Float = 0.15f,
        val lightDegrees: Float = -35f,
        val chromatic: Float = 0.35f,
        val flow: Float = 1f,
    ) : Material

    /**
     * Refracting glass: declared for consumers, drawn by no surface yet (T11 of PHO-51). A surface
     * asked for it draws [Flat] and reports [MaterialStatus.Unavailable].
     */
    @Serializable
    data class Glass(
        val refraction: Float = 0.65f,
        val dispersion: Float = 0.2f,
        val frost: Float = 0.03f,
    ) : Material

    companion object {
        /** The reach of a [Neon] halo past the cell at `glow = 1`, in cell radii. */
        const val NEON_HALO_SPREAD: Float = 2f

        /** The reach of a [Neon] envelope past the cell at `glow = 1`, in cell radii. */
        const val NEON_ENVELOPE_SPREAD: Float = 0.6f

        /** The radius of a [Neon] filament, in cell radii. */
        const val NEON_FILAMENT: Float = 0.55f

        /** The alpha of a [Neon] halo at `intensity = 1`, as a fraction of the cell's. */
        const val NEON_HALO_ALPHA: Float = 0.35f

        /** The alpha of a [Neon] envelope at `intensity = 1`, as a fraction of the cell's. */
        const val NEON_ENVELOPE_ALPHA: Float = 0.7f

        /** The radius of an [Ink] cell's opaque core, in jittered cell radii. */
        const val INK_CORE: Float = 0.92f

        /** The radius of an [Ink] cell's soft edge, in jittered cell radii. */
        const val INK_EDGE: Float = 1.08f

        /** The alpha of an [Ink] cell's soft edge, as a fraction of the core's. */
        const val INK_EDGE_ALPHA: Float = 0.35f

        /** How far an [Ink] cell's radius may wander at `grain = 1`: this much each way. */
        const val INK_RADIUS_JITTER: Float = 0.2f

        /** How far an [Ink] cell's alpha may fall at `grain = 1`, as a fraction of itself. */
        const val INK_ALPHA_JITTER: Float = 0.5f

        /**
         * The grain of cell [index]: two values in 0 until 1, the first in the low half and the
         * second in the high half of the returned bits, the same on every target and every frame.
         * Index is the cell's position in painter's order, so a cell keeps its grain while the
         * frame's order holds and takes a new one when cells reorder.
         */
        fun grainBits(index: Int): Int {
            var h = index * -0x61c88647
            h = h xor (h ushr 15)
            h *= 0x2c1b3c6d
            h = h xor (h ushr 12)
            h *= -0x297a2d39
            return h xor (h ushr 15)
        }

        /** The first grain value of [grainBits], in 0 until 1. */
        fun grainAlpha(bits: Int): Float = (bits and 0xffff) / 65536f

        /** The second grain value of [grainBits], in 0 until 1. */
        fun grainRadius(bits: Int): Float = ((bits ushr 16) and 0xffff) / 65536f
    }
}

/**
 * Whether a surface draws a [Material] as asked. A surface reports this once per material it is
 * given, through `onMaterialStatus` on `LumosCanvas`, as Dots does; [Unavailable] means the
 * surface drew [Material.Flat] in its place.
 */
enum class MaterialStatus {
    Ready,
    Unavailable,
}
