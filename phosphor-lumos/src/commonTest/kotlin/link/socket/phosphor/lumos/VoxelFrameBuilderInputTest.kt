package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.field.Force
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase
import link.socket.phosphor.signal.LumosInput

/**
 * PHO-82: with [LumosRenderConfig.interactive], a live orb's cells part around
 * the pointer and spring back when it leaves, on a body the builder keeps only
 * while it has something to answer. The frame carries the input for the light,
 * a gazing form is handed the pointer, and an orb nobody points at renders
 * exactly as it always has.
 */
class VoxelFrameBuilderInputTest {
    @Test
    fun `with no input an interactive orb is frame for frame the orb it was — across a morph`() {
        val on = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val off = VoxelFrameBuilder(initialResolution = RESOLUTION)
        var frame = 0

        fun step() {
            frame++
            assertEquals(off.build(snapshot(LIVELY, frame), DT), on.build(snapshot(LIVELY, frame), DT), "frame $frame")
        }

        repeat(10) { step() }
        on.morphTo(Forms.Fibonacci)
        off.morphTo(Forms.Fibonacci)
        while (on.isMorphing || off.isMorphing) {
            step()
            assertTrue(frame < 400, "the morph should settle")
        }
        repeat(10) { step() }
        assertNull(on.currentBody, "nothing pointed, so no body was kept")
    }

    @Test
    fun `without interactive the pointer parts nothing — the frame only carries it`() {
        val pointed = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val plain = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val pointing = LumosInput(Vector2(0.1f, 0.1f))

        for (frame in 1..30) {
            val a = pointed.build(snapshot(LIVELY, frame, pointing), DT)
            val b = plain.build(snapshot(LIVELY, frame), DT)
            assertEquals(b.copy(input = pointing), a, "frame $frame")
        }
        assertNull(pointed.currentBody)
    }

    @Test
    fun `cells drawn inside the radius move away from the pointer on screen and the rest stay put`() {
        val pushed = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val untouched = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        pushed.phases = TURNED
        untouched.phases = TURNED
        val pointer = Vector2(0.23f, -0.17f)

        var a = pushed.build(snapshot(STILL, 0, LumosInput(pointer)), DT)
        var b = untouched.build(snapshot(STILL, 0), DT)
        for (frame in 1..SETTLE_FRAMES) {
            a = pushed.build(snapshot(STILL, frame, LumosInput(pointer)), DT)
            b = untouched.build(snapshot(STILL, frame), DT)
        }

        assertEquals(TURNED.orbRotationY, a.ambient.orbRotationY, "the orb is held at a turned pose")
        assertFalse(pushed.isMorphing, "a body at rest under the pointer is not a morph")
        assertPartedAround(pointer, pushed = a, rest = b)
    }

    @Test
    fun `the aim divides the pointer by the form's scale — so a framed form parts where it is drawn`() {
        val pushed = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val untouched = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        pushed.form = Forms.Cube
        untouched.form = Forms.Cube
        pushed.phases = TURNED
        untouched.phases = TURNED
        val pointer = Vector2(0.3f, 0.2f)

        lateinit var a: VoxelFrame
        lateinit var b: VoxelFrame
        for (frame in 1..SETTLE_FRAMES) {
            a = pushed.build(snapshot(STILL, frame, LumosInput(pointer)), DT)
            b = untouched.build(snapshot(STILL, frame), DT)
        }

        // The cube is drawn 1.2 times its rest positions, so the radius on screen is 1.2 times the repel's.
        assertPartedAround(pointer, pushed = a, rest = b, radius = RADIUS * Forms.Cube.scale)
    }

    @Test
    fun `the aim follows the yaw of a look that owns it — not the atmosphere's`() {
        val pushed = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val untouched = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        pushed.look = Look(LumosLook.RETRYING)
        untouched.look = Look(LumosLook.RETRYING)
        val pointer = Vector2(-0.2f, 0.25f)

        lateinit var a: VoxelFrame
        lateinit var b: VoxelFrame
        for (frame in 1..SETTLE_FRAMES) {
            // Hold the look's clock, and so the yaw it owns, away from the atmosphere's yaw.
            pushed.phases = TURNED.copy(lookSeconds = HELD_LOOK_SECONDS)
            untouched.phases = TURNED.copy(lookSeconds = HELD_LOOK_SECONDS)
            a = pushed.build(snapshot(STILL, frame, LumosInput(pointer)), DT)
            b = untouched.build(snapshot(STILL, frame), DT)
        }

        assertTrue(
            abs(a.ambient.orbRotationY - TURNED.orbRotationY) > 0.3f,
            "the look turns the orb by its own yaw: ${a.ambient.orbRotationY}",
        )
        assertPartedAround(pointer, pushed = a, rest = b)
    }

