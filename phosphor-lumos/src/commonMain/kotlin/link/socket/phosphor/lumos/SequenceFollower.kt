package link.socket.phosphor.lumos

import link.socket.phosphor.choreography.AtmosphereTransitionSpec
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState

/**
 * Applies a [LumosSequence]'s steps to a [LiveFrameSource]'s scene and builder as the sequence
 * enters them: the preset through `CognitiveSceneRuntime.setAtmospherePreset`, the form through
 * [VoxelFrameBuilder.morphTo], the look through [VoxelFrameBuilder.lookTo], the glyph through
 * [VoxelFrameBuilder.queueGlyph]. A step that names the preset, form or look the orb already shows
 * applies nothing; a glyph fires on every entry.
 *
 * Built by [LiveFrameSource.follow] and reached only through it, so a bundle that never follows a
 * sequence carries none of this.
 */
internal class SequenceFollower(
    private val source: LiveFrameSource,
) {
    private val runtime = source.runtime
    private val builder = source.builder
    private val choreographer =
        requireNotNull(runtime.atmosphereChoreographer) { "LiveFrameSource always enables the atmosphere" }

    private var appliedCycle: Int = -1
    private var appliedStep: Int = -1
    private var appliedPreset: String? = presetNameOf(choreographer.activeTransition?.to ?: choreographer.currentState)
    private var appliedForm: String? = formNameOf(builder.form)
    private var appliedLook: Look? = builder.look

    /**
     * Apply [snapshot]'s step if it is not the one applied last. A wrapped sequence re-enters its
     * steps, so the cycle counts too.
     */
    fun apply(snapshot: LumosSequenceSnapshot) {
        if (snapshot.cycle == appliedCycle && snapshot.stepIndex == appliedStep) return
        appliedCycle = snapshot.cycle
        appliedStep = snapshot.stepIndex
        val step = snapshot.step
        val transition = step.transition
        var applied = false

        step.atmospherePreset?.lowercase()?.let { preset ->
            if (preset != appliedPreset) {
                appliedPreset = preset
                applied = true
                runtime.setAtmospherePreset(
                    preset,
                    transition?.let { AtmosphereTransitionSpec(it.durationSeconds, it.easingName) },
                )
            }
        }
        step.form?.lowercase()?.let { formName ->
            if (formName != appliedForm) {
                appliedForm = formName
                applied = true
                val form =
                    if (formName == LumosStep.LATTICE_FORM_NAME) {
                        Forms.Lattice((choreographer.activeTransition?.to ?: choreographer.currentState).resolution)
                    } else {
                        requireNotNull(Forms.byName(formName)) { "Unknown form: '$formName'" }
                    }
                if (transition == null) {
                    builder.morphTo(form)
                } else {
                    builder.morphTo(
                        form = form,
                        choreography = transition.choreography,
                        durationSeconds = transition.durationSeconds,
                        easingName = transition.easingName,
                    )
                }
            }
        }
        step.look?.let { look ->
            if (look != appliedLook) {
                appliedLook = look
                applied = true
                builder.lookTo(look, transition?.durationSeconds ?: VoxelFrameBuilder.LOOK_BLEND_SECONDS)
            }
        }
        step.glyph?.let { glyph ->
            val window = transition?.durationSeconds?.takeIf { it > 0f } ?: LiveFrameSource.DEFAULT_GLYPH_SECONDS
            builder.queueGlyph(glyph, window)
        }
        if (applied) source.noteChange()
    }

    private fun presetNameOf(state: AtmosphereState): String? =
        AtmospherePresets.ALL.firstOrNull { (_, preset) -> preset == state }?.first

    private fun formNameOf(form: LumosForm): String? =
        if (form is Forms.Lattice) {
            LumosStep.LATTICE_FORM_NAME
        } else {
            Forms.All.firstOrNull { (_, catalogued) -> catalogued === form }?.first
        }
}
