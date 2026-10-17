package link.socket.phosphor.lumos.projection

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.lumos.FramePhases
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.OrbViewport
import link.socket.phosphor.lumos.PixelForm
import link.socket.phosphor.lumos.RectPx
import link.socket.phosphor.lumos.VoxelFrameBuilder
import link.socket.phosphor.lumos.frame.LumosCanvasFrame
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase

/**
 * PHO-72 Task B's acceptance: a [PixelForm.roundedRect] target projected
 * through [PixelLattice] at any orb rotation lands within half a pixel of
 * the pixel it was laid out at, with every skin term on.
 *
 * Without a viewport `PixelLattice` fits `[-1, 1]` to the whole canvas, so
 * on a square canvas the orb's viewport is the canvas's centre and half its
 * side; with one (PHO-73) the orb sits where the viewport says on a root-sized
 * canvas. Every case is untilted: a tilt is applied after the frame's rotation
 * and is not carried by the frame, so no form can undo it.
 */
class PixelFormProjectionTest {
    private val lattice = PixelLattice(widthPx = SIZE, heightPx = SIZE)
    private val viewport = OrbViewport(centerXPx = SIZE / 2f, centerYPx = SIZE / 2f, radiusPx = SIZE / 2f)
    private val rect = RectPx(left = 110f, top = 170f, width = 180f, height = 60f, cornerRadius = 16f)

    private val skinned =
        AtmospherePresets.IDLE.copy(
            resolution = RESOLUTION,
            noise = 0.35f,
            surfaceBump = 0.12f,
            pulseAmplitude = 0.08f,
            ySquash = 0.85f,
            rotationX = 0.5f,
            rotationY = 1.1f,
        )

    @Test
    fun `every cell lands within half a pixel of its target at any rotation`() {
        val form = PixelForm.roundedRect(rect, viewport)
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.form = form
        val targets = targetsOf(form, builder.currentCount)
        val angles = listOf(0f, 0.4f, 1.3f, 2.2f, 3.0f, 3.9f, 4.7f, 5.6f, 6.2f)
        for (rx in angles) {
            for (ry in angles) {
                builder.phases =
                    FramePhases(pulsePhase = 1.1f, patternPhase = 0.7f, orbRotationX = rx, orbRotationY = ry)
                val frame = builder.build(snapshot(skinned, 0f), dt = 0f)
                assertLands(targets, lattice.project(frame), "at ($rx, $ry)")
            }
        }
    }

    @Test
    fun `a morph from the orb at a count of its own settles onto the pixels while the orb turns`() {
        val form = PixelForm.roundedRect(rect, viewport)
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        var t = 0f
        builder.build(snapshot(skinned, t), dt = 0f)
        builder.morphTo(form, count = COUNT, durationSeconds = 1.2f)
        while (builder.isMorphing) {
            t += 1f / 30f
            builder.build(snapshot(skinned, t), dt = 1f / 30f)
        }
        val targets = targetsOf(form, COUNT)
        repeat(10) {
            t += 0.25f
            val frame = builder.build(snapshot(skinned, t), dt = 0.25f)
            assertLands(targets, lattice.project(frame), "frame $it after settling")
        }
    }

    @Test
    fun `on a root canvas with the orb at a viewport a target beside the orb lands on its pixels`() {
        // A root wider than the orb, the orb at its left, the button to its right and outside the sphere.
        val orb = OrbViewport(centerXPx = 160f, centerYPx = 200f, radiusPx = 120f)
        val root = PixelLattice(widthPx = 640, heightPx = 400, viewport = orb)
        val beside = RectPx(left = 340f, top = 160f, width = 220f, height = 72f, cornerRadius = 20f)
        val form = PixelForm.roundedRect(beside, orb)
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        var t = 0f
        builder.build(snapshot(skinned, t), dt = 0f)
        builder.morphTo(form, count = COUNT, durationSeconds = 1.2f)
        while (builder.isMorphing) {
            t += 1f / 30f
            builder.build(snapshot(skinned, t), dt = 1f / 30f)
        }
        val targets =
            PixelForm.roundedRect(beside, orb).sample(COUNT, LumosLook.BASE, 0f).positions.map {
                orb.pixelX(it.x) to orb.pixelY(it.y)
            }
        repeat(10) {
            t += 0.25f
            val frame = builder.build(snapshot(skinned, t), dt = 0.25f)
            assertLands(targets, root.project(frame), "frame $it after settling")
        }
    }

    /** [form]'s cells as laid out, in root pixels. */
    private fun targetsOf(
        form: PixelForm.RoundedRect,
        count: Int,
    ): List<Pair<Float, Float>> {
        val laidOut = PixelForm.roundedRect(form.rect, form.viewport).sample(count, LumosLook.BASE, 0f)
        return laidOut.positions.map { viewport.pixelX(it.x) to viewport.pixelY(it.y) }
    }

    /** Every target has a voxel within half a pixel, and every voxel a target: depth order scrambles indices. */
    private fun assertLands(
        targets: List<Pair<Float, Float>>,
        canvas: LumosCanvasFrame,
        label: String,
    ) {
        val voxels = canvas.voxels.map { it.screenX to it.screenY }
        assertEquals(targets.size, voxels.size, "$label: every cell is on the canvas")
        val voxelsByX = ByX(voxels)
        for ((i, target) in targets.withIndex()) {
            val miss = voxelsByX.nearestWithinHalfPixel(target)
            assertTrue(miss <= HALF_PIXEL, "$label: target $i at $target has no cell within half a pixel ($miss)")
        }
        val targetsByX = ByX(targets)
        for ((i, voxel) in voxels.withIndex()) {
            val miss = targetsByX.nearestWithinHalfPixel(voxel)
            assertTrue(miss <= HALF_PIXEL, "$label: voxel $i at $voxel has no target within half a pixel ($miss)")
        }
    }

    /**
     * Points sorted by x, so the nearest within half a pixel is a binary search and a short scan
     * rather than a pass over every point: the web targets run each test under a 2 s timeout.
     */
    private class ByX(points: List<Pair<Float, Float>>) {
        private val sorted = points.sortedBy { it.first }

        /** Distance to the nearest point no more than half a pixel away in x; infinity when there is none. */
        fun nearestWithinHalfPixel(point: Pair<Float, Float>): Float {
            var low = 0
            var high = sorted.size
            while (low < high) {
                val mid = (low + high) / 2
                if (sorted[mid].first < point.first - HALF_PIXEL) low = mid + 1 else high = mid
            }
            var best = Float.POSITIVE_INFINITY
            var i = low
            while (i < sorted.size && sorted[i].first <= point.first + HALF_PIXEL) {
                val dx = point.first - sorted[i].first
                val dy = point.second - sorted[i].second
                best = minOf(best, dx * dx + dy * dy)
                i++
            }
            return sqrt(best)
        }
    }

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

    private companion object {
        const val SIZE = 400
        const val RESOLUTION = 4
        const val COUNT = 300
        const val HALF_PIXEL = 0.5f
    }
}
