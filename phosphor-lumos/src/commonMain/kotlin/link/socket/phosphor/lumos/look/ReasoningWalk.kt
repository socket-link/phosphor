package link.socket.phosphor.lumos.look

import kotlin.math.floor
import kotlin.math.max
import link.socket.phosphor.lumos.FormSample
import link.socket.phosphor.lumos.Look

/**
 * Thinking Orbs' reasoning look: a lit walk hops between neighbouring cells.
 *
 * Every 220 ms ([HOP]) the head moves to one of its [REACH] nearest cells,
 * steered toward the viewer — the candidate with the highest view-space `z`,
 * plus a little [Looks.hash] noise — and never to one of the last [AVOID]
 * cells visited. The last [TAIL] heads are lit and fading, the head brightest;
 * the rest of the sphere rests at half opacity under the rim fade.
 *
 * The walk is a function of the clock: hop `k` is taken when `t` reaches
 * `k · HOP`. Moving forward replays the hops in between, so an orb that joins a
 * look's clock late walks the path its peers walked; moving backward starts the
 * walk over from its first cell and replays up to `k`. Steering during a replay
 * uses the current frame's rotation, so a long fast-forward lands on a cell the
 * live walk might not have; two orbs aligned on one clock replay the same hops
 * from the same place and agree.
 *
 * The walk starts at the cell nearest the viewer at zero rotation, the lowest
 * index among equals.
 */
internal class ReasoningWalk(
    sample: FormSample,
    private val nearest: NearestTable,
) : LookModulator {
    private val positions = sample.positions
    private val start: Int = frontmost(sample)
    private val lit = FloatArray(sample.count)

    /** The last [TAIL] heads, newest first; `-1` where the walk is too young. */
    private val tail = IntArray(TAIL) { -1 }

    /** The last [AVOID] cells left, as a ring. */
    private val recent = IntArray(AVOID) { -1 }
    private var recentAt = 0

    /** The cells [lit] currently holds, so [relight] clears only what it set. */
    private val litCells = IntArray(TAIL) { -1 }

    /** The cell the walk is on. */
    var head: Int = start
        private set

    /** The hop the walk has taken up to; hop 0 is the start cell. */
    var hop: Int = 0
        private set

    init {
        require(sample.count > 0) { "a walk needs at least one cell" }
        tail[0] = start
        relight()
    }

    override fun beginFrame(
        t: Float,
        rotation: ViewRotation,
    ) {
        val target = max(0f, floor(t / HOP)).toInt()
        if (target < hop) reset()
        if (target == hop) return
        while (hop < target) {
            hop++
            step(rotation)
        }
        relight()
    }

    override fun modulate(
        index: Int,
        view: ViewPoint,
        t: Float,
        out: CellModulation,
    ) {
        val glow = lit[index]
        out.lit = glow
        out.alpha = max(BaseSpin.rimAlpha(view.depth) * REST, glow)
    }

    /** Whether [cell] is one of the last [AVOID] cells the walk left. */
    fun avoids(cell: Int): Boolean {
        for (i in 0 until AVOID) if (recent[i] == cell) return true
        return false
    }

    private fun reset() {
        head = start
        hop = 0
        tail.fill(-1)
        tail[0] = start
        recent.fill(-1)
        recentAt = 0
    }

    private fun step(rotation: ViewRotation) {
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (slot in 0 until nearest.reach) {
            val candidate = nearest.neighbour(head, slot)
            if (candidate < 0) break
            if (avoids(candidate)) continue
            val position = positions[candidate]
            val facing = rotation.viewZ(position.x, position.y, position.z)
            val noise = (Looks.hash(hop * nearest.reach + slot + 1f) - 0.5f) * NOISE
            val score = facing + noise
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        if (best < 0) return
        recent[recentAt] = head
        recentAt = (recentAt + 1) % AVOID
        for (i in TAIL - 1 downTo 1) tail[i] = tail[i - 1]
        tail[0] = best
        head = best
    }

    private fun relight() {
        for (i in 0 until TAIL) {
            val was = litCells[i]
            if (was >= 0) lit[was] = 0f
        }
        for (i in 0 until TAIL) {
            val cell = tail[i]
            litCells[i] = cell
            if (cell >= 0) lit[cell] = 1f - i.toFloat() / TAIL
        }
    }

    companion object {
        /** A hop every 220 ms. */
        const val HOP: Float = Look.REASONING_PERIOD_SECONDS

        /** Candidates per hop: the nearest cells. */
        const val REACH: Int = 24

        /** Cells lit behind the head, head included. */
        const val TAIL: Int = 5

        /** Cells the walk will not return to. */
        const val AVOID: Int = 8

        /** The sphere's opacity away from the tail. */
        const val REST: Float = 0.5f

        /** How much hash noise joins the steering score, in view-space `z` units. */
        const val NOISE: Float = 0.35f

        private fun frontmost(sample: FormSample): Int {
            var best = 0
            var bestZ = Float.NEGATIVE_INFINITY
            for (i in 0 until sample.count) {
                val z = sample.positions[i].z
                if (z > bestZ) {
                    bestZ = z
                    best = i
                }
            }
            return best
        }
    }
}
