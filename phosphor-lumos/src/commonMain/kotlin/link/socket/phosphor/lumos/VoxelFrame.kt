package link.socket.phosphor.lumos

import kotlinx.serialization.Serializable

/**
 * Serializable snapshot of one voxel-orb render tick.
 *
 * Sibling to [link.socket.phosphor.renderer.SimulationFrame]; the two frame
 * types are intentionally separate because the Lumos voxel pipeline emits a
 * list of 3D cubes with per-voxel position, scale, and color, whereas the
 * Phosphor cell-based pipeline emits a 2D grid of character cells.
 *
 * Consumers map [VoxelFrame] to their own 3D draw primitives — Compose
 * Multiplatform 3D, Three.js, or an ANSI-projected terminal renderer. This
 * type carries no UI-framework dependencies.
 *
 * The frame is produced by the builder introduced in PHO-17 from an
 * `AtmosphereState`, an `AtmosphereTransition`, and a `VoxelSphere`; this
 * ticket (PHO-16) only defines the data shape.
 *
 * @property tick Monotonic frame counter, matching [link.socket.phosphor.renderer.SimulationFrame.tick].
 * @property timestampEpochMillis Wall-clock timestamp at which the frame was produced.
 * @property resolution Resolution at which this frame's voxel lattice was constructed.
 * @property cells Per-voxel render data, ordered identically to `VoxelSphere.voxels`.
 * @property ambient Per-frame derived parameters useful to renderers: glow intensity,
 *  overall rotation, atmospheric color (for halo/glow effects).
 * @property glyph Active glyph state, if any. Null when no glyph is being rendered.
 *  Populated by PHO-18; included in the DTO now so the shape is stable.
 */
@Serializable
data class VoxelFrame(
    val tick: Long,
    val timestampEpochMillis: Long,
    val resolution: Int,
    val cells: List<VoxelCell>,
    val ambient: VoxelAmbient,
    val glyph: VoxelGlyphState? = null,
)

/**
 * Per-voxel render data carried by [VoxelFrame].
 *
 * @property x Voxel x position in lattice space, post-noise post-bump post-pulse.
 * @property y Voxel y position in lattice space, post-noise post-bump post-pulse.
 * @property z Voxel z position in lattice space, post-noise post-bump post-pulse.
 * @property scale Scale multiplier applied to the voxel cube. 1.0 = full size, 0.0 = invisible.
 *  Combines voxel-gap, breath pulse, bipolar boundary thinning, and glyph carving.
 * @property red Final rendered red channel in sRGB, 0..1.
 * @property green Final rendered green channel in sRGB, 0..1.
 * @property blue Final rendered blue channel in sRGB, 0..1.
 * @property alpha Optional alpha; null means "use 1.0". Reserved for future fade effects.
 */
@Serializable
data class VoxelCell(
    val x: Float,
    val y: Float,
    val z: Float,
    val scale: Float,
    val red: Float,
    val green: Float,
    val blue: Float,
    val alpha: Float? = null,
)

/**
 * Per-frame ambient parameters carried by [VoxelFrame].
 *
 * @property glowRed Atmospheric glow red channel in sRGB, 0..1, computed as an OKLab-mid of primary and secondary atmosphere hues.
 * @property glowGreen Atmospheric glow green channel in sRGB, 0..1.
 * @property glowBlue Atmospheric glow blue channel in sRGB, 0..1.
 * @property glowIntensity Glow intensity multiplier, drawn from `AtmosphereState.glow`.
 * @property orbRotationX Continuous orb rotation around the X axis in radians (Euler XYZ), applied uniformly to all voxels.
 * @property orbRotationY Continuous orb rotation around the Y axis in radians (Euler XYZ).
 * @property orbRotationZ Continuous orb rotation around the Z axis in radians (Euler XYZ).
 */
@Serializable
data class VoxelAmbient(
    val glowRed: Float,
    val glowGreen: Float,
    val glowBlue: Float,
    val glowIntensity: Float,
    val orbRotationX: Float,
    val orbRotationY: Float,
    val orbRotationZ: Float,
)

/**
 * Active glyph carved into the voxel orb, carried by [VoxelFrame.glyph].
 *
 * @property glyphName Identifier of the active glyph (see the PHO-18 `LumosGlyph` enum).
 * @property progress Glyph rendering progress, 0..1. Renderers may use this to fade glyph
 *  voxels in/out across the glyph's display window.
 * @property red Glyph-member voxel red channel in sRGB, 0..1, distinct from base voxel color.
 * @property green Glyph-member voxel green channel in sRGB, 0..1.
 * @property blue Glyph-member voxel blue channel in sRGB, 0..1.
 */
@Serializable
data class VoxelGlyphState(
    val glyphName: String,
    val progress: Float,
    val red: Float,
    val green: Float,
    val blue: Float,
)
