package link.socket.phosphor.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.choreography.AgentLayer
import link.socket.phosphor.choreography.AgentLayoutOrientation
import link.socket.phosphor.emitter.EmitterEffect
import link.socket.phosphor.emitter.EmitterManager
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector3
import link.socket.phosphor.palette.AsciiLuminancePalette
import link.socket.phosphor.palette.CognitiveColorRamp

class WaveformRasterizerEmitterTest {
    private val screenWidth = 40
    private val screenHeight = 20
    private val projector = ScreenProjector(screenWidth, screenHeight)
    private val lighting = SurfaceLighting()
    private val rasterizer = WaveformRasterizer(screenWidth, screenHeight, projector, lighting)

    private val camera =
        Camera(
            position = Vector3(0f, 10f, 15f),
            target = Vector3.ZERO,
        )

    private fun createPopulatedWaveform(): CognitiveWaveform {
        val waveform = CognitiveWaveform(gridWidth = 20, gridDepth = 15)
        val substrate = SubstrateState.create(20, 15, 0.5f)
        val agents = AgentLayer(20, 15, AgentLayoutOrientation.CUSTOM)
        waveform.update(substrate, agents, null, dt = 1f)
        return waveform
    }

    @Test
    fun `rasterize without emitter manager produces same result as before`() {
        val waveform = createPopulatedWaveform()

        val gridWithout =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                CognitiveColorRamp.NEUTRAL,
            )

