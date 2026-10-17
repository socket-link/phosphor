package link.socket.phosphor.lumos

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import link.socket.phosphor.signal.LumosInput

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
 * The frame is produced by [VoxelFrameBuilder] from an `AtmosphereState`, an
 * `AtmosphereTransition`, and the [FormSample] of its [LumosForm]; this type
 * only defines the data shape.
 *
 * @property tick Monotonic frame counter, matching [link.socket.phosphor.renderer.SimulationFrame.tick].
 * @property timestampEpochMillis Wall-clock timestamp at which the frame was produced.
 * @property resolution The lattice resolution the atmosphere asked for when this frame was built:
 *  `AtmosphereState.resolution`. With the default [Forms.Lattice] form it is the resolution the
 *  cells were laid out at; with another form the cells follow the form and this still reports
 *  the atmosphere's resolution, so the field means the same thing in every frame.
 * @property cells Per-voxel render data, in the order of the form's sample. With the default
 *  lattice form that is `VoxelSphere.voxels` order.
 * @property ambient Per-frame derived parameters useful to renderers: glow intensity,
 *  overall rotation, atmospheric color (for halo/glow effects).
 * @property glyph Active glyph state, if any. Null when no glyph is being rendered.
 *  Populated by PHO-18; included in the DTO now so the shape is stable.
 * @property input What the person was doing to the surface when this frame was built, for the
 *  surface's light: the snapshot's [LumosInput], which [VoxelFrameBuilder.build] carries unless it
 *  is [LumosInput.NONE]. Null when nothing pointed or pressed, and always on a still, a steady
 *  state and a recorded frame. Never serialized, so a trace cannot record it and the frame's JSON
 *  shape is what it was: input is live only.
 */
@Serializable
data class VoxelFrame(
    val tick: Long,
    val timestampEpochMillis: Long,
    val resolution: Int,
    val cells: List<VoxelCell>,
    val ambient: VoxelAmbient,
    val glyph: VoxelGlyphState? = null,
    @Transient val input: LumosInput? = null,
)

/**
 * Per-voxel render data carried by [VoxelFrame].
 *
 * @property x Voxel x position in lattice space, post-noise post-bump post-pulse.
 * @property y Voxel y position in lattice space, post-noise post-bump post-pulse.
 * @property z Voxel z position in lattice space, post-noise post-bump post-pulse.
 * @property scale Scale multiplier applied to the voxel cube. 1.0 = full size, 0.0 = invisible.
 *  Combines the voxel fill (`1 - AtmosphereState.voxelGap`), breath pulse, bipolar boundary
 *  thinning, and glyph carving. A renderer multiplies its own full-cell size by this value,
 *  so the orb's apparent density is this number and its apparent size is the renderer's.
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
 * @property progress Linear position in the glyph's display window, 0 when queued and 1 at
 *  completion. This is not a visibility: renderers fade by [GlyphEnvelope.visibility] of it,
 *  the same envelope the carve into these voxels already used, so an overlay and the carve
 *  rise and fall together.
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
