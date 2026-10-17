package link.socket.phosphor.field

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import link.socket.phosphor.math.Vector3

/**
 * A body of [count] cells springing from where they are to where a form says
 * they should be: the cytoskeleton under a reshaping orb.
 *
 * Each cell carries a position and a velocity and chases its destination with
 * an analytic critically damped spring — no overshoot, no oscillation, the
 * fastest approach that does neither. The destination is re-read every [step],
 * so a form that moves on its own is followed rather than frozen. A
 * [Choreography] bends the destination sideways for the first part of the trip
 * by an arc that swells and dies with a `sin²` envelope, so cells travel on a
 * curve rather than converging in straight lines. The arc's direction is a hash
 * of the cell's index that turns slowly with time, not a random draw: the same
 * morph plays the same way every time.
 *
 * Velocity is state, not something a morph resets: [retarget] in the middle of
 * a trip keeps every cell's momentum and lets the spring bring it round, which
 * is what makes an interrupted morph read as one motion. Only [snapTo] stops
 * the body dead — reduced motion and a zero duration go through it.
 *
 * [forces] act on the body alongside the spring: a [Force.Burst] carries every
 * cell out from the centroid for a moment and the spring brings it home, and a
 * [Force.Repel] parts the cells around where the person points. With the list
 * empty, [step] is exactly the spring and the arc.
 *
 * A body need not be travelling for forces to act on it. [snapTo] with an
 * assignment stands it at rest on its targets; with no trip in flight, [step]
 * still runs the spring while forces act or the body is [isDisturbed], so a
 * pushed cell comes home after the push stops. A body at rest, undisturbed and
 * with no forces does not move.
 *
 * Positions and velocities are the same `FloatArray`s for the body's whole life;
 * [step] allocates nothing.
 *
 * @param count Cells in the body; fixed. A form with another count needs another body.
 * @throws IllegalArgumentException when [count] is negative.
 */
class FormMorph(val count: Int) {
    init {
        require(count >= 0) { "count must be >= 0, was $count" }
    }

    /** Current positions, `x y z` interleaved: cell `i` is at `[3i], [3i + 1], [3i + 2]`. Read, don't write. */
    val positions: FloatArray = FloatArray(count * 3)

    /** Current velocities in units per second, laid out as [positions]. */
    val velocities: FloatArray = FloatArray(count * 3)

    // Per-cell arc amplitude for the current trip, in units; zero under DIRECT.
    private val arcAmplitude = FloatArray(count)

    // Per-cell arc direction: a hashed azimuth that turns with time, and a fixed hashed elevation.
    private val arcAzimuth = FloatArray(count) { hashToUnit(it, ARC_AZIMUTH_SALT) * TWO_PI }
    private val arcElevation = FloatArray(count) { (hashToUnit(it, ARC_ELEVATION_SALT) - 0.5f) * PI.toFloat() }

    private var targetIndexFor: IntArray? = null
    private var omega: Float = 0f
    private var arcEasing: ((Float) -> Float)? = null

    // Elapsed time a burst has kept the body settling until: zero when no burst has acted this trip.
    private var disturbedUntilSeconds: Float = 0f

    /**
     * Forces acting on the body, applied in order by every [step] before the
     * spring. Add to it to fire one; a [Force.Burst] removes itself on the step
     * that spends it, and a [Force.Repel] stays until it is removed.
     *
     * Forces act with or without a trip in flight: a body at rest steps while
     * this list is not empty. [snapTo] removes every burst: a body stopped dead
     * is not being pushed. It keeps every repel: the pointer is still there.
     */
    val forces: MutableList<Force> = ArrayList()

