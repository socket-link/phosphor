package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.field.Choreography
import link.socket.phosphor.field.FormMatcher
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase

/**
 * PHO-57 Task C: `morphTo` keeps every cell's identity, springs it to the new
 * form, and once settled renders the new form byte for byte as a cut would
 * have rendered it in the matched order.
 */
class VoxelFrameBuilderMorphTest {
    private val resolution = 4

    // Everything that moves the skin is off, so a cell's position is its rest position.
    private val still =
        AtmospherePresets.IDLE.copy(
            resolution = resolution,
            noise = 0f,
            surfaceBump = 0f,
            pulseAmplitude = 0f,
            ySquash = 1f,
            rotationX = 0f,
            rotationY = 0f,
        )

    @Test
    fun `before any morph the builder renders as it did`() {
        val plain = VoxelFrameBuilder(initialResolution = resolution)
        val morphing = VoxelFrameBuilder(initialResolution = resolution)
        val a = plain.build(snapshot(still, 0f), dt = 0.05f)
        val b = morphing.build(snapshot(still, 0f), dt = 0.05f)
        assertEquals(a, b)
        assertFalse(morphing.isMorphing)
        assertEquals(plain.voxelSphere.count, morphing.currentCount)
    }

    @Test
    fun `morphTo keeps cell count and identity and springs each cell to its matched position`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        val before = builder.build(snapshot(still, 0f), dt = 0f)
        val origins = before.cells.map { Vector3(it.x, it.y, it.z) }
        val target = Forms.Fibonacci.sample(before.cells.size, LumosLook.BASE, 0f)
        val expected = FormMatcher().match(origins, target.positions)

        builder.morphTo(Forms.Fibonacci, choreography = Choreography.DIRECT, durationSeconds = 1f)
        assertTrue(builder.isMorphing)
        assertSame(Forms.Fibonacci, builder.form)

        val first = builder.build(snapshot(still, 1f / 60f), dt = 1f / 60f)
        assertEquals(before.cells.size, first.cells.size)
        // One step in, every cell has left where it was and is on the line to its own target.
        for (i in first.cells.indices) {
            val from = origins[i]
            val to = target.positions[expected[i]]
            val now = Vector3(first.cells[i].x, first.cells[i].y, first.cells[i].z)
            val total = (to - from).length()
            val travelled = (now - from).length()
            val remaining = (to - now).length()
            assertTrue(abs(travelled + remaining - total) < 1e-4f, "cell $i left the straight line")
            if (total > 1e-2f) assertTrue(travelled > 0f && travelled < total, "cell $i should be in transit")
        }

