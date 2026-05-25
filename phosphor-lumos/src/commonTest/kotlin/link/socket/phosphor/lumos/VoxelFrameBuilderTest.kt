package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import link.socket.phosphor.color.NeutralColor
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.field.VoxelSphere
import link.socket.phosphor.field.facingCamera
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AtmospherePattern
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.AtmosphereTransition
import link.socket.phosphor.signal.CognitivePhase

class VoxelFrameBuilderTest {
    @Test
    fun `steady-state IDLE atmosphere emits one cell per voxel`() {
        val resolution = 6
        val builder = VoxelFrameBuilder(initialResolution = resolution)
        val snapshot = snapshot(atmosphere = AtmospherePresets.IDLE.copy(resolution = resolution))

        val frame = builder.build(snapshot, dt = 0.016f)

        assertEquals(resolution, frame.resolution)
        assertEquals(VoxelSphere(resolution).count, frame.cells.size)
    }

    @Test
    fun `two consecutive builds with dt zero produce bit-identical frames`() {
        val builder = VoxelFrameBuilder(initialResolution = 5)
        val snapshot = snapshot(atmosphere = AtmospherePresets.IDLE)

        val first = builder.build(snapshot, dt = 0f)
        val second = builder.build(snapshot, dt = 0f)

        assertEquals(first, second)
        assertEquals(0f, builder.pulsePhase)
        assertEquals(0f, builder.patternPhase)
    }

    @Test
    fun `successive builds with non-zero dt advance phase monotonically`() {
        val builder = VoxelFrameBuilder(initialResolution = 4)
        val snapshot = snapshot(atmosphere = AtmospherePresets.IDLE)

        val priorPulse = builder.pulsePhase
        val priorPattern = builder.patternPhase

        val first = builder.build(snapshot, dt = 0.016f)
        val midPulse = builder.pulsePhase
        val midPattern = builder.patternPhase
        val second = builder.build(snapshot, dt = 0.016f)
        val finalPulse = builder.pulsePhase
        val finalPattern = builder.patternPhase

        assertTrue(midPulse > priorPulse, "pulsePhase did not advance after first build")
        assertTrue(midPattern > priorPattern, "patternPhase did not advance after first build")
        assertTrue(finalPulse > midPulse, "pulsePhase did not advance after second build")
        assertTrue(finalPattern > midPattern, "patternPhase did not advance after second build")
        assertNotEquals(first.cells, second.cells)
    }

    @Test
    fun `bipolar strength collapses voxels at the pattern boundary`() {
        val solidBipolar =
            AtmospherePresets.IDLE.copy(
                pattern = AtmospherePattern.SOLID,
                bipolarStrength = 0.45f,
            )
        val builder = VoxelFrameBuilder(initialResolution = 5)

        val frame = builder.build(snapshot(atmosphere = solidBipolar), dt = 0f)

        assertTrue(frame.cells.isNotEmpty())
        frame.cells.forEach { cell ->
            assertTrue(
                cell.scale < 0.1f,
                "voxel at SOLID pattern boundary should be thinned, got scale=${cell.scale}",
            )
        }
    }

    @Test
    fun `bipolar voxels away from the boundary remain at full scale`() {
        val patternBipolar =
            AtmospherePresets.IDLE.copy(
                pattern = AtmospherePattern.LONGITUDE,
                bipolarStrength = 0.45f,
            )
        val builder = VoxelFrameBuilder(initialResolution = 6)

        val frame = builder.build(snapshot(atmosphere = patternBipolar), dt = 0f)

        val baseline = patternBipolar.voxelGap * (1f + 0f)
        val unthinnedCount = frame.cells.count { abs(it.scale - baseline) < 1e-5f }
        assertTrue(
            unthinnedCount > 0,
            "expected at least one voxel away from boundary to remain unthinned",
        )
    }