    /**
     * Whether forces have moved the body off its course and it is not yet
     * home: true from a [step] in which any force is in [forces], until a step
     * finds every cell within `1e-4` units of its undisplaced target — the
     * target with the arc but without any repel — and slower than `1e-3`
     * units per second. Computed in the same pass as the step.
     *
     * A trip on its own does not disturb the body; [isSettling] covers it, and
     * a body that has only travelled stops at the trip's end as it always has.
     * `1e-4` is under 0.06 px on a 1,080 px canvas, so a caller that drops the
     * body when this reads false, with no trip in flight and no forces, snaps
     * nothing visible. [snapTo] clears it.
     */
    var isDisturbed: Boolean = false
        private set

    /** Length of the current trip in seconds; `0` after [snapTo] or before any [retarget]. */
    var durationSeconds: Float = 0f
        private set

    /** Time into the current trip, advanced by [step]. */
    var elapsedSeconds: Float = 0f
        private set

    /** The choreography of the current trip. */
    var choreography: Choreography = Choreography.DIRECT
        private set

    /**
     * Whether the trip is still in flight: `elapsed < duration`, or a
     * [Force.Burst] has acted and the spring has not yet had a whole trip's
     * span since it ended to bring the cells home.
     */
    val isSettling: Boolean get() = elapsedSeconds < max(durationSeconds, disturbedUntilSeconds)

    /**
     * How far along the trip a cell that started at rest would be, `0` to `1`:
     * the critically damped step response `1 − e^(−ωt)(1 + ωt)`, `1` once
     * settled. A caller blending a scalar alongside the body — a form's framing
     * scale, say — uses this so the scalar moves like the cells do.
     */
    val progress: Float
        get() {
            if (!isSettling) return 1f
            val wt = omega * elapsedSeconds
            return 1f - exp(-wt) * (1f + wt)
        }

    /**
     * Put every cell at its target and stop it. Ends any trip in flight and
     * removes every [Force.Burst] from [forces]: the body is settled, at rest
     * and undisturbed, and [isSettling] and [isDisturbed] are false.
     *
     * The body then stands on its targets: a later [step] reads [targets]
     * through the same [assignment], so a force added to the resting body
     * pushes cells off the right targets and the spring brings them back.
     *
     * @param targets Destinations read through [assignment]; the same list, or
     *  the same form re-sampled, is passed again to every [step].
     * @param assignment `count` target indices, cell `i` standing on
     *  `targets[assignment[i]]`; null means cell `i` stands on `targets[i]`.
     * @throws IllegalArgumentException when [assignment] is not `count` long or
     *  indexes outside [targets], or [assignment] is null and [targets] is not
     *  `count` long.
     */
    fun snapTo(
        targets: List<Vector3>,
        assignment: IntArray? = null,
    ) {
        requireAssignment(targets, assignment)
        for (i in 0 until count) {
            val t = targets[assignment?.get(i) ?: i]
            positions[3 * i] = t.x
            positions[3 * i + 1] = t.y
            positions[3 * i + 2] = t.z
        }
        velocities.fill(0f)
        arcAmplitude.fill(0f)
        targetIndexFor = assignment
        durationSeconds = 0f
        elapsedSeconds = 0f
        disturbedUntilSeconds = 0f
        isDisturbed = false
        choreography = Choreography.DIRECT
        arcEasing = null
        forces.removeAll { it is Force.Burst }
    }

