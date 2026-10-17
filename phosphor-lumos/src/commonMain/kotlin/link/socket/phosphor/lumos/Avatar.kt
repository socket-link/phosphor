package link.socket.phosphor.lumos

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.math.Vector3

/** How an [Avatar]'s eyes are shaped and set; Dots' expressions, eyes only. */
enum class AvatarExpression { NEUTRAL, HAPPY, CURIOUS, SURPRISED, EXCITED, DIZZY }

/**
 * PHO-67 Task A: a face as a form. A Fibonacci body with two eyes cut out of its
 * `+z` face, implemented as a [GazingForm] and nothing else, so the morph, the
 * looks, the styles and the materials apply to it as they apply to any form.
 *
 * The eyes are *holes*: a body cell that falls inside an eye disc is pushed out
 * to the disc's rim, and a ring of reserved cells sits on each rim at a tighter
 * spacing than the body, so the hole has a crisp edge in every style and the
 * background shows through as the eye. Nothing in the pipeline knows about
 * eyes. The disc is cut in the face's screen space, straight through the orb:
 * the far hemisphere shows through the near one's gaps, so a hole in the front
 * alone fills with the cells behind it.
 *
 * The form is [rigid] by default. The IDLE atmosphere's jitter moves a cell by
 * up to about a quarter of the orb's radius, more than an eye is wide, so the
 * face is only legible with the skin held off; `rigid = false` wears the skin
 * and is here so the two can be compared.
 *
 * The eye cells are the *first* indices of the sample, then the body in
 * Fibonacci order from the top; a morph from a Fibonacci-like form matches them
 * to the nearest body cells, so a face morphed in keeps its identity.
 *
 * `gaze` slides both eyes toward the pointer, by up to
 * [GAZE_REACH] of the eye radius, in steps of [GAZE_STEP] so the sample is not
 * rebuilt on every pointer move. `tension` squashes the body in `y` by
 * `1 − 0.15·tension`. `time` is not read yet: breathing, the blink and the
 * tremble are PHO-67 Task C, and each means a sample per frame, which is the
 * question Task A raises.
 *
 * @property expression Which eyes; see [AvatarExpression].
 * @property tension Anticipation, `0..1`; squashes the face.
 * @property eyeScale Multiplies the eye radius; `1` is Dots' default.
 * @property eyeSpacing Multiplies how far the eyes sit from the centre line.
 */
