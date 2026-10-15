package link.socket.phosphor.lumos.look

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook

/**
 * PHO-58: the four idioms at known angles, straight from Thinking Orbs' constants.
 */
class IdiomsTest {
    private val still = ViewRotation().apply { set(0f, 0f, 0f, 0f) }

    @Test
    fun `hash is Thinking Orbs' fract-sin, in the unit interval, and zero at zero`() {
        assertEquals(0f, Looks.hash(0f))
        for (n in 1..200) {
            val h = Looks.hash(n.toFloat())
            assertTrue(h >= 0f && h < 1f, "hash($n) = $h")
        }
        assertNotEquals(Looks.hash(1f), Looks.hash(2f))
        assertEquals(Looks.hash(7f), Looks.hash(7f), "deterministic")
    }

    @Test
    fun `base spin ramps opacity from the rim and radius from a quarter at the back`() {
        val spin = BaseSpin(radiusRamp = true)

        val front = modulate(spin, view(0f, 0f, 1f), 0f)
        assertEquals(1f, front.alpha, TOLERANCE, "front is opaque")
        assertEquals(1f, front.radius, TOLERANCE, "front keeps its size")

        val rim = modulate(spin, view(0f, 0f, -0.4f), 0f)
        assertEquals(0f, rim.alpha, TOLERANCE, "depth 0.3 is where the ramp starts")

        val back = modulate(spin, view(0f, 0f, -1f), 0f)
        assertEquals(0f, back.alpha, TOLERANCE, "the back is invisible")
        assertEquals(0.5f / 1.9f, back.radius, TOLERANCE, "the back is a quarter of the front")
        assertEquals(0f, back.lit)
        assertEquals(1f, back.extent)
    }

    @Test
    fun `base spin without the radius ramp leaves radius alone`() {
        val back = modulate(BaseSpin(radiusRamp = false), view(0f, 0f, -1f), 0f)
        assertEquals(1f, back.radius)
        assertEquals(0f, back.alpha, TOLERANCE)
    }

    @Test
    fun `a cell on the ring at 600 ms grows to 1_6 and lights fully`() {
        // At u = 0.6 s the ring is halfway down (at = 0.5); a cell perpendicular to the
        // axis sits at q = 0.5, so the crest is on it.
        val onRing = view(cos(TEN_DEGREES), sin(TEN_DEGREES), 0f)
        assertEquals(0f, dotAxis(onRing), 1e-5f, "the test cell is on the ring")

        val out = modulate(WorkingRing(), onRing, 0.6f)

        assertEquals(1.6f, out.radius, 1e-3f)
        assertEquals(1f, out.lit, 1e-3f)
        assertEquals(1f, out.alpha, 1e-3f)
        assertEquals(1f, out.extent, 1e-3f, "the crest itself is not pulled in")
    }

    @Test
    fun `cells the ring has passed pull in and cells ahead of it are untouched`() {
        val ring = WorkingRing()
        val ahead = alongAxis(-0.8f) // q = 0.9
        val passed = alongAxis(0.4f) // q = 0.3

        val aheadOut = modulate(ring, ahead, 0.6f)
        assertEquals(1f, aheadOut.extent, "nothing ahead of the ring moves")
        assertTrue(aheadOut.radius < 1.05f, "the crest's tail barely reaches it: ${aheadOut.radius}")

        val passedOut = modulate(ring, passed, 0.6f)
        assertTrue(passedOut.extent < 1f, "a passed cell pulls in: ${passedOut.extent}")
        assertTrue(passedOut.extent > 0.92f - TOLERANCE, "never more than 8 %")
        assertTrue(passedOut.radius < 1f + 0.6f * 0.37f, "shrunk under a faint crest")
    }

    @Test
    fun `the ring repeats every 1_7 seconds`() {
        val ring = WorkingRing()
        val cell = alongAxis(0.2f)

        val first = modulate(ring, cell, 0.6f)
        val next = modulate(ring, cell, 0.6f + 1.7f)
        val later = modulate(ring, cell, 0.6f + 17f)

        assertEquals(first.radius, next.radius, 1e-4f)
        assertEquals(first.extent, next.extent, 1e-4f)
        assertEquals(first.radius, later.radius, 1e-3f)
    }

    @Test
    fun `the lens sits on the front of the sphere, 15 to 45 degrees off centre`() {
        val lens = SearchingLens()
        for (hop in 0 until 40) {
            lens.beginFrame(hop * SearchingLens.HOP + 1.5f, still) // past the glide
            val off = acos(lens.lensZ)
            assertTrue(lens.lensZ > 0f, "hop $hop faces the viewer")
            assertTrue(off >= SearchingLens.OFF_MIN - 1e-4f, "hop $hop sits $off rad off, too close")
            assertTrue(off <= SearchingLens.OFF_MAX + 1e-4f, "hop $hop sits $off rad off, too far")
            val length = sqrt(lens.lensX * lens.lensX + lens.lensY * lens.lensY + lens.lensZ * lens.lensZ)
            assertEquals(1f, length, 1e-4f, "unit length")
        }
    }

