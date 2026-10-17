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

    /** Length of the current trip in seconds; `0` after [snapTo] or before any [retarget]. */
    var durationSeconds: Float = 0f
        private set

    /** Time into the current trip, advanced by [step]. */
    var elapsedSeconds: Float = 0f
        private set

    /** The choreography of the current trip. */
    var choreography: Choreography = Choreography.DIRECT
        private set

    /** Whether the trip is still in flight: `elapsed < duration`. */
    val isSettling: Boolean get() = elapsedSeconds < durationSeconds

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
     * Put every cell at its target and stop it. Ends any trip in flight: the
     * body is settled and [isSettling] is false.
     *
     * @param targets `count` destinations; cell `i` goes to `targets[i]`.
     * @throws IllegalArgumentException when [targets] is not `count` long.
     */
    fun snapTo(targets: List<Vector3>) {
        require(targets.size == count) { "expected $count targets, got ${targets.size}" }
        for (i in 0 until count) {
            val t = targets[i]
            positions[3 * i] = t.x
            positions[3 * i + 1] = t.y
            positions[3 * i + 2] = t.z
        }
        velocities.fill(0f)
        arcAmplitude.fill(0f)
        targetIndexFor = null
        durationSeconds = 0f
        elapsedSeconds = 0f
        choreography = Choreography.DIRECT
        arcEasing = null
    }

    /**
     * Start a trip toward [targets], keeping every cell's current position and
     * velocity. A trip already in flight is replaced from here, momentum and all.
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
            snapTo(List(count) { targets[assignment?.get(it) ?: it] })
            return
        }
        this.targetIndexFor = assignment
        this.choreography = choreography
        this.durationSeconds = durationSeconds
        this.elapsedSeconds = 0f
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
     * Advance the trip by [dt] seconds toward [targets], read through the
     * assignment given to [retarget]. Does nothing to a settled body. Allocates
     * nothing.
     *
     * @throws IllegalArgumentException when [dt] is negative, or [targets] is
     *  too short for the assignment.
     */
    fun step(
        dt: Float,
        targets: List<Vector3>,
    ) {
        require(dt >= 0f) { "dt must be >= 0, was $dt" }
        if (!isSettling) return
        val assignment = targetIndexFor
        requireAssignment(targets, assignment)

        elapsedSeconds += dt
        val envelope = arcEnvelope()
        val swirl = elapsedSeconds * ARC_TURN_PER_SECOND
        val decay = exp(-omega * dt)

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
            springStep(3 * i, tx, dt, decay)
            springStep(3 * i + 1, ty, dt, decay)
            springStep(3 * i + 2, tz, dt, decay)
        }
    }

    /** `sin²(πu)` over the first `0.7083` of the trip, `u` optionally eased; `0` once that window closes. */
    private fun arcEnvelope(): Float {
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
        private const val MIN_DURATION_SECONDS: Float = 0.08f
        private const val SCATTER_ARC: Float = 0.55f
        private const val FLOW_ARC_MAX: Float = 0.2f
        private const val FLOW_ARC_PER_UNIT: Float = 0.13f
        private const val ARC_WINDOW_FRACTION: Float = 0.7083f
        private const val ARC_TURN_PER_SECOND: Float = 0.4f
        private const val ARC_AZIMUTH_SALT: Int = 82
        private const val ARC_ELEVATION_SALT: Int = 0x43
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