data class Avatar(
    val expression: AvatarExpression = AvatarExpression.NEUTRAL,
    val tension: Float = 0f,
    val eyeScale: Float = 1f,
    val eyeSpacing: Float = 1f,
    override val rigid: Boolean = true,
) : GazingForm,
    FramedForm {
    init {
        require(tension in 0f..1f) { "tension must be in 0..1, was $tension" }
        require(eyeScale > 0f) { "eyeScale must be > 0, was $eyeScale" }
        require(eyeSpacing > 0f) { "eyeSpacing must be > 0, was $eyeSpacing" }
    }

    // One immutable holder swapped as a whole, so a stale read costs a rebuild, never a torn sample.
    private var cached: Cached? = null

    /** Cells reserved for the two eye rims at [count] cells; the rest are body. */
    fun eyeCellCount(count: Int): Int = 2 * ringCells(count)

    override fun sample(
        count: Int,
        look: LumosLook,
        time: Float,
        gaze: Vector2?,
    ): FormSample {
        require(count >= 1) { "count must be >= 1, was $count" }
        val gazeX = bucket(gaze?.x ?: 0f)
        val gazeY = bucket(gaze?.y ?: 0f)
        cached?.let { if (it.count == count && it.gazeX == gazeX && it.gazeY == gazeY) return it.sample }
        val sample = FormSample.derive(positions(count, gazeX, gazeY), radiusScale = Forms.DISTANCE_SCALE)
        cached = Cached(count, gazeX, gazeY, sample)
        return sample
    }

    // Both supertypes say what this one is: the face with nothing to look at.
    override fun sample(
        count: Int,
        look: LumosLook,
        time: Float,
    ): FormSample = sample(count, look, time, gaze = null)

    private fun positions(
        count: Int,
        gazeX: Float,
        gazeY: Float,
    ): List<Vector3> {
        val shape = expression.shape
        val ring = ringCells(count)
        val bodyCount = max(0, count - 2 * ring)
        val spacing = bodySpacing(count)
        // No rim cells means no face: too few cells to cut eyes into, so the body is left whole.
        val eyes =
            if (ring == 0) {
                emptyList()
            } else {
                listOf(
                    eye(shape, side = -1f, gazeX, gazeY),
                    eye(shape, side = 1f, gazeX, gazeY),
                )
            }
        val squashY = 1f - TENSION_SQUASH * tension

        val positions = ArrayList<Vector3>(count)
        // Eye rims first, at RIM_SPACING of the body's spacing, so they outline the hole.
        for (eye in eyes) {
            for (i in 0 until ring) {
                val angle = 2f * PI.toFloat() * i / ring
                positions += eye.onRim(cos(angle), sin(angle), squashY)
            }
        }
        // The body: a Fibonacci sphere, each cell that lands in an eye pushed out to the rim's margin.
        val margin = 1f + RIM_MARGIN * spacing / sin(EYE_RADIUS * eyeScale)
        for (i in 0 until bodyCount) {
            val y = 1f - 2f * (i + 0.5f) / bodyCount
            val r = sqrt((1f - y * y).coerceAtLeast(0f))
            val theta = i * GOLDEN_ANGLE
            var direction = Vector3(cos(theta) * r, y, sin(theta) * r)
            for (eye in eyes) direction = eye.pushedOut(direction, margin, i)
            positions += Vector3(direction.x, direction.y * squashY, direction.z)
        }
        return positions
    }

    /** Cells on each eye's rim: its circumference at [RIM_SPACING] of the body's spacing, at least [MIN_RING]. */
    private fun ringCells(count: Int): Int {
        if (count < 2 * MIN_RING + MIN_RING) return 0
        val shape = expression.shape
        val radius = sin(EYE_RADIUS * eyeScale * (shape.radiusX + shape.radiusY) * 0.5f)
        val circumference = 2f * PI.toFloat() * radius
        val cells = (circumference / (bodySpacing(count) * RIM_SPACING)).roundToInt()
        return max(MIN_RING, cells).coerceAtMost(count / 4)
    }

    /** Mean spacing of [count] cells spread over the unit sphere's surface. */
    private fun bodySpacing(count: Int): Float = sqrt(UNIT_SPHERE_AREA / count.coerceAtLeast(1))

    private fun eye(
        shape: EyeShape,
        side: Float,
        gazeX: Float,
        gazeY: Float,
    ): Eye {
        val right = side > 0f
        val yaw = side * EYE_YAW * eyeSpacing * shape.spacing + gazeX * GAZE_REACH * EYE_RADIUS * eyeScale
        val pitch =
            EYE_PITCH + shape.pitch + (if (right) shape.rightLift else -shape.rightLift) +
                gazeY * GAZE_REACH * EYE_RADIUS * eyeScale
        val scale = EYE_RADIUS * eyeScale * (if (right) shape.rightScale else 1f)
        return Eye(yaw, pitch, sin(scale * shape.radiusX), sin(scale * shape.radiusY))
    }

    override fun toString(): String =
        "Avatar(expression=$expression, tension=$tension, eyeScale=$eyeScale, eyeSpacing=$eyeSpacing, rigid=$rigid)"

    /**
     * One eye, cut in the face's screen space: a centre on the `+z` face and
     * the half-axes of its ellipse in `x` and `y`, straight through the orb.
     */
    private class Eye(
        yaw: Float,
        pitch: Float,
        private val halfWidth: Float,
        private val halfHeight: Float,
    ) {
        private val centreX = sin(yaw) * cos(pitch)
        private val centreY = sin(pitch)

        /** The rim point at ellipse angle (`cosA`, `sinA`) on the near face, squashed in `y`. */
        fun onRim(
            cosA: Float,
            sinA: Float,
            squashY: Float,
        ): Vector3 {
            val x = centreX + halfWidth * cosA
            val y = centreY + halfHeight * sinA
            return Vector3(x, y * squashY, sqrt((1f - x * x - y * y).coerceAtLeast(0f)))
        }

        /**
         * [direction], unless its screen position falls inside this eye grown by
         * [margin], in which case it is pushed straight out to the grown rim on
         * its own side of the orb. A cell at the very centre has no outward
         * direction, so it takes one from its index.
         */
        fun pushedOut(
            direction: Vector3,
            margin: Float,
            index: Int,
        ): Vector3 {
            val halfA = halfWidth * margin
            val halfB = halfHeight * margin
            var a = (direction.x - centreX) / halfA
            var b = (direction.y - centreY) / halfB
            var e = a * a + b * b
            if (e >= 1f) return direction
            if (e < 1e-6f) {
                val angle = index * GOLDEN_ANGLE
                a = cos(angle) * 1e-3f
                b = sin(angle) * 1e-3f
                e = a * a + b * b
            }
            val scale = 1f / sqrt(e)
            var x = centreX + a * scale * halfA
            var y = centreY + b * scale * halfB
            // A rim grown past the silhouette: hold the cell on the silhouette instead.
            val reach = sqrt(x * x + y * y)
            if (reach > 1f) {
                x /= reach
                y /= reach
            }
            val z = sqrt((1f - x * x - y * y).coerceAtLeast(0f))
            return Vector3(x, y, if (direction.z < 0f) -z else z)
        }
    }

    private class Cached(val count: Int, val gazeX: Float, val gazeY: Float, val sample: FormSample)

    /** Per-expression eye geometry, as multiples of the NEUTRAL eye. */
    private class EyeShape(
        val radiusX: Float = 1f,
        val radiusY: Float = 1f,
        val spacing: Float = 1f,
        val pitch: Float = 0f,
        val rightScale: Float = 1f,
        val rightLift: Float = 0f,
    )

    private val AvatarExpression.shape: EyeShape
        get() =
            when (this) {
                AvatarExpression.NEUTRAL -> NEUTRAL_SHAPE
                AvatarExpression.HAPPY -> EyeShape(radiusX = 1.1f, radiusY = 0.5f, pitch = 0.04f)
                AvatarExpression.CURIOUS -> EyeShape(rightScale = 1.35f, rightLift = 0.03f)
                AvatarExpression.SURPRISED -> EyeShape(radiusX = 1.3f, radiusY = 1.35f, spacing = 1.1f, pitch = 0.04f)
                AvatarExpression.EXCITED -> EyeShape(radiusX = 1.2f, radiusY = 1.2f, spacing = 0.9f)
                AvatarExpression.DIZZY -> EyeShape(radiusX = 0.9f, radiusY = 1.1f, rightLift = 0.09f)
            }

    companion object {
        /** How far toward the pointer the eyes slide at full gaze, in eye radii. */
        const val GAZE_REACH: Float = 0.15f

        /** The gaze is bucketed to this step so the sample holds while the pointer drifts. */
        const val GAZE_STEP: Float = 0.1f

        /** How much `y` is squashed at full tension. */
        const val TENSION_SQUASH: Float = 0.15f

        /** The eyes' angular radius from their centre, in radians, at `eyeScale` 1. */
        const val EYE_RADIUS: Float = 0.21f

        /** Each eye's yaw off the centre line, in radians, at `eyeSpacing` 1. */
        const val EYE_YAW: Float = 0.42f

        /** The eyes' pitch above the equator, in radians. */
        const val EYE_PITCH: Float = 0.18f

        /** Rim cells sit this fraction of the body's spacing apart, so the rim reads tighter than the body. */
        private const val RIM_SPACING: Float = 0.7f

        /** Body cells are kept this many body spacings clear of the rim. */
        private const val RIM_MARGIN: Float = 0.6f

        private const val MIN_RING: Int = 6

        private val NEUTRAL_SHAPE = EyeShape()

        private val UNIT_SPHERE_AREA: Float = (4.0 * PI).toFloat()

        /** The golden angle, `π(3 − √5)`, in radians. */
        private val GOLDEN_ANGLE: Float = (PI * (3.0 - sqrt(5.0))).toFloat()

        private fun bucket(value: Float): Float = (value.coerceIn(-1f, 1f) / GAZE_STEP).roundToInt() * GAZE_STEP
    }
}
