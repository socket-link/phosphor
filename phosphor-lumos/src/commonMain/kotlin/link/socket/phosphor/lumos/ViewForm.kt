package link.socket.phosphor.lumos

import link.socket.phosphor.lumos.look.ViewRotation

/**
 * A form laid out in view space: the builder passes the frame's rotation so the
 * form can undo it.
 *
 * A form samples in body space, and the surfaces turn the body by the frame's
 * orb rotation at projection, so a plain form turns with the orb. A layout
 * element does not turn: a button sits where the layout put it. A view form
 * is handed the rotation the frame will be projected with — the atmosphere's,
 * or the look's own yaw when it owns one — and returns positions that rotation
 * carries back to where it wants them seen.
 *
 * [VoxelFrameBuilder] samples a view form wherever it samples a form, with the
 * rotation of the frame being built; every other form is sampled as it always
 * was. [LumosForm.counted] keeps a view form a view form.
 *
 * The contract of [LumosForm.sample] holds and tightens: the view rotation changes every
 * frame the orb turns, so when only the rotation has moved, return the same
 * [FormSample] instance with its positions rewritten in place. The builder
 * keys a look's tables on the sample's identity, and a new sample per frame
 * would rebuild them per frame. Return a new instance when anything else the
 * sample depends on changes. One builder per instance, since the sample is
 * shared state.
 */
interface ViewForm : FramedForm {
    /**
     * The form's rest positions, in body space, for a frame turned by [view].
     *
     * @param view The frame's rotation. The builder's own, valid only during
     *  this call: read it, never keep it.
     */
    fun sample(
        count: Int,
        look: LumosLook,
        time: Float,
        view: ViewRotation,
    ): FormSample

    /** The form's positions as laid out, unturned: [ViewRotation.NONE]. */
    override fun sample(
        count: Int,
        look: LumosLook,
        time: Float,
    ): FormSample = sample(count, look, time, ViewRotation.NONE)
}