    @Test
    fun `crossfade between IDLE and UNCERTAIN produces colors distinct from either endpoint`() {
        val from = AtmospherePresets.IDLE
        val to = AtmospherePresets.UNCERTAIN
        val transition =
            AtmosphereTransition(
                from = from,
                to = to,
                fromPresetName = "idle",
                toPresetName = "uncertain",
                progressLinear = 0.5f,
                progressEased = 0.5f,
                easingName = "linear",
                durationSeconds = 1f,
            )

        val builder = VoxelFrameBuilder(initialResolution = 5)
        val sourceFrame = builder.build(snapshot(atmosphere = from), dt = 0f)
        val crossFrame =
            builder.build(
                snapshot(atmosphere = from, transition = transition),
                dt = 0f,
            )
        val targetFrame = builder.build(snapshot(atmosphere = to), dt = 0f)

        val sourceColors = sourceFrame.cells.map { Triple(it.red, it.green, it.blue) }
        val crossColors = crossFrame.cells.map { Triple(it.red, it.green, it.blue) }
        val targetColors = targetFrame.cells.map { Triple(it.red, it.green, it.blue) }

        assertNotEquals(sourceColors, crossColors)
        assertNotEquals(targetColors, crossColors)
    }

    @Test
    fun `pattern crossfade lands between pure source and pure target mix`() {
        val from = AtmospherePresets.IDLE
        val to = AtmospherePresets.LISTENING
        val sphere = VoxelSphere(4)
        val patternPhase = 0f

        val sample =
            sphere.voxels.first { voxel ->
                val fromMix =
                    VoxelFrameBuilder.evaluatePattern(from.pattern, voxel, patternPhase)
                val toMix =
                    VoxelFrameBuilder.evaluatePattern(to.pattern, voxel, patternPhase)
                abs(fromMix - toMix) > 0.05f
            }

        val fromMix = VoxelFrameBuilder.evaluatePattern(from.pattern, sample, patternPhase)
        val toMix = VoxelFrameBuilder.evaluatePattern(to.pattern, sample, patternPhase)
        val blended = fromMix * 0.5f + toMix * 0.5f

        assertNotEquals(fromMix, blended)
        assertNotEquals(toMix, blended)
        assertTrue(blended in minOf(fromMix, toMix)..maxOf(fromMix, toMix))
    }

    @Test
    fun `pulse phase stays continuous when frequency doubles between ticks`() {
        val builder = VoxelFrameBuilder(initialResolution = 3)
        val slow = AtmospherePresets.IDLE.copy(pulseFrequency = 0.3f)
        val fast = AtmospherePresets.IDLE.copy(pulseFrequency = 0.6f)
        val dt = 0.016f

        repeat(8) { builder.build(snapshot(atmosphere = slow), dt = dt) }
        val phaseBeforeChange = builder.pulsePhase

        builder.build(snapshot(atmosphere = fast), dt = dt)
        val phaseAfterChange = builder.pulsePhase

        val expectedDelta = dt * fast.pulseFrequency * 2f * kotlin.math.PI.toFloat()
        val actualDelta = phaseAfterChange - phaseBeforeChange
        assertTrue(
            abs(actualDelta - expectedDelta) < 1e-5f,
            "expected phase to advance by $expectedDelta but advanced by $actualDelta",
        )
    }

    @Test
    fun `phase accumulators wrap to stay within their respective bounds`() {
        val builder = VoxelFrameBuilder(initialResolution = 2)
        val fast =
            AtmospherePresets.IDLE.copy(
                pulseFrequency = 50f,
                patternSpeed = 50f,
                rotationX = 50f,
                rotationY = 50f,
            )
        val twoPi = 2f * kotlin.math.PI.toFloat()
        val patternWrap = twoPi * 10f

        repeat(200) { builder.build(snapshot(atmosphere = fast), dt = 0.5f) }

        assertWrappedPhase("pulsePhase", builder.pulsePhase, twoPi)
        assertWrappedPhase("patternPhase", builder.patternPhase, patternWrap)
        assertWrappedPhase("orbRotationX", builder.orbRotationX, twoPi)
        assertWrappedPhase("orbRotationY", builder.orbRotationY, twoPi)
    }