    /**
     * Start a trip toward [targets], keeping every cell's current position and
     * velocity. A trip already in flight is replaced from here, momentum and all,
     * and [forces] stay: a burst still pushing goes on pushing.
     *
     * @param targets Destinations the trip reads through [assignment]; the same
     *  list, or the same form re-sampled, is passed again to every [step].
     * @param assignment `count` target indices, cell `i` heading for
     *  `targets[assignment[i]]`; null means cell `i` heads for `targets[i]`.
     * @param choreography The arc shape; see [Choreography].
     * @param durationSeconds Trip length. `0` snaps. The spring's stiffness is
     *  `7.5 × 2.4 / max(0.08, duration)`, so a shorter trip is a stiffer spring.
     * @param arcEasing Reshapes the arc envelope's time, `0..1` to `0..1`; null
     *  leaves it linear. The spring is not eased: the spring is the motion.
     * @throws IllegalArgumentException when [durationSeconds] is negative, or
     *  [assignment] is not `count` long or indexes outside [targets], or
     *  [assignment] is null and [targets] is not `count` long.
     */
    fun retarget(
        targets: List<Vector3>,
        assignment: IntArray? = null,
        choreography: Choreography = Choreography.FLOW,
        durationSeconds: Float,
        arcEasing: ((Float) -> Float)? = null,
    ) {
        require(
            durationSeconds >= 0f && durationSeconds.isFinite(),
        ) { "durationSeconds must be >= 0, was $durationSeconds" }
        requireAssignment(targets, assignment)
        if (durationSeconds == 0f) {
            snapTo(targets, assignment)
            return
        }
        this.targetIndexFor = assignment
        this.choreography = choreography
        this.durationSeconds = durationSeconds
        this.elapsedSeconds = 0f
        this.disturbedUntilSeconds = 0f
        this.arcEasing = arcEasing
        omega = STIFFNESS / max(MIN_DURATION_SECONDS, durationSeconds)
        for (i in 0 until count) {
            val target = targets[assignment?.get(i) ?: i]
            arcAmplitude[i] =
                when (choreography) {
                    Choreography.DIRECT -> 0f
                    Choreography.SCATTER -> SCATTER_ARC
                    Choreography.FLOW -> {
                        val dx = target.x - positions[3 * i]
                        val dy = target.y - positions[3 * i + 1]
                        val dz = target.z - positions[3 * i + 2]
                        min(FLOW_ARC_MAX, sqrt(dx * dx + dy * dy + dz * dz) * FLOW_ARC_PER_UNIT)
                    }
                }
        }
    }