    @Test
    fun `a cell at the lens centre is fully lit and opaque while one behind the lens is dimmed`() {
        val lens = SearchingLens()
        lens.beginFrame(0.9f, still)
        val centre = view(lens.lensX, lens.lensY, lens.lensZ)

        val inside = CellModulation()
        lens.modulate(0, centre, 0.9f, inside)
        assertEquals(1f, inside.alpha, TOLERANCE)
        assertEquals(1f, inside.lit, TOLERANCE)
        assertEquals(1f + SearchingLens.GROW, inside.radius, TOLERANCE)
        assertEquals(1f + SearchingLens.SWELL, inside.extent, TOLERANCE)
        assertEquals(0f, inside.ox, TOLERANCE, "the centre is not spread")
        assertEquals(0f, inside.oy, TOLERANCE)

        val away = view(-lens.lensX, -lens.lensY, -lens.lensZ)
        val outside = CellModulation()
        lens.modulate(1, away, 0.9f, outside)
        assertEquals(0f, outside.lit)
        assertEquals(1f, outside.radius)
        assertEquals(BaseSpin.rimAlpha(away.depth) * (1f - SearchingLens.DIM), outside.alpha, TOLERANCE)
    }

    @Test
    fun `cells beside the lens centre spread away from it`() {
        val lens = SearchingLens()
        lens.beginFrame(0.9f, still)
        // A direction a tenth of a radian off the lens, within the lens's hard core.
        val beside = tilted(lens.lensX, lens.lensY, lens.lensZ, 0.1f)

        val out = CellModulation()
        lens.modulate(0, beside, 0.9f, out)

        assertEquals(1f, out.lit, 1e-3f)
        val expectedX = (beside.x - lens.lensX) * SearchingLens.SPREAD
        val expectedY = (beside.y - lens.lensY) * SearchingLens.SPREAD
        assertEquals(expectedX, out.ox, 1e-4f)
        assertEquals(expectedY, out.oy, 1e-4f)
    }

    @Test
    fun `the lens glides for the first 40 percent of a hop and then rests`() {
        val lens = SearchingLens()
        lens.beginFrame(1.8f + 0.1f, still)
        val early = Triple(lens.lensX, lens.lensY, lens.lensZ)
        lens.beginFrame(1.8f + 0.8f, still)
        val rested = Triple(lens.lensX, lens.lensY, lens.lensZ)
        lens.beginFrame(1.8f + 1.7f, still)
        val late = Triple(lens.lensX, lens.lensY, lens.lensZ)

        assertNotEquals(early, rested, "the lens is still gliding a tenth of a second in")
        assertEquals(rested, late, "after the glide the lens holds its spot")
    }

    @Test
    fun `nearest table lists each cell's nearest others nearest first with ties to the lower index`() {
        val sample = Forms.Lattice(3).sample(0, LumosLook.BASE, 0f)
        val reach = 24
        val table = NearestTable(sample.positions, reach)

        for (i in listOf(0, 1, sample.count / 2, sample.count - 1)) {
            val origin = sample.positions[i]
            val expected =
                (0 until sample.count)
                    .filter { it != i }
                    .map { j ->
                        val d = sample.positions[j] - origin
                        Pair(d.x * d.x + d.y * d.y + d.z * d.z, j)
                    }.sortedWith(compareBy({ it.first }, { it.second }))
                    .take(reach)
                    .map { it.second }
            val actual = (0 until reach).map { table.neighbour(i, it) }
            assertEquals(expected, actual, "neighbours of $i")
        }
    }

    @Test
    fun `nearest table pads with minus one when there are fewer cells than reach`() {
        val sample = Forms.Lattice(1).sample(0, LumosLook.BASE, 0f) // 19 cells
        val others = sample.count - 1
        assertTrue(others < 24, "the test needs fewer cells than reach, has $others")
        val table = NearestTable(sample.positions, 24)
        val row = (0 until 24).map { table.neighbour(0, it) }
        assertEquals(others, row.count { it >= 0 })
        assertTrue(row.drop(others).all { it == -1 })
    }

    @Test
    fun `the walk only visits neighbours and never the last eight`() {
        val sample = Forms.Lattice(4).sample(0, LumosLook.BASE, 0f)
        val table = NearestTable(sample.positions, ReasoningWalk.REACH)
        val walk = ReasoningWalk(sample, table)
        val spin = ViewRotation().apply { set(0.3f, 1.1f, 0.1f, 0.45f) }

        val visited = ArrayList<Int>()
        visited += walk.head
        for (k in 1..300) {
            val before = walk.head
            walk.beginFrame(k * ReasoningWalk.HOP + 0.01f, spin)
            val after = walk.head
            assertEquals(k, walk.hop)
            assertNotEquals(before, after, "hop $k moved")
            val neighbours = (0 until ReasoningWalk.REACH).map { table.neighbour(before, it) }
            assertTrue(after in neighbours, "hop $k went to $after, not a neighbour of $before")
            val recent = visited.takeLast(ReasoningWalk.AVOID)
            assertTrue(after !in recent, "hop $k returned to $after, one of the last ${ReasoningWalk.AVOID}")
            visited += after
        }
    }

