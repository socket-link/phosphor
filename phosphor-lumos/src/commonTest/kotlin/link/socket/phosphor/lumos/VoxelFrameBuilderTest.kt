package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
    fun `voxel gap is the empty fraction of a cell rather than the cube size`() {
        // A gap is what is left out, so the cube fills what remains. Asserted at
        // dt = 0 and pulseAmplitude = 0 so `scale` is the fill and nothing else.
        val resting = AtmospherePresets.IDLE.copy(pulseAmplitude = 0f, resolution = 4)

        listOf(0f, 0.05f, 0.25f, 0.4f).forEach { gap ->
            val builder = VoxelFrameBuilder(initialResolution = 4)
            val frame = builder.build(snapshot(atmosphere = resting.copy(voxelGap = gap)), dt = 0f)

            assertTrue(frame.cells.isNotEmpty())
            frame.cells.forEach { cell ->
                assertEquals(1f - gap, cell.scale, 1e-5f, "voxelGap $gap should fill ${1f - gap}")
            }
        }
    }

    @Test
    fun `a closed gap fills the lattice solid`() {
        val builder = VoxelFrameBuilder(initialResolution = 4)
        val solid = AtmospherePresets.IDLE.copy(voxelGap = 0f, pulseAmplitude = 0f, resolution = 4)

        val frame = builder.build(snapshot(atmosphere = solid), dt = 0f)

        frame.cells.forEach { cell ->
            assertEquals(1f, cell.scale, 1e-5f, "a zero gap should leave voxels at full size")
        }
    }

    @Test
    fun `a gap beyond a full cell clamps to invisible rather than inverting the cube`() {
        val builder = VoxelFrameBuilder(initialResolution = 4)
        val overshot = AtmospherePresets.IDLE.copy(voxelGap = 1.4f, pulseAmplitude = 0f, resolution = 4)

        val frame = builder.build(snapshot(atmosphere = overshot), dt = 0f)

        assertTrue(frame.cells.isNotEmpty())
        frame.cells.forEach { cell ->
            assertEquals(0f, cell.scale, 1e-5f, "an over-full gap should clamp to 0, got ${cell.scale}")
        }
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
        // SOLID holds every voxel at mix = 0.5, which is the centre of the bipolar
        // band, so the whole lattice thins to nothing. Compared against the
        // un-thinned fill rather than a bare constant: a threshold below the
        // baseline would pass even if no thinning happened at all.
        val unthinned = 1f - solidBipolar.voxelGap
        frame.cells.forEach { cell ->
            assertTrue(
                cell.scale < unthinned * 0.1f,
                "voxel at SOLID pattern boundary should be thinned, got scale=${cell.scale} " +
                    "against un-thinned fill $unthinned",
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

        val baseline = (1f - patternBipolar.voxelGap) * (1f + 0f)
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
        val sample = Forms.Lattice(4).sample(0, LumosLook.BASE, 0f)
        val patternPhase = 0f

        val index =
            (0 until sample.count).first { index ->
                val fromMix =
                    VoxelFrameBuilder.evaluatePattern(from.pattern, sample, index, patternPhase)
                val toMix =
                    VoxelFrameBuilder.evaluatePattern(to.pattern, sample, index, patternPhase)
                abs(fromMix - toMix) > 0.05f
            }

        val fromMix = VoxelFrameBuilder.evaluatePattern(from.pattern, sample, index, patternPhase)
        val toMix = VoxelFrameBuilder.evaluatePattern(to.pattern, sample, index, patternPhase)
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

    @Test
    fun `phases advance exactly as FramePhases does`() {
        val atmosphere = AtmospherePresets.THINKING.copy(resolution = 3)
        val builder = VoxelFrameBuilder(initialResolution = atmosphere.resolution)
        var expected = FramePhases.ZERO

        repeat(10) {
            builder.build(snapshot(atmosphere = atmosphere), dt = 0.05f)
            expected = expected.advanced(atmosphere, 0.05f)
            assertEquals(expected, builder.phases)
        }
        assertEquals(builder.phases.pulsePhase, builder.pulsePhase)
        assertEquals(builder.phases.patternPhase, builder.patternPhase)
        assertEquals(builder.phases.orbRotationX, builder.orbRotationX)
        assertEquals(builder.phases.orbRotationY, builder.orbRotationY)
    }

    @Test
    fun `buildSteadyState at the builder's own phases is the steady-state frame it just built`() {
        val atmosphere = AtmospherePresets.THINKING.copy(resolution = 5)
        val builder = VoxelFrameBuilder(initialResolution = atmosphere.resolution)
        repeat(7) { builder.build(snapshot(atmosphere = atmosphere), dt = 0.04f) }
        val live =
            builder.build(
                snapshot(atmosphere = atmosphere).copy(frameIndex = 8L, elapsedTimeSeconds = 0.32f),
                dt = 0.04f,
            )

        val steady =
            builder.buildSteadyState(
                atmosphere = atmosphere,
                phases = builder.phases,
                tick = live.tick,
                timestampEpochMillis = live.timestampEpochMillis,
            )

        assertEquals(live, steady)
    }

    @Test
    fun `a rewound steady-state frame matches the frame the builder produced there`() {
        // THINKING rather than a bipolar preset: its colour is continuous in the
        // pattern mix, so a phase a few ulps off cannot flip a cell across a hue step.
        val atmosphere = AtmospherePresets.THINKING.copy(resolution = 4)
        val builder = VoxelFrameBuilder(initialResolution = atmosphere.resolution)
        val dt = 1f / 24f
        val produced =
            List(40) { index ->
                builder.build(snapshot(atmosphere = atmosphere).copy(frameIndex = index + 1L), dt = dt)
            }

        val rewound =
            builder.buildSteadyState(
                atmosphere = atmosphere,
                phases = builder.phases.advanced(atmosphere, -12 * dt),
                tick = 28L,
                timestampEpochMillis = 0L,
            )

        val expected = produced[27]
        val tolerance = 1e-4f
        assertEquals(expected.tick, rewound.tick)
        assertEquals(expected.cells.size, rewound.cells.size)
        expected.cells.zip(rewound.cells).forEachIndexed { index, (want, got) ->
            assertEquals(want.x, got.x, tolerance, "x of cell $index")
            assertEquals(want.y, got.y, tolerance, "y of cell $index")
            assertEquals(want.z, got.z, tolerance, "z of cell $index")
            assertEquals(want.scale, got.scale, tolerance, "scale of cell $index")
            assertEquals(want.red, got.red, tolerance, "red of cell $index")
            assertEquals(want.green, got.green, tolerance, "green of cell $index")
            assertEquals(want.blue, got.blue, tolerance, "blue of cell $index")
        }
        assertEquals(expected.ambient.glowRed, rewound.ambient.glowRed)
        assertEquals(expected.ambient.glowGreen, rewound.ambient.glowGreen)
        assertEquals(expected.ambient.glowBlue, rewound.ambient.glowBlue)
        assertEquals(expected.ambient.orbRotationX, rewound.ambient.orbRotationX, tolerance)
        assertEquals(expected.ambient.orbRotationY, rewound.ambient.orbRotationY, tolerance)
    }

    @Test
    fun `buildSteadyState leaves the builder's phases and glyph where they were`() {
        val atmosphere = AtmospherePresets.IDLE.copy(resolution = 4)
        val builder = VoxelFrameBuilder(initialResolution = atmosphere.resolution)
        builder.queueGlyph(LumosGlyph.CHECK, durationSeconds = 1.5f)
        builder.build(snapshot(atmosphere = atmosphere), dt = 0.3f)
        val phasesBefore = builder.phases

        val steady =
            builder.buildSteadyState(
                atmosphere = atmosphere,
                phases = builder.phases.advanced(atmosphere, -0.3f),
                tick = 0L,
                timestampEpochMillis = 0L,
            )
        val next = builder.build(snapshot(atmosphere = atmosphere), dt = 0f)

        assertEquals(phasesBefore, builder.phases)
        assertNull(steady.glyph, "a steady-state frame carries no glyph")
        // The live glyph went on burning where it was: the zero-dt build finds it at the same age.
        assertEquals(0.2f, assertNotNull(next.glyph).progress, absoluteTolerance = 1e-5f)
    }

    @Test
    fun `buildSteadyState rejects an atmosphere at another resolution`() {
        val builder = VoxelFrameBuilder(initialResolution = 4)
        builder.build(snapshot(atmosphere = AtmospherePresets.IDLE.copy(resolution = 4)), dt = 0f)

        assertFailsWith<IllegalArgumentException> {
            builder.buildSteadyState(
                atmosphere = AtmospherePresets.IDLE.copy(resolution = 7),
                phases = builder.phases,
                tick = 0L,
                timestampEpochMillis = 0L,
            )
        }
    }

    @Test
    fun `the form defaults to the lattice at the initial resolution`() {
        val builder = VoxelFrameBuilder(initialResolution = 5)

        val form = assertIs<Forms.Lattice>(builder.form)
        assertEquals(5, form.resolution)
        assertSame(form.sphere, builder.voxelSphere)
    }

    @Test
    fun `the form is sampled once per build with the lattice's count and the frame's time`() {
        val builder = VoxelFrameBuilder(initialResolution = 4)
        val recording = RecordingForm(builder.form)
        builder.form = recording
        val atmosphere = AtmospherePresets.IDLE.copy(resolution = 4)

        builder.build(snapshot(atmosphere = atmosphere).copy(elapsedTimeSeconds = 1.25f), dt = 0f)
        builder.build(snapshot(atmosphere = atmosphere).copy(elapsedTimeSeconds = 1.5f), dt = 0f)

        assertEquals(
            listOf(
                Triple(VoxelSphere(4).count, LumosLook.BASE, 1.25f),
                Triple(VoxelSphere(4).count, LumosLook.BASE, 1.5f),
            ),
            recording.calls,
        )
    }

    @Test
    fun `cells follow the form with one cell per sampled position in sample order`() {
        val rest = listOf(Vector3(0.5f, 0f, 0f), Vector3(0f, 0.5f, 0f), Vector3(0f, 0f, -0.5f))
        val builder = VoxelFrameBuilder(initialResolution = 6)
        builder.form = LumosForm { _, _, _ -> FormSample.derive(rest) }
        val still =
            AtmospherePresets.IDLE.copy(
                resolution = 6,
                noise = 0f,
                surfaceBump = 0f,
                pulseAmplitude = 0f,
                ySquash = 1f,
            )

        val frame = builder.build(snapshot(atmosphere = still), dt = 0f)

        assertEquals(3, frame.cells.size)
        assertEquals(rest, frame.cells.map { Vector3(it.x, it.y, it.z) })
        // The field keeps its meaning: the resolution the atmosphere asked for, whatever the form.
        assertEquals(6, frame.resolution)
        assertEquals(6, builder.voxelSphere.resolution)
    }

    @Test
    fun `a framed form is drawn at its scale with cells sized against the lattice`() {
        val rest = listOf(Vector3(0.5f, 0f, 0f), Vector3(0f, 0f, -0.5f))
        val builder = VoxelFrameBuilder(initialResolution = 10)
        builder.form =
            object : FramedForm {
                override val scale: Float get() = 1.2f

                // A cell a tenth of a unit across: twice the lattice's at resolution 10.
                override fun cellRadius(count: Int): Float = 0.1f

                override fun sample(
                    count: Int,
                    look: LumosLook,
                    time: Float,
                ): FormSample = FormSample.derive(rest)
            }
        val still =
            AtmospherePresets.IDLE.copy(
                noise = 0f,
                surfaceBump = 0f,
                pulseAmplitude = 0f,
                ySquash = 1f,
                voxelGap = 0f,
            )

        val frame = builder.build(snapshot(atmosphere = still), dt = 0f)

        assertEquals(
            listOf(Vector3(0.6f, 0f, 0f), Vector3(0f, 0f, -0.6f)),
            frame.cells.map { Vector3(it.x, it.y, it.z) },
        )
        frame.cells.forEach { assertEquals(2f, it.scale, 1e-6f) }
    }

    @Test
    fun `an unframed form and the lattice are drawn as before`() {
        val rest = listOf(Vector3(0.5f, 0f, 0f))
        val builder = VoxelFrameBuilder(initialResolution = 10)
        builder.form = LumosForm { _, _, _ -> FormSample.derive(rest) }
        val still = AtmospherePresets.IDLE.copy(noise = 0f, surfaceBump = 0f, pulseAmplitude = 0f, ySquash = 1f)

        val cell = builder.build(snapshot(atmosphere = still), dt = 0f).cells.single()

        assertEquals(0.5f, cell.x)
        assertEquals(1f - still.voxelGap, cell.scale, 1e-6f)
    }

    @Test
    fun `a resolution change replaces a lattice form and leaves another form alone`() {
        val latticeBuilder = VoxelFrameBuilder(initialResolution = 4)
        val formBuilder = VoxelFrameBuilder(initialResolution = 4)
        val custom = RecordingForm(LumosForm { _, _, _ -> FormSample.derive(listOf(Vector3.ZERO)) })
        formBuilder.form = custom
        val higher = AtmospherePresets.IDLE.copy(resolution = 7)

        val latticeFrame = latticeBuilder.build(snapshot(atmosphere = higher), dt = 0f)
        val formFrame = formBuilder.build(snapshot(atmosphere = higher), dt = 0f)

        assertEquals(7, assertIs<Forms.Lattice>(latticeBuilder.form).resolution)
        assertEquals(VoxelSphere(7).count, latticeFrame.cells.size)
        assertSame(custom, formBuilder.form)
        assertEquals(1, formFrame.cells.size)
        assertEquals(7, formFrame.resolution)
        assertEquals(7, formBuilder.voxelSphere.resolution)
        assertEquals(VoxelSphere(7).count, custom.calls.single().first, "the new resolution's budget is offered")
    }

    @Test
    fun `setting a lattice form moves voxelSphere with it until the atmosphere says otherwise`() {
        val builder = VoxelFrameBuilder(initialResolution = 4)
        val lattice = Forms.Lattice(6)

        builder.form = lattice
        assertSame(lattice.sphere, builder.voxelSphere)

        val frame = builder.build(snapshot(atmosphere = AtmospherePresets.IDLE.copy(resolution = 4)), dt = 0f)
        assertEquals(4, assertIs<Forms.Lattice>(builder.form).resolution)
        assertEquals(VoxelSphere(4).count, frame.cells.size)
    }

    @Test
    fun `buildSteadyState renders a settled form that is not a lattice`() {
        val builder = VoxelFrameBuilder(initialResolution = 4)
        builder.form = LumosForm { _, _, _ -> FormSample.derive(listOf(Vector3(0.5f, 0f, 0f))) }
        val atmosphere = AtmospherePresets.IDLE.copy(resolution = 4)
        val live = builder.build(snapshot(atmosphere = atmosphere), dt = 0f)

        val steady =
            builder.buildSteadyState(
                atmosphere = atmosphere,
                phases = builder.phases,
                tick = 0L,
                timestampEpochMillis = 0L,
            )

        assertEquals(live.cells, steady.cells)
    }

    private class RecordingForm(private val delegate: LumosForm) : LumosForm {
        val calls = mutableListOf<Triple<Int, LumosLook, Float>>()

        override fun sample(
            count: Int,
            look: LumosLook,
            time: Float,
        ): FormSample {
            calls += Triple(count, look, time)
            return delegate.sample(count, look, time)
        }
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