    @Test
    fun `when the pointer leaves the cells go home — and the frame the body drops on is the untouched one`() {
        val touched = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val untouched = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val pointing = LumosInput(Vector2(0.1f, 0.2f))
        var frame = 0
        var a: VoxelFrame
        var b: VoxelFrame
        do {
            frame++
            a = touched.build(snapshot(LIVELY, frame, pointing), DT)
            b = untouched.build(snapshot(LIVELY, frame), DT)
        } while (frame < 30)
        assertNotEquals(b.cells, a.cells, "the pointer parts the cells")

        frame++
        a = touched.build(snapshot(LIVELY, frame), DT)
        b = untouched.build(snapshot(LIVELY, frame), DT)
        assertNotNull(touched.currentBody, "the body is kept while the cells go home")
        assertNotEquals(b.cells, a.cells, "the cells spring home rather than snap")

        var gap: Float
        do {
            gap = a.cells.indices.maxOf { distance(a.cells[it], b.cells[it]) }
            frame++
            a = touched.build(snapshot(LIVELY, frame), DT)
            b = untouched.build(snapshot(LIVELY, frame), DT)
            assertTrue(frame < 30 + LET_GO_FRAMES, "the cells should be home within ten seconds")
        } while (touched.currentBody != null)

        assertTrue(gap < 1e-3f, "the frame before the drop is home to within a hair, was $gap away")
        assertEquals(b, a, "the frame the body drops on is read straight from the form")
        repeat(10) {
            frame++
            assertEquals(untouched.build(snapshot(LIVELY, frame), DT), touched.build(snapshot(LIVELY, frame), DT))
        }
    }

    @Test
    fun `a morph under the pointer settles — and isMorphing goes false while the body stays`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val pointing = LumosInput(Vector2(0.3f, 0f))
        var frame = 0
        var last = builder.build(snapshot(STILL, frame, pointing), DT)
        repeat(30) { last = builder.build(snapshot(STILL, ++frame, pointing), DT) }
        val resting = assertNotNull(builder.currentBody)
        assertFalse(builder.isMorphing)

        builder.morphTo(Forms.Fibonacci, durationSeconds = 1f)
        assertTrue(builder.isMorphing)
        assertNotSame(resting, builder.currentBody, "the morph is a body of its own")
        val first = builder.build(snapshot(STILL, ++frame, pointing), DT)
        for (i in first.cells.indices) {
            val step = distance(last.cells[i], first.cells[i])
            // It starts where the pointer pushed the cells: one frame on, nothing has cut.
            assertTrue(step < 0.15f, "cell $i jumped $step")
        }

        while (builder.isMorphing) {
            builder.build(snapshot(STILL, ++frame, pointing), DT)
            assertTrue(frame < 30 + 120, "the morph should settle")
        }
        assertSame(Forms.Fibonacci, builder.form)
        assertNotNull(builder.currentBody, "the pointer is still there, so the body stays")
        // A body at rest is no morph, so there is a steady state to render.
        builder.buildSteadyState(STILL, builder.phases, tick = 0L, timestampEpochMillis = 0L)