    @Test
    fun `resolution change rebuilds the voxel sphere`() {
        val builder = VoxelFrameBuilder(initialResolution = 4)
        val higher = AtmospherePresets.IDLE.copy(resolution = 7)

        val frame = builder.build(snapshot(atmosphere = higher), dt = 0f)

        assertEquals(7, builder.voxelSphere.resolution)
        assertEquals(VoxelSphere(7).count, frame.cells.size)
    }

    @Test
    fun `omitBelowScale filters voxels below the threshold`() {
        val solidBipolar =
            AtmospherePresets.IDLE.copy(
                pattern = AtmospherePattern.SOLID,
                bipolarStrength = 0.45f,
            )
        val builder =
            VoxelFrameBuilder(
                initialResolution = 5,
                config = LumosRenderConfig(omitBelowScale = 0.01f),
            )

        val frame = builder.build(snapshot(atmosphere = solidBipolar), dt = 0f)

        assertEquals(0, frame.cells.size)
    }

    @Test
    fun `globalYSquashOverride multiplies the atmosphere ySquash`() {
        val builder =
            VoxelFrameBuilder(
                initialResolution = 4,
                config = LumosRenderConfig(globalYSquashOverride = 0.5f),
            )
        val baselineBuilder = VoxelFrameBuilder(initialResolution = 4)
        val flatAtmosphere =
            AtmospherePresets.IDLE.copy(
                noise = 0f,
                surfaceBump = 0f,
                pulseAmplitude = 0f,
            )

        val override = builder.build(snapshot(atmosphere = flatAtmosphere), dt = 0f)
        val baseline = baselineBuilder.build(snapshot(atmosphere = flatAtmosphere), dt = 0f)

        override.cells.zip(baseline.cells).forEach { (overridden, normal) ->
            assertEquals(normal.x, overridden.x, "x should not change")
            assertEquals(normal.z, overridden.z, "z should not change")
            assertEquals(normal.y * 0.5f, overridden.y, absoluteTolerance = 1e-5f)
        }
    }

    @Test
    fun `ambient glow comes from the OKLab midpoint of primary and secondary hues`() {
        val builder = VoxelFrameBuilder(initialResolution = 3)
        val frame = builder.build(snapshot(atmosphere = AtmospherePresets.IDLE), dt = 0f)

        val ambient = frame.ambient
        assertTrue(ambient.glowRed in 0f..1f)
        assertTrue(ambient.glowGreen in 0f..1f)
        assertTrue(ambient.glowBlue in 0f..1f)
        assertEquals(AtmospherePresets.IDLE.glow, ambient.glowIntensity)
        assertEquals(0f, ambient.orbRotationZ)
    }

    @Test
    fun `build throws when snapshot atmosphere is null`() {
        val builder = VoxelFrameBuilder(initialResolution = 3)
        val snapshot = snapshot(atmosphere = null)

        assertFailsWith<IllegalStateException> { builder.build(snapshot, dt = 0.016f) }
    }

    @Test
    fun `frame tick and timestamp track the snapshot`() {
        val builder = VoxelFrameBuilder(initialResolution = 3)
        val snapshot =
            snapshot(atmosphere = AtmospherePresets.IDLE)
                .copy(frameIndex = 42L, elapsedTimeSeconds = 1.5f)

        val frame = builder.build(snapshot, dt = 0f)

        assertEquals(42L, frame.tick)
        assertEquals(1_500L, frame.timestampEpochMillis)
    }

