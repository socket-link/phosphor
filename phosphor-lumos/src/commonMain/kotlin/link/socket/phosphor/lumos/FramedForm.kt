package link.socket.phosphor.lumos

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * A [LumosForm] that says how it is framed: how much larger than the orb it is
 * drawn, how far from above it is meant to be seen, and how big its cells are.
 *
 * Thinking Orbs frames each shape with a `scale` and a `tip`. A solid whose
 * corners touch the unit sphere fills less of the frame than a sphere does, so
 * it is drawn at 1.2; a torus reads as a ring from straight on and as a torus
 * from 35° above. The lattice is not framed: it is the frame everything else
 * is measured against.
 *
 * [VoxelFrameBuilder] reads [scale], [cellRadius] and [rigid] on every build,
 * so an implementation must answer them without allocating. [tipDegrees] is
 * declared here so a catalogue form can carry it, and is read by nothing yet:
 * the tip is a view-space rotation that has to come *after* the orb's own
 * spin, which is the projection's job, and PHO-58's `ViewPoint` is where it
 * lands. Until then a host that wants the tip passes it as
 * `LumosConfig.tiltDegrees`.
 */
interface FramedForm : LumosForm {
    /**
     * How much larger than the unit sphere the form is drawn. `1` draws the
     * form as sampled; a solid inscribed in the sphere uses `1.2`, Thinking
     * Orbs' tuning, so its faces reach the frame its corners already touch.
     */
    val scale: Float get() = 1f

    /**
     * How far above the equator the form asks to be seen from, in degrees;
     * positive tips the top toward the viewer. `0` looks straight on.
     */
    val tipDegrees: Float get() = 0f

    /**
     * Half the gap between neighbouring cells at [count] cells, in unit-sphere
     * space: the radius a renderer draws a cell at when `scale` is 1, before
     * the atmosphere's gap and pulse.
     *
     * The lattice's cells sit `1 / resolution` apart, so its half-gap is
     * `0.5 / resolution` and the builder scales every other form's cells by
     * `cellRadius(count) · 2 · resolution` to keep the one dot-size rule
     * surfaces already apply. The default is half the mean spacing of [count]
     * points spread evenly over the unit sphere's surface, `0.5 · √(4π / count)`,
     * which a surface form inherits as is; a form over a smaller surface
     * overrides it with its own spacing.
     */
    fun cellRadius(count: Int): Float = 0.5f * sqrt(UNIT_SPHERE_AREA / count.coerceAtLeast(1))

    /**
     * Hold the atmosphere's jitter, bump, pulse and squash off this form.
     *
     * On a sphere the skin is the point; on a layout element it blurs the edge
     * into a blob. A rigid form is drawn where it was sampled: the builder
     * blends the four terms out by a morph's own progress, the way it blends
     * [scale], so the skin fades as the body arrives and back in when the orb
     * is morphed to a form that is not rigid. At rest the four terms are zero.
     * The atmosphere itself is untouched, so a trace records what it was.
     * `false` by default, which leaves every frame as it was.
     */
    val rigid: Boolean get() = false

    companion object {
        private val UNIT_SPHERE_AREA: Float = (4.0 * PI).toFloat()
    }
}
