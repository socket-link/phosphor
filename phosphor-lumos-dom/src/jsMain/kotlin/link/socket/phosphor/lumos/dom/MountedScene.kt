package link.socket.phosphor.lumos.dom

import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.LumosInput

/**
 * A mounted orb's live scene, addressed by name as the page addresses it: a [LiveFrameSource] whose
 * atmosphere is a preset, at the preset's resolution or at the mount's.
 *
 * A resolution of its own makes the atmosphere a copy of the preset, which [LiveFrameSource] no
 * longer recognises by name, so this source keys the look clock by the preset it was asked for:
 * an orb at resolution 3 breathes with one at 10 in the same state.
 *
 * @param atmosphere The preset to start in, by name; see [AtmospherePresets.ALL].
 * @param resolution The lattice resolution to hold every preset at, or null for each preset's own.
 * @throws IllegalArgumentException when [atmosphere] names no preset.
 */
internal class MountedScene(
    atmosphere: String,
    private val resolution: Int?,
) : LumosFrameSource {
    private var preset: String = presetName(atmosphere)

    val live: LiveFrameSource = LiveFrameSource(stateOf(preset))

    private val choreographer =
        checkNotNull(live.runtime.atmosphereChoreographer) { "LiveFrameSource always enables the atmosphere" }

    /** Head for the preset named [name] over its tabled transition. */
    fun setAtmosphere(name: String) {
        val next = presetName(name)
        choreographer.setAtmosphere(stateOf(next), targetPresetName = next)
        preset = next
    }

    /** Blend to [look] over `VoxelFrameBuilder.LOOK_BLEND_SECONDS`. */
    fun setLook(look: Look) = live.builder.lookTo(look)

    override fun next(dt: Float): VoxelFrame = live.next(dt)

    override fun still(dt: Float): VoxelFrame = live.still(dt)

    override val revision: Long get() = live.revision

    override val lookKey: String?
        get() =
            live.lookKey ?: run {
                val look = live.builder.look
                if (look.look == LumosLook.BASE) preset else "$preset/${look.key}"
            }

    override fun align(lookTime: Double) = live.align(lookTime)

    override fun alignLook(lookTime: Double) = live.alignLook(lookTime)

    override fun setInput(input: LumosInput) = live.setInput(input)

    private fun stateOf(name: String): AtmosphereState {
        val state = AtmospherePresets.byName(name)!!
        return if (resolution == null) state else state.copy(resolution = resolution)
    }

    private companion object {
        fun presetName(name: String): String =
            AtmospherePresets.ALL.firstOrNull { (preset, _) -> preset.equals(name.trim(), ignoreCase = true) }?.first
                ?: throw IllegalArgumentException(
                    "Unknown atmosphere \"$name\"; one of ${AtmospherePresets.ALL.joinToString { it.first }}",
                )
    }
}