    /**
     * Advance the body by [dt] seconds toward [targets], read through the
     * assignment given to [retarget] or [snapTo], with [forces] acting first.
     * Does nothing to a body with no trip in flight, no forces, and not
     * [isDisturbed]. Allocates nothing.
     *
     * With no trip in flight, the spring runs at the default 2.4 s trip's
     * stiffness, `ω = 7.5`, with no arc, and [elapsedSeconds] stands still: the
     * trip's clock is the trip's.
     *
     * A [Force.Burst] moves each cell along the line from the body's centroid,
     * taken once before anything moves, by the distance the burst carries it
     * over this step: half before the spring steps and half after, so the
     * spring answers the push across the step rather than all at its start.
     * While a burst acts, the trip is held open until the spring has had a
     * whole trip's span after the burst ends, so the cells are home before the
     * body settles. A burst on a body with no trip in flight opens no trip;
     * [isDisturbed] says when its cells are home.
     *
     * A [Force.Repel] moves each cell's target, not the cell: see its KDoc.
     *
     * @throws IllegalArgumentException when [dt] is negative, or [targets] is
     *  too short for the assignment.
     */
    fun step(
        dt: Float,
        targets: List<Vector3>,
    ) {
        require(dt >= 0f) { "dt must be >= 0, was $dt" }
        val inTrip = isSettling
        if (!inTrip && forces.isEmpty() && !isDisturbed) return
        val assignment = targetIndexFor
        requireAssignment(targets, assignment)

        val omega = if (inTrip) this.omega else REST_OMEGA
        if (inTrip) elapsedSeconds += dt
        val envelope = if (inTrip) arcEnvelope() else 0f
        val swirl = elapsedSeconds * ARC_TURN_PER_SECOND
        val decay = exp(-omega * dt)

        // A trip alone does not disturb the body: only forces, or a disturbance not yet home.
        val tracking = forces.isNotEmpty() || isDisturbed
        var disturbed = false

        val push = if (forces.isEmpty()) 0f else advanceBursts(dt, inTrip)
        var cx = 0f
        var cy = 0f
        var cz = 0f
        if (push != 0f) {
            for (i in 0 until count) {
                cx += positions[3 * i]
                cy += positions[3 * i + 1]
                cz += positions[3 * i + 2]
            }
            cx /= count
            cy /= count
            cz /= count
        }

        for (i in 0 until count) {
            val target = targets[assignment?.get(i) ?: i]
            var tx = target.x
            var ty = target.y
            var tz = target.z
            val arc = arcAmplitude[i] * envelope
            if (arc != 0f) {
                val azimuth = arcAzimuth[i] + swirl
                val elevation = arcElevation[i]
                val flat = cos(elevation)
                tx += cos(azimuth) * flat * arc
                ty += sin(elevation) * arc
                tz += sin(azimuth) * flat * arc
            }
            // The undisplaced target, for isDisturbed: the arc in, no repel.
            val homeX = tx
            val homeY = ty
            val homeZ = tz
            for (k in 0 until forces.size) {
                val repel = forces[k] as? Force.Repel ?: continue
                if (!repel.isAimed) continue
                // d is measured from the form target, before the arc.
                val wx = target.x - repel.pointX
                val wy = target.y - repel.pointY
                val wz = target.z - repel.pointZ
                val along = wx * repel.directionX + wy * repel.directionY + wz * repel.directionZ
                val qx = wx - along * repel.directionX
                val qy = wy - along * repel.directionY
                val qz = wz - along * repel.directionZ
                val d = sqrt(qx * qx + qy * qy + qz * qz)
                // On the line there is no "away": the target stays.
                if (d < repel.radius && d > 0f) {
                    val scale = (repel.radius - d) * repel.strength / d
                    tx += qx * scale
                    ty += qy * scale
                    tz += qz * scale
                }
            }
            var px = 0f
            var py = 0f
            var pz = 0f
            if (push != 0f) {
                val dx = positions[3 * i] - cx
                val dy = positions[3 * i + 1] - cy
                val dz = positions[3 * i + 2] - cz
                val length = sqrt(dx * dx + dy * dy + dz * dz)
                // A cell at the centroid has no outward; it stays where it is.
                if (length > CENTROID_EPSILON) {
                    val half = push * 0.5f / length
                    px = dx * half
                    py = dy * half
                    pz = dz * half
                    positions[3 * i] += px
                    positions[3 * i + 1] += py
                    positions[3 * i + 2] += pz
                }
            }
            springStep(3 * i, tx, dt, decay, omega)
            springStep(3 * i + 1, ty, dt, decay, omega)
            springStep(3 * i + 2, tz, dt, decay, omega)
            if (push != 0f) {
                positions[3 * i] += px
                positions[3 * i + 1] += py
                positions[3 * i + 2] += pz
            }
            if (tracking && !disturbed) {
                val ox = positions[3 * i] - homeX
                val oy = positions[3 * i + 1] - homeY
                val oz = positions[3 * i + 2] - homeZ
                val vx = velocities[3 * i]
                val vy = velocities[3 * i + 1]
                val vz = velocities[3 * i + 2]
                disturbed = ox * ox + oy * oy + oz * oz > HOME_DISTANCE * HOME_DISTANCE ||
                    vx * vx + vy * vy + vz * vz > HOME_SPEED * HOME_SPEED
            }
        }
        if (tracking) isDisturbed = disturbed
    }

    /**
     * Move every [Force.Burst]'s clock on by [dt], drop the ones this step
     * spends, hold the trip open behind them when one is [inTrip], and return
     * how far they carry a cell outward this step, summed.
     */
    private fun advanceBursts(
        dt: Float,
        inTrip: Boolean,
    ): Float {
        var push = 0f
        // The spring's own settling span: the trip length its stiffness was set for.
        val settleSpan = if (inTrip) STIFFNESS / omega else 0f
        var k = 0
        while (k < forces.size) {
            val burst = forces[k] as? Force.Burst
            if (burst == null) {
                k++
                continue
            }
            val from = burst.elapsedSeconds
            burst.elapsedSeconds = from + dt
            push += burst.displacement(from, burst.elapsedSeconds)
            if (inTrip) {
                val remaining = max(0f, burst.durationSeconds - burst.elapsedSeconds)
                disturbedUntilSeconds = max(disturbedUntilSeconds, elapsedSeconds + remaining + settleSpan)
            }
            if (burst.isSpent) forces.removeAt(k) else k++
        }
        return push
    }

