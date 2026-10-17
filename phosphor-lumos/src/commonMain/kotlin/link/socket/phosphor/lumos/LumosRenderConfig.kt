package link.socket.phosphor.lumos

import kotlinx.serialization.Serializable

/**
 * Embedder-controllable knobs for [VoxelFrame] production.
 *
 * Distinct from [link.socket.phosphor.signal.AtmosphereState], which describes
 * scene-global visual character. Config here is about how the renderer
 * interprets that character — global format adjustments, optional features the
 * downstream renderer cannot honor, and cheap emission filters.
 *
 * Downstream renderers are also responsible for the light budget: per-voxel
 * sRGB output from [VoxelFrameBuilder] is in 0..1, and the consuming renderer
 * must constrain its lighting setup so that total ambient + directional
 * illumination does not exceed 1.0 to avoid color overshoot.
 *
 * @property globalYSquashOverride Multiplicative Y-axis squash applied on top
 *  of [link.socket.phosphor.signal.AtmosphereState.ySquash]. Null leaves the
 *  atmosphere's squash untouched; a non-null value multiplies further.
 * @property enableGlyphCarving When false, [VoxelFrame.glyph] is always null
 *  even if the atmosphere references a glyph. Useful for renderers that do
 *  not support glyph carving. Default true.
 * @property omitBelowScale Voxels whose final [VoxelCell.scale] falls below
 *  this threshold are omitted from the output frame entirely. Default 0
 *  emits all voxels regardless of scale.
 * @property rimFade Light the sphere from the front in [LumosLook.BASE]: opacity
 *  ramps from 0 just behind the rim to 1 at the front, and radius from a quarter
 *  at the back to full at the front, Thinking Orbs' base look. Off by default,
 *  so every existing preset, trace and golden file is unchanged; the other looks
 *  fade their rim regardless. See [VoxelFrameBuilder.look].
 * @property agentId Which agent's signal drives the orb when a
 *  [VoxelFrameBuilder.lookSwitch] is set: that agent's phase, activity and
 *  escalation. Null, the default, reads the scene's dominant phase
 *  (`SceneSnapshot.choreographyPhase`) with the first agent's activity and
 *  escalation. An id the scene has no agent for reads as no phase and no agent.
 *  Ignored without a switch.
 * @property unattended Whether this surface is one nobody is watching — an orb
 *  in a "running now" list rather than the run's own view. A mount-time fact:
 *  with a switch set, the orb wears [CognitiveLookSwitch.whenUnattended] in
 *  place of whatever the table picked, keeping the table's glyph. Ignored
 *  without a switch.
 * @property interactive Whether the cells part around the pointer, as Dots'
 *  `interactive` turns on its repel: in a live frame whose snapshot carries a
 *  [link.socket.phosphor.signal.LumosInput.pointer], the cells near the
 *  pointer's line of sight give way, and spring back when it leaves. Off by
 *  default, so an orb nobody can touch renders as it always has. It gates the
 *  push alone: the pointer still reaches [VoxelFrame.input], for the surface's
 *  light, and a [GazingForm]'s gaze either way. Reduced motion never pushes,
 *  and a capture cannot: `Oscilloscope`'s scene has no pointer. See
 *  [VoxelFrameBuilder.build].
 */
@Serializable
data class LumosRenderConfig(
    val globalYSquashOverride: Float? = null,
    val enableGlyphCarving: Boolean = true,
    val omitBelowScale: Float = 0.0f,
    val rimFade: Boolean = false,
    val agentId: String? = null,
    val unattended: Boolean = false,
    val interactive: Boolean = false,
)
