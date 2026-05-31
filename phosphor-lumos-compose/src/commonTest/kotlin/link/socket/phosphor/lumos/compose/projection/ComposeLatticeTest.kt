package link.socket.phosphor.lumos.compose.projection

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelCell
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.lumos.VoxelGlyphState
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase

class ComposeLatticeTest {
    @Test
    fun `constructor rejects non-positive dimensions and radius and aspect ratio`() {
        assertFailsWith<IllegalArgumentException> { ComposeLattice(widthPx = 0, heightPx = 600) }
        assertFailsWith<IllegalArgumentException> { ComposeLattice(widthPx = -1, heightPx = 600) }
        assertFailsWith<IllegalArgumentException> { ComposeLattice(widthPx = 800, heightPx = 0) }
        assertFailsWith<IllegalArgumentException> { ComposeLattice(widthPx = 800, heightPx = -1) }
        assertFailsWith<IllegalArgumentException> {
            ComposeLattice(widthPx = 800, heightPx = 600, voxelRadiusPx = 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            ComposeLattice(widthPx = 800, heightPx = 600, voxelRadiusPx = -1f)
        }
        assertFailsWith<IllegalArgumentException> {
            ComposeLattice(widthPx = 800, heightPx = 600, aspectRatio = 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            ComposeLattice(widthPx = 800, heightPx = 600, aspectRatio = -1f)
        }
        // Valid construction should not throw
        ComposeLattice(widthPx = 800, heightPx = 600)
    }

    @Test
    fun `voxel at origin with no rotation projects to canvas center`() {
        val lattice = ComposeLattice(widthPx = 400, heightPx = 400)
        val frame = voxelFrame(cells = listOf(voxelAt(x = 0f, y = 0f, z = 0f)))
        val canvas = lattice.project(frame)

        assertEquals(1, canvas.voxels.size)
        val v = canvas.voxels[0]
        // screenX = (0 + 1) * 0.5 * 400 = 200
        // screenY = (1 - (0 + 1) * 0.5) * 400 = 200
        assertNear(200f, v.screenX, "screenX")
        assertNear(200f, v.screenY, "screenY")
    }

    @Test
    fun `voxel at 1 0 0 with no rotation projects to right edge`() {
        val lattice = ComposeLattice(widthPx = 400, heightPx = 400)
        val frame = voxelFrame(cells = listOf(voxelAt(x = 1f, y = 0f, z = 0f)))
        val canvas = lattice.project(frame)

        // screenX = (1 + 1) * 0.5 * 400 = 400 → out of bounds [0, 400)
        // The voxel lands exactly at widthPx, which is out-of-bounds, so it should be dropped
        assertEquals(0, canvas.voxels.size, "voxel at x=1 lands at screenX=widthPx and should be dropped")
    }

    @Test
    fun `voxel at neg1 0 0 with no rotation projects to left edge`() {
        val lattice = ComposeLattice(widthPx = 400, heightPx = 400)
        val frame = voxelFrame(cells = listOf(voxelAt(x = -1f, y = 0f, z = 0f)))
        val canvas = lattice.project(frame)

        assertEquals(1, canvas.voxels.size)
        val v = canvas.voxels[0]
        // screenX = (-1 + 1) * 0.5 * 400 = 0
        // screenY = (1 - (0 + 1) * 0.5) * 400 = 200
        assertNear(0f, v.screenX, "screenX")
        assertNear(200f, v.screenY, "screenY")
    }

    @Test
    fun `voxel at 0 1 0 with no rotation projects to top edge Y-flip`() {
        val lattice = ComposeLattice(widthPx = 400, heightPx = 400)
        val frame = voxelFrame(cells = listOf(voxelAt(x = 0f, y = 1f, z = 0f)))
        val canvas = lattice.project(frame)

        // screenX = (0 + 1) * 0.5 * 400 = 200
        // screenY = (1 - (1 + 1) * 0.5) * 400 = 0
        assertEquals(1, canvas.voxels.size)
        val v = canvas.voxels[0]
        assertNear(200f, v.screenX, "screenX")
        assertNear(0f, v.screenY, "screenY (top edge, Y-flip)")
    }

    @Test
    fun `zero-scale voxel is dropped`() {
        val lattice = ComposeLattice(widthPx = 400, heightPx = 400)
        val cell = VoxelCell(x = 0f, y = 0f, z = 0f, scale = 0f, red = 1f, green = 1f, blue = 1f)
        val canvas = lattice.project(voxelFrame(cells = listOf(cell)))

        assertEquals(0, canvas.voxels.size)
    }

    @Test
    fun `negative-scale voxel is dropped`() {
        val lattice = ComposeLattice(widthPx = 400, heightPx = 400)
        val cell = VoxelCell(x = 0f, y = 0f, z = 0f, scale = -0.1f, red = 1f, green = 1f, blue = 1f)
        val canvas = lattice.project(voxelFrame(cells = listOf(cell)))

        assertEquals(0, canvas.voxels.size)
    }

    @Test
    fun `scale maps to radiusPx and colors are forwarded unchanged`() {
        val lattice = ComposeLattice(widthPx = 400, heightPx = 400, voxelRadiusPx = 8f)
        val cell =
            VoxelCell(
                x = 0f,
                y = 0f,
                z = 0f,
                scale = 0.5f,
                red = 0.1f,
                green = 0.2f,
                blue = 0.3f,
                alpha = 0.7f,
            )
        val canvas = lattice.project(voxelFrame(cells = listOf(cell)))

        assertEquals(1, canvas.voxels.size)
        val v = canvas.voxels[0]
        assertNear(4f, v.radiusPx, "radiusPx = voxelRadiusPx * scale = 8 * 0.5")
        assertNear(0.1f, v.red, "red")
        assertNear(0.2f, v.green, "green")
        assertNear(0.3f, v.blue, "blue")
        assertNear(0.7f, v.alpha, "alpha")
    }

    @Test
    fun `null alpha defaults to 1f`() {
        val lattice = ComposeLattice(widthPx = 400, heightPx = 400)
        val cell = VoxelCell(x = 0f, y = 0f, z = 0f, scale = 1f, red = 1f, green = 1f, blue = 1f, alpha = null)
        val canvas = lattice.project(voxelFrame(cells = listOf(cell)))

        assertEquals(1, canvas.voxels.size)
        assertNear(1.0f, canvas.voxels[0].alpha, "null alpha should default to 1.0")
    }

    @Test
    fun `voxel at 1 0 0 with orbRotationY pi over 2 projects near canvas center with negative z`() {
        val lattice = ComposeLattice(widthPx = 400, heightPx = 400)
        val ambient = DEFAULT_AMBIENT.copy(orbRotationY = (PI / 2).toFloat())
        val frame = voxelFrame(cells = listOf(voxelAt(x = 1f, y = 0f, z = 0f)), ambient = ambient)
        val canvas = lattice.project(frame)

        // After Y rotation of π/2: (1,0,0) → (0,0,-1)
        // screenX = (0 + 1) * 0.5 * 400 = 200 (center)
        // screenY = (1 - (0 + 1) * 0.5) * 400 = 200 (center)
        // rz = -1 (negative z, rotated away from camera)
        assertEquals(1, canvas.voxels.size)
        val v = canvas.voxels[0]
        assertNear(200f, v.screenX, "screenX near center", tolerance = 1f)
        assertNear(200f, v.screenY, "screenY near center", tolerance = 1f)
        assertTrue(v.z < 0f, "z should be negative after rotation away from camera, got ${v.z}")
    }

    @Test
    fun `identity rotation with all zero angles produces same output as unrotated projection`() {
        val lattice = ComposeLattice(widthPx = 400, heightPx = 400)
        val cells =
            listOf(
                voxelAt(x = 0.5f, y = 0.5f, z = 0.5f),
                voxelAt(x = -0.3f, y = 0.2f, z = -0.1f),
            )
        val frameNoRotation = voxelFrame(cells = cells, ambient = DEFAULT_AMBIENT)
        val frameZeroAngles =
            voxelFrame(
                cells = cells,
                ambient = DEFAULT_AMBIENT.copy(orbRotationX = 0f, orbRotationY = 0f, orbRotationZ = 0f),
            )

        val a = lattice.project(frameNoRotation)
        val b = lattice.project(frameZeroAngles)

        assertEquals(a.voxels, b.voxels)
    }

    @Test
    fun `voxel projected outside canvas bounds is dropped`() {
        val lattice = ComposeLattice(widthPx = 100, heightPx = 100)
        // x = 1 maps to screenX = 100 (out of bounds for [0, 100))
        val cell = voxelAt(x = 1f, y = 0f, z = 0f)
        val canvas = lattice.project(voxelFrame(cells = listOf(cell)))

        assertEquals(0, canvas.voxels.size)
    }

    @Test
    fun `ambient and glyph state pass through unchanged`() {
        val ambient =
            VoxelAmbient(
                glowRed = 0.1f,
                glowGreen = 0.2f,
                glowBlue = 0.3f,
                glowIntensity = 0.4f,
                orbRotationX = 0f,
                orbRotationY = 0f,
                orbRotationZ = 0f,
            )
        val glyph =
            VoxelGlyphState(
                glyphName = "CHECK",
                progress = 0.4f,
                red = 0.9f,
                green = 0.8f,
                blue = 0.7f,
            )
        val frame = voxelFrame(tick = 42L, cells = emptyList(), ambient = ambient, glyph = glyph)
        val canvas = ComposeLattice(widthPx = 400, heightPx = 400).project(frame)

        assertEquals(ambient, canvas.ambient)
        assertEquals(glyph, canvas.glyph)
        assertEquals(42L, canvas.tick)
        assertEquals(400, canvas.width)
        assertEquals(400, canvas.height)
    }

    @Test
    fun `projection is deterministic for identical inputs`() {
        val frame = settledFrame(AtmospherePresets.IDLE)
        val lattice = ComposeLattice(widthPx = 400, heightPx = 400)

        val a = lattice.project(frame)
        val b = lattice.project(frame)

        assertEquals(a.voxels, b.voxels)
        assertEquals(a.ambient, b.ambient)
    }

    @Test
    fun `IDLE atmosphere snapshot at 400x400 matches baseline voxel count and centroid`() {
        assertSnapshot(AtmospherePresets.IDLE, IDLE_BASELINE)
    }

    @Test
    fun `LISTENING atmosphere snapshot at 400x400 matches baseline voxel count and centroid`() {
        assertSnapshot(AtmospherePresets.LISTENING, LISTENING_BASELINE)
    }

    @Test
    fun `THINKING atmosphere snapshot at 400x400 matches baseline voxel count and centroid`() {
        assertSnapshot(AtmospherePresets.THINKING, THINKING_BASELINE)
    }

    @Test
    fun `UNCERTAIN atmosphere snapshot at 400x400 matches baseline voxel count and centroid`() {
        assertSnapshot(AtmospherePresets.UNCERTAIN, UNCERTAIN_BASELINE)
    }

    @Test
    fun `READY atmosphere snapshot at 400x400 matches baseline voxel count and centroid`() {
        assertSnapshot(AtmospherePresets.READY, READY_BASELINE)
    }

    private fun assertSnapshot(
        atmosphere: AtmosphereState,
        baseline: SnapshotBaseline,
    ) {
        val frame = settledFrame(atmosphere)
        val canvas = ComposeLattice(widthPx = 400, heightPx = 400).project(frame)
        val count = canvas.voxels.size
        val centroidX = if (count > 0) canvas.voxels.sumOf { it.screenX.toDouble() }.toFloat() / count else 0f
        val centroidY = if (count > 0) canvas.voxels.sumOf { it.screenY.toDouble() }.toFloat() / count else 0f

        assertEquals(
            baseline.voxelCount,
            count,
            "voxel count mismatch for ${atmosphere::class.simpleName}",
        )
        assertNear(baseline.centroidX, centroidX, "centroidX", tolerance = 1f)
        assertNear(baseline.centroidY, centroidY, "centroidY", tolerance = 1f)
    }

    private fun settledFrame(atmosphere: AtmosphereState): VoxelFrame {
        val builder = VoxelFrameBuilder(initialResolution = atmosphere.resolution)
        val snapshot =
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
                atmosphereTransition = null,
            )
        var frame = builder.build(snapshot, dt = 0f)
        repeat(SETTLE_FRAMES) {
            frame = builder.build(snapshot, dt = SETTLE_DT)
        }
        return frame
    }

    private fun voxelFrame(
        tick: Long = 0L,
        cells: List<VoxelCell>,
        ambient: VoxelAmbient = DEFAULT_AMBIENT,
        glyph: VoxelGlyphState? = null,
    ): VoxelFrame =
        VoxelFrame(
            tick = tick,
            timestampEpochMillis = 0L,
            resolution = 10,
            cells = cells,
            ambient = ambient,
            glyph = glyph,
        )

    private fun voxelAt(
        x: Float,
        y: Float,
        z: Float,
    ): VoxelCell =
        VoxelCell(
            x = x,
            y = y,
            z = z,
            scale = 1f,
            red = 1f,
            green = 1f,
            blue = 1f,
        )

    private fun assertNear(
        expected: Float,
        actual: Float,
        label: String,
        tolerance: Float = 0.01f,
    ) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "$label: expected $expected ± $tolerance, got $actual",
        )
    }

    private data class SnapshotBaseline(val voxelCount: Int, val centroidX: Float, val centroidY: Float)

    companion object {
        private val DEFAULT_AMBIENT =
            VoxelAmbient(
                glowRed = 0f,
                glowGreen = 0f,
                glowBlue = 0f,
                glowIntensity = 0f,
                orbRotationX = 0f,
                orbRotationY = 0f,
                orbRotationZ = 0f,
            )

        private const val SETTLE_FRAMES: Int = 60
        private const val SETTLE_DT: Float = 0.05f

        // Baselines committed after first run. Centroid tolerance: ±1px.
        private val IDLE_BASELINE = SnapshotBaseline(voxelCount = 4638, centroidX = 199.95895f, centroidY = 199.7617f)
        private val LISTENING_BASELINE =
            SnapshotBaseline(voxelCount = 4610, centroidX = 201.17964f, centroidY = 200.50601f)
        private val THINKING_BASELINE =
            SnapshotBaseline(voxelCount = 4568, centroidX = 202.3864f, centroidY = 200.70616f)
        private val UNCERTAIN_BASELINE =
            SnapshotBaseline(voxelCount = 4597, centroidX = 199.00285f, centroidY = 199.86017f)
        private val READY_BASELINE = SnapshotBaseline(voxelCount = 4614, centroidX = 201.32152f, centroidY = 199.20158f)
    }
}
