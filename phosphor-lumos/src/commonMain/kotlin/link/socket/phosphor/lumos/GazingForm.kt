package link.socket.phosphor.lumos

import link.socket.phosphor.math.Vector2

/**
 * A [LumosForm] that looks where the person points: sampled with the pointer as
 * well as the count, look and time, so a face can turn its eyes toward it.
 *
 * [VoxelFrameBuilder] hands it the snapshot's
 * [LumosInput.pointer][link.socket.phosphor.signal.LumosInput.pointer] wherever
 * it samples a live frame: in [VoxelFrameBuilder.build], and in
 * [VoxelFrameBuilder.morphTo] and [VoxelFrameBuilder.currentCount], which sample
 * with the last frame's. The gaze follows any pointer, as Dots' does, whether or
 * not the orb is [interactive][LumosRenderConfig.interactive]. A still and a
 * steady state sample with no gaze, so reduced motion never gazes. [counted]
 * forwards the gaze. A form that is also a [ViewForm] is sampled as a view
 * form, with the frame's rotation and no gaze.
 *
 * The builder rebuilds the look's modulator whenever the sample it is handed is
 * a new instance, and a new sample in the middle of a look blend lands the
 * blend. A form that resampled on every pointer move would rebuild its look's
 * tables every frame and cut every blend: bucket the gaze, or keep the sample
 * the same instance while the gaze moves.
 */
interface GazingForm : LumosForm {
    /**
     * The form's rest positions and derived fields, looking toward [gaze]. The
     * other parameters are [LumosForm.sample]'s.
     *
     * @param gaze Where the person points, in the orb's view space as
     *  [LumosInput.pointer][link.socket.phosphor.signal.LumosInput.pointer]
     *  gives it: `x` and `y` in `[-1, 1]` across the orb's square, `+y` up. Null
     *  when nothing points.
     */
    fun sample(
        count: Int,
        look: LumosLook,
        time: Float,
        gaze: Vector2?,
    ): FormSample

    /** The form with nothing to look at: [sample] with no gaze. */
    override fun sample(
        count: Int,
        look: LumosLook,
        time: Float,
    ): FormSample = sample(count, look, time, gaze = null)
}
