package link.socket.phosphor.field

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
     * A view-space push on each cell's spring target, away from a point the
     * pointer names: cells within [radius] of it give way.
     *
     * Declared here so PHO-64's pointer input lands on this seam instead of
     * adding a second mechanism. [FormMorph.step] does not apply it yet; it
     * sits in [FormMorph.forces] untouched until that work does, and that work
     * defines how the point is aimed and how [strength] scales.
     *
     * @property radius How far from the point cells give way, in units.
     * @property strength How hard they give way.
     */
    class Repel(
        val radius: Float = 0.32f,
        val strength: Float = 0.7f,
    ) : Force {
        override fun toString(): String = "Repel(radius=$radius, strength=$strength)"
    }
}
