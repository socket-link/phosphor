package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase

/**
 * PHO-77: [VoxelFrameBuilder.lookSwitch] reads the scene's signal and
 * [VoxelFrameBuilder.lookTo] blends between looks over
 * [VoxelFrameBuilder.LOOK_BLEND_SECONDS].
 */
class VoxelFrameBuilderSwitchTest {
    @Test
    fun `with a null switch an agent in EXECUTE changes nothing`() {
        val reference = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val watched = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val quiet = snapshot(THINKING)
        val busy = snapshot(THINKING, agents = listOf(agent(CognitivePhase.EXECUTE, AgentActivityState.PROCESSING)))

        val expected = (1..5).map { reference.build(quiet, dt = DT) }
        val actual = (1..5).map { watched.build(busy, dt = DT) }

        assertEquals(expected, actual)
        assertEquals(Look(LumosLook.BASE), watched.look)
        assertFalse(watched.isLookBlending)
    }

    @Test
    fun `a phase change starts a blend that lasts the blend's length`() {
        val builder = switched()
        builder.build(snapshot(THINKING, agents = listOf(agent(CognitivePhase.PLAN))), dt = DT)
        assertEquals(Look(LumosLook.REASONING, "twins"), builder.look, "the baseline wears the choice outright")
        assertFalse(builder.isLookBlending, "the baseline is a cut")

        builder.build(snapshot(THINKING, agents = listOf(agent(CognitivePhase.EXECUTE))), dt = DT)
        assertEquals(Look(LumosLook.WORKING), builder.look, "look names the target at once")
        assertTrue(builder.isLookBlending)

        var frames = 1
        while (builder.isLookBlending) {
            builder.build(snapshot(THINKING, agents = listOf(agent(CognitivePhase.EXECUTE))), dt = DT)
            frames++
        }
        assertEquals(36, frames, "0.6 s at 60 fps")
        assertFalse(builder.isLookBlending)
    }