        while (builder.currentBody != null) {
            builder.build(snapshot(STILL, ++frame), DT)
            assertTrue(frame < 30 + 120 + LET_GO_FRAMES, "once the pointer leaves the cells should come home")
        }
    }

    @Test
    fun `setting form and a resolution change drop the body at once`() {
        val touched = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val untouched = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val pointing = LumosInput(Vector2(0.1f, -0.1f))
        var frame = 0

        fun point(frames: Int) {
            repeat(frames) {
                frame++
                touched.build(snapshot(LIVELY, frame, pointing), DT)
                untouched.build(snapshot(LIVELY, frame), DT)
            }
        }

        point(20)
        assertNotNull(touched.currentBody)
        touched.form = Forms.Cube
        untouched.form = Forms.Cube
        assertNull(touched.currentBody, "setting form is a cut")
        frame++
        assertEquals(untouched.build(snapshot(LIVELY, frame), DT), touched.build(snapshot(LIVELY, frame), DT))

        point(20)
        assertNotNull(touched.currentBody)
        val finer = LIVELY.copy(resolution = RESOLUTION + 1)
        frame++
        assertEquals(
            untouched.build(snapshot(finer, frame), DT),
            touched.build(snapshot(finer, frame), DT),
            "a resolution change is a cut",
        )
        assertNull(touched.currentBody)
    }

    @Test
    fun `a pointer on the frame of a resolution change stands a body at the new count`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val pointing = LumosInput(Vector2(0.1f, 0.1f))
        repeat(5) { builder.build(snapshot(STILL, it, pointing), DT) }
        val coarse = assertNotNull(builder.currentBody)

        val frame = builder.build(snapshot(STILL.copy(resolution = RESOLUTION + 1), 5, pointing), DT)

        val fine = assertNotNull(builder.currentBody)
        assertNotSame(coarse, fine)
        assertEquals(Forms.Lattice(RESOLUTION + 1).count, fine.count)
        assertEquals(fine.count, frame.cells.size)
    }

    @Test
    fun `the body stood for the pointer is reused while the count holds`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val pointing = LumosInput(Vector2(0.1f, 0.1f))
        var frame = 0
        builder.build(snapshot(STILL, frame, pointing), DT)
        val first = assertNotNull(builder.currentBody)
        while (builder.currentBody != null) {
            builder.build(snapshot(STILL, ++frame), DT)
            assertTrue(frame < LET_GO_FRAMES, "the cells should come home")
        }

        builder.build(snapshot(STILL, ++frame, pointing), DT)

        assertSame(first, builder.currentBody, "a pointer that comes back stands the same body")
    }

    @Test
    fun `a burst under the pointer acts on the body standing there`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val pointing = LumosInput(Vector2(0.2f, 0f))
        var frame = 0
        repeat(30) { builder.build(snapshot(STILL, ++frame, pointing), DT) }
        val resting = assertNotNull(builder.currentBody)

        builder.burst(6f)

        assertSame(resting, builder.currentBody, "the burst starts its trip from where the pointer pushed the cells")
        assertTrue(builder.isMorphing)
        while (builder.isMorphing) {
            builder.build(snapshot(STILL, ++frame, pointing), DT)
            assertTrue(frame < 30 + 120, "the burst's trip should settle")
        }
        assertSame(resting, builder.currentBody, "with the pointer still there the body stays")
    }

    @Test
    fun `buildStill ignores the pointer — it drops the body and holds its instance while the pointer moves`() {
        val touched = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val untouched = VoxelFrameBuilder(initialResolution = RESOLUTION, config = INTERACTIVE)
        val pointing = LumosInput(Vector2(0.2f, 0.2f))
        for (frame in 1..10) {
            touched.build(snapshot(LIVELY, frame, pointing), DT)
            untouched.build(snapshot(LIVELY, frame), DT)
        }
        assertNotNull(touched.currentBody)

        val held = touched.buildStill(snapshot(LIVELY, 11, pointing), DT)

        assertNull(touched.currentBody, "reduced motion drops the body, as it drops a morph")
        assertNull(held.input, "a still carries no input")
        assertEquals(untouched.buildStill(snapshot(LIVELY, 11), DT), held, "a still parts nothing")
        assertSame(held, touched.buildStill(snapshot(LIVELY, 12, LumosInput(Vector2(-0.4f, 0.1f))), DT))
        assertSame(held, touched.buildStill(snapshot(LIVELY, 13), DT))
        assertNull(touched.currentBody)
    }

    @Test
    fun `a gazing form sees the pointer from build and through counted — and none from a still or steady state`() {
        val gazer = Gazer()
        // Not interactive: the gaze follows any pointer, as Dots' does.
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.form = gazer
        val pointer = Vector2(0.4f, -0.3f)

        builder.build(snapshot(STILL, 1, LumosInput(pointer)), DT)
        assertEquals(pointer, gazer.lastGaze)
        gazer.lastGaze = null
        assertEquals(Forms.Lattice(RESOLUTION).count, builder.currentCount)
        assertEquals(pointer, gazer.lastGaze, "currentCount samples with the last frame's gaze")

        builder.buildStill(snapshot(STILL, 2, LumosInput(pointer)), DT)
        assertNull(gazer.lastGaze, "a still does not gaze")
        gazer.lastGaze = pointer
        assertEquals(Forms.Lattice(RESOLUTION).count, builder.currentCount)
        assertNull(gazer.lastGaze, "after a still the last frame's gaze is none")

        gazer.lastGaze = pointer
        builder.buildSteadyState(STILL, builder.phases, tick = 0L, timestampEpochMillis = 0L)
        assertNull(gazer.lastGaze, "a steady state does not gaze")

        val counted = gazer.counted(COUNT)
        assertTrue(counted is GazingForm, "counted a gazing form still gazes")
        assertFalse(Forms.Fibonacci.counted(COUNT) is GazingForm, "and a form that does not gaze is not made to")
        builder.form = counted
        val moved = Vector2(-0.1f, 0.6f)
        builder.build(snapshot(STILL, 3, LumosInput(moved)), DT)
        assertEquals(moved, gazer.lastGaze)
        assertEquals(COUNT, gazer.lastCount, "counted hands on the count it pins")

        val target = Gazer()
        builder.morphTo(target)
        assertEquals(moved, target.lastGaze, "morphTo samples its target with the last frame's gaze")
    }

    @Test
    fun `frame input is set by build alone — and only when it is not NONE`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val pointing = LumosInput(Vector2(0.2f, 0.4f))
        val pressed = LumosInput(pressed = true)

        assertNull(builder.build(snapshot(LIVELY, 1), DT).input)
        assertEquals(pointing, builder.build(snapshot(LIVELY, 2, pointing), DT).input, "the light needs no interactive")
        assertEquals(pressed, builder.build(snapshot(LIVELY, 3, pressed), DT).input)
        assertNull(builder.build(snapshot(LIVELY, 4, LumosInput(pointer = null, pressed = false)), DT).input)
        assertNull(builder.buildStill(snapshot(LIVELY, 5, pointing), DT).input)
        assertNull(builder.buildSteadyState(LIVELY, builder.phases, tick = 6L, timestampEpochMillis = 0L).input)
    }

    /**
     * Every cell [rest] draws within [radius] of [pointer] on screen is drawn
     * further from it in [pushed], and every cell further out is drawn where it
     * rests: each frame seen as the surfaces see it, through its own rotation.
     */
    private fun assertPartedAround(
        pointer: Vector2,
        pushed: VoxelFrame,
        rest: VoxelFrame,
        radius: Float = RADIUS,
    ) {
        assertEquals(rest.cells.size, pushed.cells.size)
        var inside = 0
        for (i in rest.cells.indices) {
            val from = onScreen(rest.cells[i], rest.ambient)
            val to = onScreen(pushed.cells[i], pushed.ambient)
            val before = (from - pointer).length()
            val after = (to - pointer).length()
            if (before < radius - MARGIN && before > MARGIN) {
                inside++
                assertTrue(after > before, "cell $i, $before from the pointer, should give way, is $after")
            } else if (before > radius + MARGIN) {
                assertTrue(distance(rest.cells[i], pushed.cells[i]) < 1e-5f, "cell $i is outside the radius and moved")
            }
        }
        assertTrue(inside > 0, "the pointer should be over the orb")
    }

    // Where a surface draws a cell before its tilt: rotated about X and then Y by the frame's own
    // angles, as PixelLattice and CliLattice rotate it, and seen along z.
    private fun onScreen(
        cell: VoxelCell,
        ambient: VoxelAmbient,
    ): Vector2 {
        val cosX = cos(ambient.orbRotationX.toDouble()).toFloat()
        val sinX = sin(ambient.orbRotationX.toDouble()).toFloat()
        val cosY = cos(ambient.orbRotationY.toDouble()).toFloat()
        val sinY = sin(ambient.orbRotationY.toDouble()).toFloat()
        val y1 = cell.y * cosX - cell.z * sinX
        val z1 = cell.y * sinX + cell.z * cosX
        return Vector2(cell.x * cosY + z1 * sinY, y1)
    }

    private fun distance(
        a: VoxelCell,
        b: VoxelCell,
    ): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun snapshot(
        atmosphere: AtmosphereState,
        frame: Int,
        input: LumosInput = LumosInput.NONE,
    ): SceneSnapshot =
        SceneSnapshot(
            frameIndex = frame.toLong(),
            elapsedTimeSeconds = frame * DT,
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
            input = input,
        )

    /** A form that remembers what it was last asked for, and is the Fibonacci sphere. */
    private class Gazer : GazingForm {
        var lastGaze: Vector2? = null
        var lastCount: Int = 0

        override fun sample(
            count: Int,
            look: LumosLook,
            time: Float,
            gaze: Vector2?,
        ): FormSample {
            lastGaze = gaze
            lastCount = count
            return Forms.Fibonacci.sample(count, look, time)
        }
    }

    private companion object {
        const val RESOLUTION = 4
        const val DT = 1f / 60f
        const val COUNT = 40

        // Long enough for the spring to carry every pushed cell all but home to its parted target.
        const val SETTLE_FRAMES = 120

        // Ten seconds: far longer than the spring needs to bring every cell home.
        const val LET_GO_FRAMES = 600

        // A look clock where the retrying look's own yaw is a quarter radian, far from TURNED's.
        const val HELD_LOOK_SECONDS = 0.5

        const val RADIUS = Force.Repel.DEFAULT_RADIUS
        const val MARGIN = 1e-3f

        val INTERACTIVE = LumosRenderConfig(interactive = true)

        val LIVELY: AtmosphereState = AtmospherePresets.THINKING.copy(resolution = RESOLUTION)

        // Everything that moves the skin is off, so a cell is drawn at its rest position, and the
        // orb holds whatever pose its phases are given.
        val STILL: AtmosphereState =
            AtmospherePresets.IDLE.copy(
                resolution = RESOLUTION,
                noise = 0f,
                surfaceBump = 0f,
                pulseAmplitude = 0f,
                ySquash = 1f,
                rotationX = 0f,
                rotationY = 0f,
            )

        // A pose that is not the identity about either axis.
        val TURNED = FramePhases(pulsePhase = 0f, patternPhase = 0f, orbRotationX = 0.35f, orbRotationY = 1.1f)
    }
}
