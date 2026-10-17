package link.socket.phosphor.field

import kotlin.math.sqrt

/**
 * Something acting on a [FormMorph]'s body besides its spring: added to
 * [FormMorph.forces] and applied, in list order, by every [FormMorph.step]
 * before the spring.
 *
 * A force is a transient push or a standing bend. It is not a target: the
 * spring still decides where each cell is heading, and a force only moves the
 * cells off that course or changes what the spring reads on the way.
 */
sealed interface Force {
    /**
     * A radial push out from the body's centroid: every cell is carried away
     * from it at [strength] units per second at the moment of firing, falling
     * linearly to nothing over [durationSeconds]. Dots' `SwarmPhysics.burst`,
     * which Dots fires before every scene morph so the body gathers itself
     * before it reshapes.
     *
     * The push is a velocity the cell rides on top of its spring, integrated
     * exactly over each step, so a cell carried through one long step lands
     * where a run of short ones would have put it. Left alone, it moves a cell
     * `strength × durationSeconds / 2` units; the spring pulls against it
     * throughout and brings the cell home afterward. The centroid is the
     * body's current one, taken once per step.
     *
     * A unit is the orb's radius: half the side of the square
     * `PixelLattice` projects into. So a pixel speed converts by dividing by
     * that radius in pixels. Dots' 380 px/s on a 1,000-dot pool 64 px in
     * radius is about 6 units/s.
     *
     * One instance is one firing. It keeps its own clock in [elapsedSeconds],
     * which a body moves on only while it is stepping, and is removed from
     * [FormMorph.forces] by the step that spends it. To fire again, add a new
     * one. Because the clock travels with the instance, a burst moved to
     * another body's [FormMorph.forces] carries on where it was.
     *
     * @property strength Outward speed at the moment of firing, units per
     *  second; negative pulls the body in.
     * @property durationSeconds How long the push lasts.
     * @throws IllegalArgumentException when [strength] is not finite or
     *  [durationSeconds] is not positive and finite.
     */
    class Burst(
        val strength: Float,
        val durationSeconds: Float = DEFAULT_DURATION_SECONDS,
    ) : Force {
        init {
            require(strength.isFinite()) { "strength must be finite, was $strength" }
            require(durationSeconds > 0f && durationSeconds.isFinite()) {
                "durationSeconds must be > 0, was $durationSeconds"
            }
        }

        /** How long this burst has been acting, advanced by [FormMorph.step]. */
        var elapsedSeconds: Float = 0f
            internal set

        /** Whether this burst has done all its pushing. */
        val isSpent: Boolean get() = elapsedSeconds >= durationSeconds

        /**
         * How far a cell is carried between [from] and [to] seconds into the
         * burst: the integral of `strength × (1 − t / duration)` over the
         * part of that span before the burst ends.
         */
        internal fun displacement(
            from: Float,
            to: Float,
        ): Float {
            if (from >= durationSeconds) return 0f
            val end = minOf(to, durationSeconds)
            return strength * ((end - from) - (end * end - from * from) / (2f * durationSeconds))
        }

        override fun toString(): String =
            "Burst(strength=$strength, durationSeconds=$durationSeconds, elapsedSeconds=$elapsedSeconds)"

        companion object {
            /** Dots' burst length: 0.16 s. */
            const val DEFAULT_DURATION_SECONDS: Float = 0.16f
        }
    }

    /**
     * A push on each cell's spring target, away from the pointer's line of
     * sight through the body: cells near where the person points give way, and
     * come home when the force is removed.
     *
     * For each cell, `d` is the distance from its form target to the aimed line,
     * taken before the choreography's arc bends the target. Inside [radius], the
     * target moves straight away from the line by `(radius − d) × strength`,
     * with the arc still added on top. A target exactly on the line has no
     * "away" and is left alone. These are Dots' `DotSwarm` numbers, applied to
     * the target and not to the position, so the spring carries the cell over
     * and the parting is as smooth as any trip.
     *
     * The caller aims in the body's own space along the view direction, so each
     * `d` equals the 2D view-space distance from the pointer and no cell needs
     * trig. Until [aim] is called, the force pushes nothing.
     *
     * One instance is re-aimed every frame without allocating, which is why it
     * is a class and not a data class: it carries its aim.
     *
     * @property radius How far from the line targets give way, in units: a unit
     *  is the orb's radius.
     * @property strength The fraction of `radius − d` a target gives way by.
     * @throws IllegalArgumentException when [radius] is not positive and finite,
     *  or [strength] is negative or not finite.
     */
    class Repel(
        val radius: Float = DEFAULT_RADIUS,
        val strength: Float = DEFAULT_STRENGTH,
    ) : Force {
        init {
            require(radius > 0f && radius.isFinite()) { "radius must be > 0, was $radius" }
            require(strength >= 0f && strength.isFinite()) { "strength must be >= 0, was $strength" }
        }

        /** Whether [aim] has been called: an unaimed repel pushes nothing. */
        var isAimed: Boolean = false
            private set

        // A point on the line, and its unit direction.
        internal var pointX: Float = 0f
            private set
        internal var pointY: Float = 0f
            private set
        internal var pointZ: Float = 0f
            private set
        internal var directionX: Float = 0f
            private set
        internal var directionY: Float = 0f
            private set
        internal var directionZ: Float = 0f
            private set

        /**
         * Aim along the pointer's line of sight through the body, in the body's
         * own space: ([x], [y], [z]) is a point on it and ([dx], [dy], [dz]) its
         * direction, of any length. Takes effect on the next [FormMorph.step].
         *
         * @throws IllegalArgumentException when any argument is not finite or
         *  the direction is zero.
         */
        fun aim(
            x: Float,
            y: Float,
            z: Float,
            dx: Float,
            dy: Float,
            dz: Float,
        ) {
            require(x.isFinite() && y.isFinite() && z.isFinite()) { "point must be finite, was ($x, $y, $z)" }
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            require(length > 0f && length.isFinite()) { "direction must be non-zero and finite, was ($dx, $dy, $dz)" }
            pointX = x
            pointY = y
            pointZ = z
            directionX = dx / length
            directionY = dy / length
            directionZ = dz / length
            isAimed = true
        }

        override fun toString(): String =
            if (isAimed) {
                "Repel(radius=$radius, strength=$strength, point=($pointX, $pointY, $pointZ), " +
                    "direction=($directionX, $directionY, $directionZ))"
            } else {
                "Repel(radius=$radius, strength=$strength, unaimed)"
            }

        companion object {
            /** Dots' repel radius, in orb radii. */
            const val DEFAULT_RADIUS: Float = 0.32f

            /** Dots' repel strength. */
            const val DEFAULT_STRENGTH: Float = 0.7f
        }
    }
}