    /** `sin²(πu)` over the first `0.7083` of the trip, `u` optionally eased; `0` once that window closes. */
    private fun arcEnvelope(): Float {
        // No trip, no arc: guards the division at a zero duration.
        if (durationSeconds == 0f) return 0f
        val window = durationSeconds * ARC_WINDOW_FRACTION
        val u = min(1f, elapsedSeconds / window)
        val eased = arcEasing?.invoke(u) ?: u
        val s = sin(eased * PI.toFloat())
        return s * s
    }

    // The closed-form critically damped step: exact for any dt, so a dropped frame lands where a
    // run of short frames would have.
    private fun springStep(
        at: Int,
        target: Float,
        dt: Float,
        decay: Float,
        omega: Float,
    ) {
        val offset = positions[at] - target
        val velocity = velocities[at]
        val reach = (velocity + omega * offset) * dt
        positions[at] = target + (offset + reach) * decay
        velocities[at] = (velocity - reach * omega) * decay
    }

    private fun requireAssignment(
        targets: List<Vector3>,
        assignment: IntArray?,
    ) {
        if (assignment == null) {
            require(targets.size == count) { "expected $count targets, got ${targets.size}" }
            return
        }
        require(assignment.size == count) { "expected $count assignments, got ${assignment.size}" }
        for (i in 0 until count) {
            val j = assignment[i]
            require(j in targets.indices) { "cell $i is assigned target $j, outside ${targets.size} targets" }
        }
    }

    companion object {
        /** Dots' spring: `ω = 7.5 × (2.4 / duration)`, so the default 2.4 s trip has `ω = 7.5`. */
        private const val STIFFNESS: Float = 7.5f * 2.4f

        /** The spring with no trip in flight: the default 2.4 s trip's, `ω = 7.5`. */
        private const val REST_OMEGA: Float = STIFFNESS / 2.4f

        // isDisturbed's thresholds: a cell is home within HOME_DISTANCE units and under HOME_SPEED units/s.
        private const val HOME_DISTANCE: Float = 1e-4f
        private const val HOME_SPEED: Float = 1e-3f
        private const val MIN_DURATION_SECONDS: Float = 0.08f
        private const val SCATTER_ARC: Float = 0.55f
        private const val FLOW_ARC_MAX: Float = 0.2f
        private const val FLOW_ARC_PER_UNIT: Float = 0.13f
        private const val ARC_WINDOW_FRACTION: Float = 0.7083f
        private const val ARC_TURN_PER_SECOND: Float = 0.4f
        private const val ARC_AZIMUTH_SALT: Int = 82
        private const val ARC_ELEVATION_SALT: Int = 0x43
        private const val CENTROID_EPSILON: Float = 1e-6f
        private val TWO_PI: Float = (2.0 * PI).toFloat()

        /** The lattice's FNV-seeded hash of a cell index and a salt, folded to `[0, 1)`. */
        internal fun hashToUnit(
            index: Int,
            salt: Int,
        ): Float {
            var hash = 0x811C9DC5.toInt()
            hash = (hash xor index) * 0x01000193
            hash = (hash xor salt) * 0x01000193
            hash = hash xor (hash ushr 16)
            hash *= 0x7FEB352D
            hash = hash xor (hash ushr 15)
            hash *= 0x846CA68B.toInt()
            hash = hash xor (hash ushr 16)
            return (hash ushr 1) / Int.MAX_VALUE.toFloat()
        }
    }
}