    @Test
    fun `the head is lit and opaque and the tail fades and the rest of the sphere rests at half`() {
        val sample = Forms.Lattice(4).sample(0, LumosLook.BASE, 0f)
        val walk = ReasoningWalk(sample, NearestTable(sample.positions, ReasoningWalk.REACH))
        val heads = ArrayList<Int>()
        for (k in 0..6) {
            walk.beginFrame(k * ReasoningWalk.HOP + 0.01f, still)
            heads += walk.head
        }
        val front = view(0f, 0f, 1f)

        val head = CellModulation()
        walk.modulate(heads.last(), front, 1.4f, head)
        assertEquals(1f, head.lit)
        assertEquals(1f, head.alpha)

        val second = CellModulation()
        walk.modulate(heads[heads.size - 2], front, 1.4f, second)
        assertEquals(1f - 1f / ReasoningWalk.TAIL, second.lit, TOLERANCE)

        val fallenOff = CellModulation()
        walk.modulate(heads.first(), front, 1.4f, fallenOff)
        assertEquals(0f, fallenOff.lit, "the first cell is six hops back, past the tail")
        assertEquals(ReasoningWalk.REST, fallenOff.alpha, TOLERANCE, "a front cell at rest is at half")
    }

    @Test
    fun `a rewind restarts the walk and two walks on one clock agree`() {
        val sample = Forms.Lattice(4).sample(0, LumosLook.BASE, 0f)
        val table = NearestTable(sample.positions, ReasoningWalk.REACH)
        val spin = ViewRotation().apply { set(0.2f, 0.7f, 0.1f, 0.2f) }

        val walked = ReasoningWalk(sample, table)
        for (k in 1..40) walked.beginFrame(k * ReasoningWalk.HOP + 0.01f, spin)
        val start = walked.head.also { walked.beginFrame(0f, spin) }
        assertNotEquals(start, walked.head, "rewinding to zero puts the walk back at its first cell")
        assertEquals(0, walked.hop)

        val fresh = ReasoningWalk(sample, table)
        fresh.beginFrame(40 * ReasoningWalk.HOP + 0.01f, spin)
        walked.beginFrame(40 * ReasoningWalk.HOP + 0.01f, spin)
        assertEquals(fresh.head, walked.head, "a late joiner and a restarted walk land on the same cell")
        assertEquals(start, fresh.head, "and it is where the live walk was")
    }

    @Test
    fun `the catalogue builds a modulator for every look`() {
        val sample = Forms.Lattice(2).sample(0, LumosLook.BASE, 0f)
        for (look in LumosLook.entries) {
            val modulator = Looks.forLook(Look(look), sample)
            val out = modulate(modulator, view(0f, 0f, 1f), 0.3f)
            assertTrue(out.alpha in 0f..1f, "$look alpha ${out.alpha}")
        }
    }

    private fun modulate(
        modulator: LookModulator,
        view: ViewPoint,
        t: Float,
    ): CellModulation {
        val out = CellModulation()
        modulator.beginFrame(t, still)
        modulator.modulate(0, view, t, out)
        return out
    }

    /** A view point whose direction is its position normalised. */
    private fun view(
        x: Float,
        y: Float,
        z: Float,
    ): ViewPoint =
        ViewPoint().apply {
            this.x = x
            this.y = y
            this.z = z
            val length = sqrt(x * x + y * y + z * z)
            if (length > 0f) {
                ux = x / length
                uy = y / length
                uz = z / length
            }
        }

    private fun alongAxis(dot: Float): ViewPoint =
        view(WorkingRing.AXIS_X * dot, WorkingRing.AXIS_Y * dot, WorkingRing.AXIS_Z * dot)

    private fun dotAxis(view: ViewPoint): Float =
        view.x * WorkingRing.AXIS_X + view.y * WorkingRing.AXIS_Y + view.z * WorkingRing.AXIS_Z

    /** A unit direction [radians] away from `(x, y, z)`, rotated about the screen's Y axis. */
    private fun tilted(
        x: Float,
        y: Float,
        z: Float,
        radians: Float,
    ): ViewPoint {
        val c = cos(radians)
        val s = sin(radians)
        return view(x * c + z * s, y, -x * s + z * c)
    }

    private companion object {
        const val TOLERANCE: Float = 1e-5f
        val TEN_DEGREES: Float = (10.0 * PI / 180.0).toFloat()
    }
}