        var t = 1f / 60f
        var frames = 1
        while (builder.isMorphing) {
            t += 1f / 60f
            builder.build(snapshot(still, t), dt = 1f / 60f)
            frames++
        }
        assertTrue(frames in 60..61, "a 1 s morph at 60 fps settles in 60 frames, took $frames")
    }

    @Test
    fun `once settled the frame is byte-identical to a cut to the matched order`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        val lively = AtmospherePresets.THINKING.copy(resolution = resolution)
        val before = builder.build(snapshot(lively, 0f), dt = 0f)
        val target = Forms.Cube.sample(before.cells.size, LumosLook.BASE, 0f)
        // The match runs on rest positions, not on the rendered cells THINKING's noise and bump have moved.
        val assignment =
            FormMatcher().match(
                Forms.Lattice(resolution).sample(0, LumosLook.BASE, 0f).positions,
                target.positions,
            )

        builder.morphTo(Forms.Cube, durationSeconds = 0.5f)
        var t = 0f
        while (builder.isMorphing) {
            t += 1f / 30f
            builder.build(snapshot(lively, t), dt = 1f / 30f)
        }
        t += 1f / 30f
        val settled = builder.build(snapshot(lively, t), dt = 1f / 30f)

        // A second builder cut straight to the cube in the matched order, at the same phases.
        val cut = VoxelFrameBuilder(initialResolution = resolution)
        cut.form =
            object : FramedForm {
                override val scale: Float get() = Forms.Cube.scale

                override fun cellRadius(count: Int): Float = Forms.Cube.cellRadius(count)

                override fun sample(
                    count: Int,
                    look: LumosLook,
                    time: Float,
                ): FormSample = reordered(target, assignment)
            }
        cut.phases = builder.phases
        // Advance by zero so the cut builder's accumulators stay at the morphing builder's.
        val reference = cut.build(snapshot(lively, t), dt = 0f)

        assertEquals(reference.cells, settled.cells)
        assertEquals(reference.ambient, settled.ambient)
    }

    @Test
    fun `a settled morph costs no spring step and the frame stays identical frame to frame`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        builder.build(snapshot(still, 0f), dt = 0f)
        builder.morphTo(Forms.Fibonacci, durationSeconds = 0.2f)
        var t = 0f
        while (builder.isMorphing) {
            t += 0.05f
            builder.build(snapshot(still, t), dt = 0.05f)
        }
        val a = builder.build(snapshot(still, t + 0.05f), dt = 0.05f)
        val b = builder.build(snapshot(still, t + 0.1f), dt = 0.05f)
        // The pattern phase still drifts the colours; the positions are the form's and do not move.
        assertEquals(a.cells.map { Vector3(it.x, it.y, it.z) }, b.cells.map { Vector3(it.x, it.y, it.z) })
    }

    @Test
    fun `a zero duration snaps to the matched order`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        val before = builder.build(snapshot(still, 0f), dt = 0f)
        val target = Forms.Fibonacci.sample(before.cells.size, LumosLook.BASE, 0f)
        val assignment = FormMatcher().match(before.cells.map { Vector3(it.x, it.y, it.z) }, target.positions)

        builder.morphTo(Forms.Fibonacci, durationSeconds = 0f)

        assertFalse(builder.isMorphing)
        val after = builder.build(snapshot(still, 0f), dt = 0f)
        assertEquals(assignment.map { target.positions[it] }, after.cells.map { Vector3(it.x, it.y, it.z) })
    }

    @Test
    fun `buildStill snaps a morph in flight`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        builder.build(snapshot(still, 0f), dt = 0f)
        builder.morphTo(Forms.Fibonacci, durationSeconds = 5f)
        assertTrue(builder.isMorphing)

        builder.buildStill(snapshot(still, 0f), dt = 0f)

        assertFalse(builder.isMorphing)
        val held = builder.buildStill(snapshot(still, 0f), dt = 0f)
        val target = Forms.Fibonacci.sample(held.cells.size, LumosLook.BASE, 0f)
        assertEquals(target.positions.toSet(), held.cells.map { Vector3(it.x, it.y, it.z) }.toSet())
    }

    @Test
    fun `a second morphTo mid-flight starts from where the cells are`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        builder.build(snapshot(still, 0f), dt = 0f)
        builder.morphTo(Forms.Fibonacci, choreography = Choreography.DIRECT, durationSeconds = 2f)
        val midway = builder.build(snapshot(still, 0.5f), dt = 0.5f)

        builder.morphTo(Forms.Cube, choreography = Choreography.DIRECT, durationSeconds = 2f)
        val next = builder.build(snapshot(still, 0.5f + 1f / 60f), dt = 1f / 60f)

        assertTrue(builder.isMorphing)
        assertSame(Forms.Cube, builder.form)
        for (i in next.cells.indices) {
            val a = midway.cells[i]
            val b = next.cells[i]
            // One frame on, each cell is near where it was: no cut.
            assertTrue(abs(a.x - b.x) + abs(a.y - b.y) + abs(a.z - b.z) < 0.15f, "cell $i jumped")
        }
    }

    @Test
    fun `a smaller count drops the highest indices and a larger one spawns new cells from the body`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        val before = builder.build(snapshot(still, 0f), dt = 0f)
        val count = before.cells.size

        builder.morphTo(Forms.Fibonacci, count = count - 10, choreography = Choreography.DIRECT, durationSeconds = 1f)
        val fewer = builder.build(snapshot(still, 1f / 60f), dt = 1f / 60f)
        assertEquals(count - 10, fewer.cells.size)
        assertEquals(count - 10, builder.currentCount)

        builder.morphTo(Forms.Cube, count = count + 10, choreography = Choreography.DIRECT, durationSeconds = 1f)
        val more = builder.build(snapshot(still, 2f / 60f), dt = 1f / 60f)
        assertEquals(count + 10, more.cells.size)
        // The surviving cells are still near where they were — they carry the velocity of a spring one
        // frame into a 1 s trip, so they move, but nothing cuts; the spawned ones sit inside the body.
        for (i in fewer.cells.indices) {
            val a = fewer.cells[i]
            val b = more.cells[i]
            val step = Vector3(b.x - a.x, b.y - a.y, b.z - a.z).length()
            assertTrue(step < 0.3f, "cell $i jumped $step")
        }
        for (i in fewer.cells.size until more.cells.size) {
            val c = more.cells[i]
            assertTrue(Vector3(c.x, c.y, c.z).length() <= 1.3f, "spawned cell $i is outside the body")
        }
    }

    @Test
    fun `a cell keeps its colour on the first frame of a morph rather than taking the new form's`() {
        val pattern = AtmospherePresets.THINKING.copy(resolution = resolution, noise = 0f, surfaceBump = 0f)
        val cut = VoxelFrameBuilder(initialResolution = resolution)
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        val before = builder.build(snapshot(pattern, 0f), dt = 0f)
        cut.build(snapshot(pattern, 0f), dt = 0f)

        builder.morphTo(Forms.Cube, choreography = Choreography.DIRECT, durationSeconds = 1f)
        val first = builder.build(snapshot(pattern, 0f), dt = 0f)
        cut.form = Forms.Cube
        val recoloured = cut.build(snapshot(pattern, 0f), dt = 0f)

        fun shift(
            a: VoxelCell,
            b: VoxelCell,
        ) = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
        // The premise: the cube's pattern colours the orb differently from the lattice's.
        val cutShift = before.cells.indices.sumOf { shift(before.cells[it], recoloured.cells[it]).toDouble() }
        assertTrue(cutShift > 0.5, "the cube recolours too little to test against: $cutShift")
        for (i in before.cells.indices) {
            assertTrue(shift(before.cells[i], first.cells[i]) < 1e-3f, "cell $i changed colour on the first frame")
        }
    }

    @Test
    fun `survivors name the cells a smaller count keeps — survivor i becomes cell i`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        val before = builder.build(snapshot(still, 0f), dt = 0f)
        val count = before.cells.size
        val survivors = IntArray(count / 3) { it * 3 }

        builder.morphTo(Forms.Fibonacci, count = survivors.size, durationSeconds = 1f, survivors = survivors)
        val after = builder.build(snapshot(still, 0f), dt = 0f)

        assertEquals(survivors.size, after.cells.size)
        for (k in survivors.indices) {
            val was = before.cells[survivors[k]]
            val now = after.cells[k]
            val moved = abs(was.x - now.x) + abs(was.y - now.y) + abs(was.z - now.z)
            assertTrue(moved < 1e-3f, "survivor $k is not where cell ${survivors[k]} was: moved $moved")
        }
    }

    @Test
    fun `survivors must be as many ascending indices as the new form has`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        val count = builder.build(snapshot(still, 0f), dt = 0f).cells.size
        assertFailsWith<IllegalArgumentException> {
            builder.morphTo(Forms.Fibonacci, count = 3, survivors = intArrayOf(0, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            builder.morphTo(Forms.Fibonacci, count = 3, survivors = intArrayOf(0, 2, 1))
        }
        assertFailsWith<IllegalArgumentException> {
            builder.morphTo(Forms.Fibonacci, count = 3, survivors = intArrayOf(0, 1, count))
        }
    }

    @Test
    fun `morphing to the current form at the current count is a no-op`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        builder.build(snapshot(still, 0f), dt = 0f)
        builder.morphTo(builder.form)
        assertFalse(builder.isMorphing)
    }

    @Test
    fun `a resolution change mid-morph is a cut`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        builder.build(snapshot(still, 0f), dt = 0f)
        builder.morphTo(Forms.Fibonacci, durationSeconds = 2f)
        builder.build(snapshot(still, 0.1f), dt = 0.1f)

        val frame = builder.build(snapshot(still.copy(resolution = 3), 0.2f), dt = 0.1f)

        assertFalse(builder.isMorphing)
        assertEquals(Forms.Lattice(3).count, frame.cells.size)
        assertEquals(
            Forms.Fibonacci.sample(frame.cells.size, LumosLook.BASE, 0f).positions.toSet(),
            frame.cells.map {
                Vector3(it.x, it.y, it.z)
            }.toSet(),
        )
    }

    @Test
    fun `setting form directly is a cut that drops the morph`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        builder.build(snapshot(still, 0f), dt = 0f)
        builder.morphTo(Forms.Fibonacci, durationSeconds = 2f)
        builder.form = Forms.Cube
        assertFalse(builder.isMorphing)
    }

    @Test
    fun `buildSteadyState refuses mid-morph and renders the settled form in matched order`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        builder.build(snapshot(still, 0f), dt = 0f)
        builder.morphTo(Forms.Fibonacci, durationSeconds = 1f)
        assertFailsWith<IllegalStateException> {
            builder.buildSteadyState(still, builder.phases, tick = 0L, timestampEpochMillis = 0L)
        }
        var t = 0f
        while (builder.isMorphing) {
            t += 0.1f
            builder.build(snapshot(still, t), dt = 0.1f)
        }
        val live = builder.build(snapshot(still, t + 0.1f), dt = 0.1f)
        val steady = builder.buildSteadyState(still, builder.phases, tick = 0L, timestampEpochMillis = 0L)
        assertEquals(live.cells, steady.cells)
    }

    @Test
    fun `a framed form's scale blends in with the spring rather than cutting`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        val before = builder.build(snapshot(still, 0f), dt = 0f)
        builder.morphTo(Forms.Cube, choreography = Choreography.DIRECT, durationSeconds = 2f)
        val early = builder.build(snapshot(still, 1f / 60f), dt = 1f / 60f)
        // The cube's cells are drawn larger than the lattice's; one frame in they have barely grown.
        val beforeScale = before.cells.first().scale
        val earlyScale = early.cells.first().scale
        assertNotEquals(beforeScale, earlyScale)
        assertTrue(abs(earlyScale - beforeScale) < 0.05f * beforeScale, "scale jumped from $beforeScale to $earlyScale")
    }

    @Test
    fun `rejects a non-positive count and a negative duration`() {
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        assertFailsWith<IllegalArgumentException> { builder.morphTo(Forms.Fibonacci, count = 0) }
        assertFailsWith<IllegalArgumentException> { builder.morphTo(Forms.Fibonacci, durationSeconds = -1f) }
    }

    private fun reordered(
        sample: FormSample,
        order: IntArray,
    ): FormSample =
        FormSample(
            positions = order.map { sample.positions[it] },
            unitDirections = order.map { sample.unitDirections[it] },
            theta = FloatArray(order.size) { sample.theta[order[it]] },
            phi = FloatArray(order.size) { sample.phi[order[it]] },
            distance = FloatArray(order.size) { sample.distance[order[it]] },
            jitter = order.map { sample.jitter[it] },
        )

    private fun snapshot(
        atmosphere: AtmosphereState,
        elapsedSeconds: Float,
    ): SceneSnapshot =
        SceneSnapshot(
            frameIndex = (elapsedSeconds * 60f).toLong(),
            elapsedTimeSeconds = elapsedSeconds,
            coordinateSpace = CoordinateSpace.WORLD_CENTERED,
            agentStates = emptyList(),
            substrateState = SubstrateState.create(2, 2),
            particleStates = emptyList(),
            flowConnections = emptyList(),
            flowField = null,
            waveformHeightField = null,
            waveformGridWidth = null,
            waveformGridDepth = null,
            cameraTransform = null,
            emitterStates = emptyList(),
            choreographyPhase = CognitivePhase.NONE,
            atmosphere = atmosphere,
            atmosphereTransition = null,
        )
}