    @Test
    fun `queueGlyph causes the next build to populate glyph state`() {
        val builder = VoxelFrameBuilder(initialResolution = 5)

        builder.queueGlyph(LumosGlyph.CHECK)
        val frame = builder.build(snapshot(atmosphere = AtmospherePresets.IDLE), dt = 0f)

        val glyph = assertNotNull(frame.glyph)
        assertEquals("CHECK", glyph.glyphName)
        assertEquals(0f, glyph.progress, absoluteTolerance = 1e-5f)
        assertTrue(builder.hasActiveGlyph)
    }

    @Test
    fun `glyph clears after its duration elapses`() {
        val builder = VoxelFrameBuilder(initialResolution = 5)

        builder.queueGlyph(LumosGlyph.CHECK, durationSeconds = 0.2f)
        val activeFrame = builder.build(snapshot(atmosphere = AtmospherePresets.IDLE), dt = 0.1f)
        val expiredFrame = builder.build(snapshot(atmosphere = AtmospherePresets.IDLE), dt = 0.1f)

        assertNotNull(activeFrame.glyph)
        assertNull(expiredFrame.glyph)
        assertFalse(builder.hasActiveGlyph)
    }

    @Test
    fun `glyph-member voxels lerp to the glyph accent color`() {
        val atmosphere =
            AtmospherePresets.IDLE.copy(
                resolution = 8,
                noise = 0f,
                pulseAmplitude = 0f,
                rotationX = 0f,
                rotationY = 0f,
                surfaceBump = 0f,
            )
        val builder = VoxelFrameBuilder(initialResolution = atmosphere.resolution)
        val shape = GlyphShape.forGlyph(LumosGlyph.CHECK)
        val sampleIndex =
            builder.voxelSphere.voxels.indexOfFirst { voxel ->
                voxel.facingCamera(Vector3.ZERO) &&
                    shape.contains(voxel.unitDirection.x, voxel.unitDirection.y)
            }

        assertTrue(sampleIndex >= 0, "expected at least one CHECK glyph voxel at resolution ${atmosphere.resolution}")

        builder.queueGlyph(LumosGlyph.CHECK, durationSeconds = 1.5f)
        val frame = builder.build(snapshot(atmosphere = atmosphere), dt = 0.3f)
        val cell = frame.cells[sampleIndex]
        val accent =
            NeutralColor.fromHsl(
                LumosGlyph.CHECK.hue,
                LumosGlyph.CHECK.saturation,
                LumosGlyph.CHECK.lightness,
            )

        assertEquals(accent.red, cell.red, absoluteTolerance = 0.02f)
        assertEquals(accent.green, cell.green, absoluteTolerance = 0.02f)
        assertEquals(accent.blue, cell.blue, absoluteTolerance = 0.02f)
    }

    @Test
    fun `queueGlyph replaces the active glyph`() {
        val builder = VoxelFrameBuilder(initialResolution = 5)

        builder.queueGlyph(LumosGlyph.CHECK, durationSeconds = 1.5f)
        builder.build(snapshot(atmosphere = AtmospherePresets.IDLE), dt = 0.3f)
        builder.queueGlyph(LumosGlyph.QUESTION, durationSeconds = 1.5f)
        val frame = builder.build(snapshot(atmosphere = AtmospherePresets.IDLE), dt = 0f)

        val glyph = assertNotNull(frame.glyph)
        assertEquals("QUESTION", glyph.glyphName)
        assertEquals(0f, glyph.progress, absoluteTolerance = 1e-5f)
    }

    private fun snapshot(
        atmosphere: AtmosphereState?,
        transition: AtmosphereTransition? = null,
    ): SceneSnapshot =
        SceneSnapshot(
            frameIndex = 0L,
            elapsedTimeSeconds = 0f,
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
            atmosphereTransition = transition,
        )

    private fun assertWrappedPhase(
        name: String,
        actual: Float,
        upperBound: Float,
    ) {
        val tolerance = 1e-4f
        assertTrue(
            actual >= -tolerance && actual <= upperBound + tolerance,
            "$name should stay in [0, $upperBound], got $actual",
        )
    }
}