    @Test
    fun `the weight never decreases and lands at exactly 1`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.lookTo(Look(LumosLook.WORKING))
        var previous = 0f
        val weights = ArrayList<Float>()
        while (builder.isLookBlending) {
            builder.build(snapshot(THINKING), dt = 0.07f)
            val weight = builder.lookBlendProgress
            assertTrue(weight >= previous, "weight fell from $previous to $weight")
            previous = weight
            weights += weight
        }
        assertEquals(1f, weights.last())
        assertTrue(weights.first() > 0f, "the first frame of a blend has already moved")
        assertTrue(weights.size > 2, "several frames in flight: $weights")
    }

    @Test
    fun `a blend mixes the two looks' modulations by the weight`() {
        // Rim-faded BASE to SEARCHING with the lens at rest (past 40 % of a hop), under a
        // still atmosphere, so the only motion is the blend's.
        val blending = VoxelFrameBuilder(initialResolution = RESOLUTION, config = LumosRenderConfig(rimFade = true))
        val base = VoxelFrameBuilder(initialResolution = RESOLUTION, config = LumosRenderConfig(rimFade = true))
        val searching = VoxelFrameBuilder(initialResolution = RESOLUTION, config = LumosRenderConfig(rimFade = true))
        searching.look = Look(LumosLook.SEARCHING)
        blending.phases = FramePhases.ZERO.copy(lookSeconds = 1.0)
        blending.lookTo(Look(LumosLook.SEARCHING))

        var previousAlpha: FloatArray? = null
        var previousScale: FloatArray? = null
        var moved = false
        while (blending.isLookBlending) {
            val frame = blending.build(snapshot(STILL), dt = DT)
            val w = blending.lookBlendProgress
            val from = base.buildSteadyState(STILL, blending.phases, 0L, 0L)
            val to = searching.buildSteadyState(STILL, blending.phases, 0L, 0L)
            val alphas = FloatArray(frame.cells.size)
            val scales = FloatArray(frame.cells.size)
            for (i in frame.cells.indices) {
                val a = from.cells[i].alpha ?: 1f
                val b = to.cells[i].alpha ?: 1f
                alphas[i] = frame.cells[i].alpha ?: 1f
                scales[i] = frame.cells[i].scale
                assertEquals(a * (1f - w) + b * w, alphas[i], 1e-5f, "alpha of $i at w=$w")
                assertEquals(
                    from.cells[i].scale * (1f - w) + to.cells[i].scale * w,
                    scales[i],
                    1e-5f,
                    "scale of $i at w=$w",
                )
            }
            previousAlpha?.let { last ->
                for (i in alphas.indices) {
                    val direction = (to.cells[i].alpha ?: 1f) - (from.cells[i].alpha ?: 1f)
                    val step = alphas[i] - last[i]
                    assertTrue(step * direction >= -1e-6f, "alpha of $i moved against the blend: $step vs $direction")
                    if (step != 0f) moved = true
                }
            }
            previousScale?.let { last ->
                for (i in scales.indices) {
                    val direction = to.cells[i].scale - from.cells[i].scale
                    val step = scales[i] - last[i]
                    assertTrue(step * direction >= -1e-6f, "scale of $i moved against the blend: $step vs $direction")
                }
            }
            previousAlpha = alphas
            previousScale = scales
        }
        assertTrue(moved, "cells moved during the blend")
        assertEquals(
            searching.buildSteadyState(STILL, blending.phases, 0L, 0L).cells,
            blending.build(snapshot(STILL), dt = 0f).cells,
            "landed on SEARCHING exactly",
        )
    }

    @Test
    fun `reversing a blend in place never jumps`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION, config = LumosRenderConfig(rimFade = true))
        builder.phases = FramePhases.ZERO.copy(lookSeconds = 1.0)
        builder.lookTo(Look(LumosLook.SEARCHING))

        val frames = ArrayList<VoxelFrame>()
        frames += builder.build(snapshot(STILL), dt = 0f)
        while (builder.lookBlendProgress < 0.3f) frames += builder.build(snapshot(STILL), dt = DT)
        val forwardSteps = frames.zipWithNext { a, b -> maxDelta(a, b) }
        val forwardStep = forwardSteps.max()

        builder.lookTo(Look(LumosLook.BASE))
        assertEquals(Look(LumosLook.BASE), builder.look)
        assertTrue(builder.isLookBlending)
        val turned = builder.build(snapshot(STILL), dt = DT)
        assertTrue(
            maxDelta(frames.last(), turned) <= forwardStep + 1e-5f,
            "the turn's step ${maxDelta(frames.last(), turned)} exceeds the blend's own $forwardStep",
        )

        var last = turned
        while (builder.isLookBlending) {
            val next = builder.build(snapshot(STILL), dt = DT)
            assertTrue(maxDelta(last, next) <= forwardStep + 1e-5f)
            last = next
        }
        val plain = VoxelFrameBuilder(initialResolution = RESOLUTION, config = LumosRenderConfig(rimFade = true))
        assertEquals(plain.buildSteadyState(STILL, builder.phases, 0L, 0L).cells, last.cells, "back on BASE exactly")
    }

    @Test
    fun `a third look mid-blend waits then blends`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.lookTo(Look(LumosLook.WORKING))
        repeat(10) { builder.build(snapshot(THINKING), dt = DT) }
        builder.lookTo(Look(LumosLook.REASONING))

        assertEquals(Look(LumosLook.WORKING), builder.look, "still heading to the first look")
        repeat(25) { builder.build(snapshot(THINKING), dt = DT) }
        assertTrue(builder.isLookBlending)
        assertEquals(Look(LumosLook.WORKING), builder.look)

        builder.build(snapshot(THINKING), dt = DT)
        assertEquals(Look(LumosLook.REASONING), builder.look, "the first blend landed and the next began")
        assertTrue(builder.isLookBlending)
        assertEquals(0f, builder.lookBlendProgress, "fresh from the landed look")

        repeat(36) { builder.build(snapshot(THINKING), dt = DT) }
        assertFalse(builder.isLookBlending)
        assertEquals(Look(LumosLook.REASONING), builder.look)
    }

    @Test
    fun `the latest look asked for mid-blend is the one taken up`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.lookTo(Look(LumosLook.WORKING))
        builder.build(snapshot(THINKING), dt = DT)
        builder.lookTo(Look(LumosLook.REASONING))
        builder.lookTo(Look(LumosLook.SEARCHING))
        repeat(36) { builder.build(snapshot(THINKING), dt = DT) }
        assertEquals(Look(LumosLook.SEARCHING), builder.look)
    }

    @Test
    fun `COMPLETE fires CHECK once`() {
        val builder = switched()
        builder.build(snapshot(THINKING, agents = listOf(agent(CognitivePhase.EXECUTE))), dt = DT)
        assertFalse(builder.hasActiveGlyph)

        val done = snapshot(THINKING, agents = listOf(agent(CognitivePhase.NONE, AgentActivityState.COMPLETE)))
        val fired = builder.build(done, dt = DT)
        assertEquals(LumosGlyph.CHECK.name, fired.glyph?.glyphName)
        assertEquals(Look(LumosLook.BASE), builder.look)

        // Let the glyph run out; a held COMPLETE fires nothing more.
        repeat(100) { builder.build(done, dt = DT) }
        assertFalse(builder.hasActiveGlyph, "CHECK fired once on arrival, not while COMPLETE holds")
    }

    @Test
    fun `a builder created on an already-COMPLETE agent fires nothing`() {
        val builder = switched()
        val done = snapshot(THINKING, agents = listOf(agent(CognitivePhase.NONE, AgentActivityState.COMPLETE)))
        val first = builder.build(done, dt = DT)
        assertNull(first.glyph)
        assertFalse(builder.hasActiveGlyph)
        assertFalse(builder.isLookBlending)
    }

    @Test
    fun `an escalated rising edge fires QUESTION and wears WAITING`() {
        val builder = switched()
        builder.build(snapshot(THINKING, agents = listOf(agent(CognitivePhase.EXECUTE))), dt = DT)

        val raised =
            builder.build(
                snapshot(THINKING, agents = listOf(agent(CognitivePhase.EXECUTE, escalated = true))),
                DT,
            )
        assertEquals(LumosGlyph.QUESTION.name, raised.glyph?.glyphName)
        assertEquals(Look(LumosLook.WAITING), builder.look)
        assertTrue(builder.isLookBlending)

        repeat(100) {
            builder.build(snapshot(THINKING, agents = listOf(agent(CognitivePhase.EXECUTE, escalated = true))), DT)
        }
        assertFalse(builder.hasActiveGlyph, "QUESTION fired on the edge only")

        builder.build(snapshot(THINKING, agents = listOf(agent(CognitivePhase.EXECUTE))), DT)
        assertEquals(Look(LumosLook.WORKING), builder.look, "the table's choice returns when the escalation clears")
    }

    @Test
    fun `unattended wears the background look and still shows its CHECK`() {
        val builder =
            VoxelFrameBuilder(initialResolution = RESOLUTION, config = LumosRenderConfig(unattended = true))
        builder.lookSwitch = CognitiveLookSwitch()
        builder.build(snapshot(THINKING, agents = listOf(agent(CognitivePhase.EXECUTE))), dt = DT)
        assertEquals(Look(LumosLook.BACKGROUND, "spiral"), builder.look)

        val done = snapshot(THINKING, agents = listOf(agent(CognitivePhase.NONE, AgentActivityState.COMPLETE)))
        val fired = builder.build(done, dt = DT)
        assertEquals(LumosGlyph.CHECK.name, fired.glyph?.glyphName)
        assertEquals(Look(LumosLook.BACKGROUND, "spiral"), builder.look)
        assertFalse(builder.isLookBlending, "the look did not change, so nothing blends")
    }

    @Test
    fun `SPAWNING ramps alpha from 0 to 1 over a second and leaves no alpha behind`() {
        val builder = switched()
        val spawning = snapshot(THINKING, agents = listOf(agent(CognitivePhase.NONE, AgentActivityState.SPAWNING)))
        val reference = VoxelFrameBuilder(initialResolution = RESOLUTION)

        var previous = -1f
        var frames = 0
        var landed = false
        while (!landed) {
            val frame = builder.build(spawning, dt = 0.1f)
            val expected = reference.build(snapshot(THINKING), dt = 0.1f)
            frames++
            val alpha = frame.cells.first().alpha ?: 1f
            assertTrue(frame.cells.all { (it.alpha ?: 1f) == alpha }, "presence is uniform across cells")
            assertTrue(alpha >= previous, "presence fell from $previous to $alpha")
            assertEquals(expected.ambient.glowIntensity * alpha, frame.ambient.glowIntensity, 1e-6f)
            if (frames == 1) assertTrue(alpha < 0.05f, "starts from nothing: $alpha")
            previous = alpha
            landed = alpha == 1f
        }
        assertEquals(10, frames, "a second of dt")
        val present = builder.build(spawning, dt = 0.1f)
        assertTrue(present.cells.all { it.alpha == null }, "fully present: no alpha carried")

        val idle = snapshot(THINKING, agents = listOf(agent(CognitivePhase.NONE, AgentActivityState.IDLE)))
        val settled = builder.build(idle, dt = 0.1f)
        assertTrue(settled.cells.all { it.alpha == null }, "a BASE frame after the spawn carries no alpha")
        assertEquals(Look(LumosLook.BASE), builder.look)
    }

    @Test
    fun `a still lands the spawn fade at once`() {
        val builder = switched()
        val spawning = snapshot(THINKING, agents = listOf(agent(CognitivePhase.NONE, AgentActivityState.SPAWNING)))
        val still = builder.buildStill(spawning, dt = DT)
        assertTrue(still.cells.all { it.alpha == null })
    }

    @Test
    fun `agentId selects the named agent and a missing agent gives BASE`() {
        val agents =
            listOf(
                agent(CognitivePhase.EXECUTE, id = "a"),
                agent(CognitivePhase.PLAN, id = "b"),
            )
        val named = VoxelFrameBuilder(initialResolution = RESOLUTION, config = LumosRenderConfig(agentId = "b"))
        named.lookSwitch = CognitiveLookSwitch()
        named.build(snapshot(THINKING, agents = agents, phase = CognitivePhase.EXECUTE), dt = DT)
        assertEquals(Look(LumosLook.REASONING, "twins"), named.look)

        val first = switched()
        first.build(snapshot(THINKING, agents = agents, phase = CognitivePhase.EXECUTE), dt = DT)
        assertEquals(Look(LumosLook.WORKING), first.look, "unnamed: the scene's dominant phase")

        val missing = VoxelFrameBuilder(initialResolution = RESOLUTION, config = LumosRenderConfig(agentId = "zz"))
        missing.lookSwitch = CognitiveLookSwitch()
        missing.build(snapshot(THINKING, agents = agents, phase = CognitivePhase.EXECUTE), dt = DT)
        assertEquals(Look(LumosLook.BASE), missing.look)
    }

    @Test
    fun `a manual look sticks until the signal next changes`() {
        val builder = switched()
        val executing = snapshot(THINKING, agents = listOf(agent(CognitivePhase.EXECUTE)))
        builder.build(executing, dt = DT)
        builder.look = Look(LumosLook.RETRYING)
        repeat(5) { builder.build(executing, dt = DT) }
        assertEquals(Look(LumosLook.RETRYING), builder.look)

        builder.build(snapshot(THINKING, agents = listOf(agent(CognitivePhase.PLAN))), dt = DT)
        assertEquals(Look(LumosLook.REASONING, "twins"), builder.look)
    }

    @Test
    fun `buildStill lands a blend and buildSteadyState has none`() {
        val builder = switched()
        builder.build(snapshot(THINKING, agents = listOf(agent(CognitivePhase.EXECUTE))), dt = DT)
        builder.build(snapshot(THINKING, agents = listOf(agent(CognitivePhase.PLAN))), dt = DT)
        assertTrue(builder.isLookBlending)

        val steady = builder.buildSteadyState(THINKING, builder.phases, 0L, 0L)
        assertTrue(builder.isLookBlending, "a steady-state frame leaves the blend as it was")
        val twins =
            VoxelFrameBuilder(
                initialResolution = RESOLUTION,
            ).apply { look = Look(LumosLook.REASONING, "twins") }
        assertEquals(
            twins.buildSteadyState(THINKING, builder.phases, 0L, 0L),
            steady,
            "and draws the incoming look alone",
        )

        builder.buildStill(snapshot(THINKING, agents = listOf(agent(CognitivePhase.PLAN))), dt = DT)
        assertFalse(builder.isLookBlending, "a still lands the blend")
        assertEquals(Look(LumosLook.REASONING, "twins"), builder.look)
    }

    @Test
    fun `a still rebuilds when the switch changes the look`() {
        val builder = switched()
        val first = builder.buildStill(snapshot(THINKING, agents = listOf(agent(CognitivePhase.EXECUTE))), dt = DT)
        val second = builder.buildStill(snapshot(THINKING, agents = listOf(agent(CognitivePhase.PLAN))), dt = DT)
        assertNotEquals(first.cells, second.cells)
    }

    @Test
    fun `setting look cuts a blend and drops the pending look`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.lookTo(Look(LumosLook.WORKING))
        builder.build(snapshot(THINKING), dt = DT)
        builder.lookTo(Look(LumosLook.REASONING))
        builder.look = Look(LumosLook.SEARCHING)
        assertFalse(builder.isLookBlending)
        repeat(40) { builder.build(snapshot(THINKING), dt = DT) }
        assertEquals(Look(LumosLook.SEARCHING), builder.look)
        assertFalse(builder.isLookBlending)
    }

    @Test
    fun `a sample change mid-blend is a cut`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.lookTo(Look(LumosLook.WORKING))
        builder.build(snapshot(THINKING), dt = DT)
        assertTrue(builder.isLookBlending)
        builder.build(snapshot(THINKING.copy(resolution = RESOLUTION + 1)), dt = DT)
        assertFalse(builder.isLookBlending)
        assertEquals(Look(LumosLook.WORKING), builder.look)
    }

    @Test
    fun `entering a look that owns the yaw turns the spin along the short arc`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.phases = FramePhases.ZERO.copy(orbRotationY = 3.0f)
        var last = builder.build(snapshot(THINKING), dt = DT).ambient.orbRotationY
        builder.lookTo(Look(LumosLook.RETRYING))
        var largest = 0f
        while (builder.isLookBlending) {
            val yaw = builder.build(snapshot(THINKING), dt = DT).ambient.orbRotationY
            var step = abs(yaw - last)
            if (step > FramePhases.TWO_PI / 2f) step = FramePhases.TWO_PI - step
            largest = maxOf(largest, step)
            last = yaw
        }
        // 3 rad of arc over 36 frames is under 0.1 rad a frame, eased; a jump would be near 3.
        assertTrue(largest < 0.25f, "the yaw jumped by $largest rad in one frame")
    }

    @Test
    fun `lookTo rejects a negative duration and cuts at zero`() {
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        kotlin.test.assertFailsWith<IllegalArgumentException> { builder.lookTo(Look(LumosLook.WORKING), -1f) }
        builder.lookTo(Look(LumosLook.WORKING), 0f)
        assertEquals(Look(LumosLook.WORKING), builder.look)
        assertFalse(builder.isLookBlending)
    }

    private fun switched(): VoxelFrameBuilder =
        VoxelFrameBuilder(initialResolution = RESOLUTION).apply { lookSwitch = CognitiveLookSwitch() }

    /** The largest change in any cell's alpha or scale between [a] and [b]. */
    private fun maxDelta(
        a: VoxelFrame,
        b: VoxelFrame,
    ): Float {
        var largest = 0f
        for (i in a.cells.indices) {
            largest = maxOf(largest, abs((a.cells[i].alpha ?: 1f) - (b.cells[i].alpha ?: 1f)))
            largest = maxOf(largest, abs(a.cells[i].scale - b.cells[i].scale))
        }
        return largest
    }

    private fun agent(
        phase: CognitivePhase,
        state: AgentActivityState = AgentActivityState.ACTIVE,
        escalated: Boolean = false,
        id: String = "agent",
    ): AgentVisualState =
        AgentVisualState(
            id = id,
            name = id,
            role = "reasoning",
            position = Vector2.ZERO,
            state = state,
            cognitivePhase = phase,
            escalated = escalated,
        )

    private fun snapshot(
        atmosphere: AtmosphereState,
        agents: List<AgentVisualState> = emptyList(),
        phase: CognitivePhase = agents.firstOrNull()?.cognitivePhase ?: CognitivePhase.NONE,
    ): SceneSnapshot =
        SceneSnapshot(
            frameIndex = 0L,
            elapsedTimeSeconds = 0f,
            coordinateSpace = CoordinateSpace.WORLD_CENTERED,
            agentStates = agents,
            substrateState = SubstrateState.create(2, 2),
            particleStates = emptyList(),
            flowConnections = emptyList(),
            flowField = null,
            waveformHeightField = null,
            waveformGridWidth = null,
            waveformGridDepth = null,
            cameraTransform = null,
            emitterStates = emptyList(),
            choreographyPhase = phase,
            atmosphere = atmosphere,
            atmosphereTransition = null,
        )

    private companion object {
        const val RESOLUTION = 4
        const val DT = 1f / 60f
        val THINKING: AtmosphereState = AtmospherePresets.THINKING.copy(resolution = RESOLUTION)

        /** THINKING with nothing of its own moving: no spin, breath, bump or jitter. */
        val STILL: AtmosphereState =
            THINKING.copy(
                rotationX = 0f,
                rotationY = 0f,
                pulseAmplitude = 0f,
                surfaceBump = 0f,
                noise = 0f,
            )
    }
}