        val gridWithNull =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                CognitiveColorRamp.NEUTRAL,
                emitterManager = null,
            )

        assertEquals(gridWithout.size, gridWithNull.size)
        for (y in gridWithout.indices) {
            for (x in gridWithout[y].indices) {
                assertEquals(
                    gridWithout[y][x],
                    gridWithNull[y][x],
                    "Cell at ($x, $y) should be identical with no emitter manager",
                )
            }
        }
    }

    @Test
    fun `rasterize with empty emitter manager produces same result as without`() {
        val waveform = createPopulatedWaveform()
        val emitterManager = EmitterManager()

        val gridWithout =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                CognitiveColorRamp.NEUTRAL,
            )

        val gridWith =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                CognitiveColorRamp.NEUTRAL,
                emitterManager = emitterManager,
            )

        for (y in gridWithout.indices) {
            for (x in gridWithout[y].indices) {
                assertEquals(
                    gridWithout[y][x],
                    gridWith[y][x],
                    "Cell at ($x, $y) should be identical with empty emitter manager",
                )
            }
        }
    }

    @Test
    fun `height pulse at center modifies rasterized output`() {
        val waveform = createPopulatedWaveform()
        val emitterManager = EmitterManager()
        emitterManager.emit(
            EmitterEffect.HeightPulse(duration = 2f, radius = 5f, maxHeightBoost = 5f),
            Vector3.ZERO,
        )
        emitterManager.update(0.3f) // Advance to mid-rise

        val gridWithout =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                CognitiveColorRamp.NEUTRAL,
            )

        val gridWith =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                CognitiveColorRamp.NEUTRAL,
                emitterManager = emitterManager,
            )

        // At least some cells should differ due to height/luminance modification
        var differences = 0
        for (y in gridWithout.indices) {
            for (x in gridWithout[y].indices) {
                if (gridWithout[y][x] != gridWith[y][x]) differences++
            }
        }

        assertTrue(
            differences > 0,
            "HeightPulse should modify at least some cells, got $differences differences",
        )
    }

    @Test
    fun `color wash overrides cell colors`() {
        val waveform = createPopulatedWaveform()
        val emitterManager = EmitterManager()
        emitterManager.emit(
            EmitterEffect.ColorWash(
                duration = 2f,
                // Large radius to cover the surface
                radius = 15f,
                colorRamp = CognitiveColorRamp.EXECUTE,
                // Fast wave to ensure it covers the area
                waveFrontSpeed = 100f,
            ),
            Vector3.ZERO,
        )
        emitterManager.update(0.2f)

        val gridBase =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                // Neutral uses grays
                CognitiveColorRamp.NEUTRAL,
            )

        val gridWash =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                CognitiveColorRamp.NEUTRAL,
                emitterManager = emitterManager,
            )

        // Collect non-empty cell colors from each
        val baseColors =
            gridBase.flatMap { it.toList() }
                .filter { it != AsciiCell.EMPTY }
                .map { it.fgColor }
                .toSet()

        val washColors =
            gridWash.flatMap { it.toList() }
                .filter { it != AsciiCell.EMPTY }
                .map { it.fgColor }
                .toSet()

        // ColorWash should introduce colors from EXECUTE ramp not in NEUTRAL
        val newColors = washColors - baseColors
        assertTrue(
            newColors.isNotEmpty() || washColors != baseColors,
            "ColorWash should change some cell colors",
        )
    }

    @Test
    fun `confetti overrides characters`() {
        val waveform = createPopulatedWaveform()
        val emitterManager = EmitterManager()
        emitterManager.emit(
            EmitterEffect.Confetti(duration = 2f, radius = 15f),
            Vector3.ZERO,
        )
        emitterManager.update(0.2f)

        val gridConfetti =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                CognitiveColorRamp.NEUTRAL,
                emitterManager = emitterManager,
            )

        // Confetti characters are: ✦✧⚡★·*
        val confettiChars = "\u2726\u2727\u26A1\u2605\u00B7*".toSet()
        val renderedChars =
            gridConfetti.flatMap { it.toList() }
                .filter { it != AsciiCell.EMPTY }
                .map { it.char }
                .toSet()

        val hasConfetti = renderedChars.any { it in confettiChars }
        assertTrue(hasConfetti, "Confetti effect should inject special characters into the output")
    }

    @Test
    fun `spark burst modifies luminance and can override palette`() {
        val waveform = createPopulatedWaveform()
        val emitterManager = EmitterManager()
        emitterManager.emit(
            EmitterEffect.SparkBurst(
                duration = 2f,
                radius = 15f,
                // Fast expansion to cover area
                expansionSpeed = 50f,
                palette = AsciiLuminancePalette.EXECUTE,
            ),
            Vector3.ZERO,
        )
        emitterManager.update(0.1f)

        val gridBase =
            rasterizer.rasterize(
                waveform,
                camera,
                // Use a distinctly different palette
                AsciiLuminancePalette.PERCEIVE,
                CognitiveColorRamp.PERCEIVE,
            )

        val gridBurst =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.PERCEIVE,
                CognitiveColorRamp.PERCEIVE,
                emitterManager = emitterManager,
            )

        var differences = 0
        for (y in gridBase.indices) {
            for (x in gridBase[y].indices) {
                if (gridBase[y][x] != gridBurst[y][x]) differences++
            }
        }

        assertTrue(
            differences > 0,
            "SparkBurst should modify at least some cells",
        )
    }

    @Test
    fun `high intensity effect produces bold cells`() {
        val waveform = createPopulatedWaveform()
        val emitterManager = EmitterManager()
        // Confetti at peak intensity (just started, at center) should produce bold cells
        emitterManager.emit(
            EmitterEffect.Confetti(duration = 2f, radius = 15f),
            Vector3.ZERO,
        )
        emitterManager.update(0.05f) // Very early = high intensity near center

        val grid =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                CognitiveColorRamp.NEUTRAL,
                emitterManager = emitterManager,
            )

        val boldCells =
            grid.flatMap { it.toList() }
                .filter { it != AsciiCell.EMPTY && it.bold }

        assertTrue(
            boldCells.isNotEmpty(),
            "High intensity confetti should produce some bold cells",
        )
    }

    @Test
    fun `expired effects do not modify output`() {
        val waveform = createPopulatedWaveform()
        val emitterManager = EmitterManager()
        emitterManager.emit(
            EmitterEffect.HeightPulse(duration = 0.5f, radius = 5f, maxHeightBoost = 5f),
            Vector3.ZERO,
        )
        // Advance past expiration
        emitterManager.update(0.6f)

        assertEquals(0, emitterManager.activeCount, "Effect should be expired")

        val gridBase =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                CognitiveColorRamp.NEUTRAL,
            )

        val gridExpired =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                CognitiveColorRamp.NEUTRAL,
                emitterManager = emitterManager,
            )

        for (y in gridBase.indices) {
            for (x in gridBase[y].indices) {
                assertEquals(
                    gridBase[y][x],
                    gridExpired[y][x],
                    "Expired effects should not change output at ($x, $y)",
                )
            }
        }
    }

    @Test
    fun `rasterize grid dimensions unchanged with emitter manager`() {
        val waveform = createPopulatedWaveform()
        val emitterManager = EmitterManager()
        emitterManager.emit(EmitterEffect.HeightPulse(), Vector3.ZERO)
        emitterManager.update(0.1f)

        val grid =
            rasterizer.rasterize(
                waveform,
                camera,
                AsciiLuminancePalette.STANDARD,
                CognitiveColorRamp.NEUTRAL,
                emitterManager = emitterManager,
            )

        assertEquals(screenHeight, grid.size, "grid should have $screenHeight rows")
        for (row in grid) {
            assertEquals(screenWidth, row.size, "each row should have $screenWidth cols")
        }
    }
}
