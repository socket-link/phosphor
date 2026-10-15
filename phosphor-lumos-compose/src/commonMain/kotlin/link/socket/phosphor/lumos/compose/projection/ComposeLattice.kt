package link.socket.phosphor.lumos.compose.projection

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import link.socket.phosphor.lumos.VoxelAmbient
import link.socket.phosphor.lumos.VoxelFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame.CanvasVoxel
import link.socket.phosphor.lumos.probe.FramePhase
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.lumos.probe.measure

/**
 * Orthographic projection of a [VoxelFrame] to a [LumosCanvasFrame] for Compose Canvas rendering.
 *
 * Mirrors CliLattice with three structural differences:
 * 1. **No aspect compression** — pixel cells are 1:1, so [aspectRatio] defaults to 1.0.
 * 2. **No per-cell winner aggregation** — every visible voxel is emitted as its own [CanvasVoxel].
 *    The output list is sorted by z ascending (back to front, ties in lattice order), once per
 *    projected frame, through a reusable key buffer. `LumosCanvas` therefore never sorts.
 * 3. **Orb rotation applied here** — each voxel's `(x, y, z)` lattice position is rotated by
 *    Euler angles from the [VoxelAmbient.orbRotationX], [VoxelAmbient.orbRotationY], and
 *    [VoxelAmbient.orbRotationZ] properties before projection.
 *    **Rotation order: X first, then Y, then Z.**
 *
 * Input lattice positions are in `[-1, 1]` on all axes, following the same convention as
 * CliLattice. Y is flipped: `y = +1` maps to the top of the canvas (`screenY = 0`).
 *
 * A [tiltDegrees] other than 0 views the orb from above its equator: one more rotation, about
 * the screen's X axis, after the orb's own. It has to come last. Added to
 * [VoxelAmbient.orbRotationX] instead, it would be applied first, in the orb's own frame, and the
 * spin about Y would carry it round — the orb would nod forward, roll sideways and nod back once
 * a turn instead of being seen from above.
 *
 * @param widthPx Output canvas width in pixels; must be > 0.
 * @param heightPx Output canvas height in pixels; must be > 0.
 * @param voxelRadiusPx Base voxel half-size in pixels at `scale = 1.0`; must be > 0.
 * @param aspectRatio Pixel aspect ratio applied to the Y axis; defaults to 1.0 for square pixels.
 *  Must be > 0.
 * @param probe Per-phase timing sink. Defaults to [FrameProbe.Disabled], which
 *  adds no per-frame cost; pass a [link.socket.phosphor.lumos.probe.RingBufferFrameProbe]
 *  to capture PROJECT timings.
 * @param tiltDegrees How far above the orb's equator the viewer sits; positive tips the north pole
 *  (`y = +1`) toward the viewer. 0, the default, projects exactly as before tilt existed. The glyph
 *  is carved on the face an untilted viewer sees, so a tilted view shows the carve that far below
 *  the centre, where `LumosCanvas` draws its overlay. Must be finite.
 */
class ComposeLattice(
    val widthPx: Int,
    val heightPx: Int,
    val voxelRadiusPx: Float = 4f,
    val aspectRatio: Float = 1.0f,
    private val probe: FrameProbe = FrameProbe.Disabled,
    val tiltDegrees: Float = 0f,
) {
    init {
        require(widthPx > 0) { "widthPx must be > 0, got $widthPx" }
        require(heightPx > 0) { "heightPx must be > 0, got $heightPx" }
        require(voxelRadiusPx > 0f) { "voxelRadiusPx must be > 0, got $voxelRadiusPx" }
        require(aspectRatio > 0f) { "aspectRatio must be > 0, got $aspectRatio" }
        require(tiltDegrees.isFinite()) { "tiltDegrees must be finite, got $tiltDegrees" }
    }

    private val tilted: Boolean = tiltDegrees != 0f
    private val cosTilt: Float = cos(tiltDegrees * DEGREES_TO_RADIANS).toFloat()
    private val sinTilt: Float = sin(tiltDegrees * DEGREES_TO_RADIANS).toFloat()

    /**
     * Project [frame] to a [LumosCanvasFrame].
     *
     * Algorithm:
     * 1. For each voxel with `scale > 0`, apply Euler XYZ rotation (X → Y → Z) using
     *    angles from [frame]'s ambient state.
     * 2. Project the rotated position orthographically:
     *    `screenX = (rx + 1) * 0.5 * widthPx`
     *    `screenY = (1 - (ry + 1) * 0.5) * heightPx * (1 / aspectRatio)`
     * 3. Skip voxels whose projected center falls outside `[0, widthPx) × [0, heightPx)`.
     * 4. Emit one [CanvasVoxel] per surviving voxel with `radiusPx = voxelRadiusPx * scale`,
     *    sRGB color forwarded directly, and rotated `z` preserved for depth compositing.
     *
     * Ambient and glyph state pass through unchanged.
     */
    fun project(frame: VoxelFrame): LumosCanvasFrame = probe.measure(FramePhase.PROJECT) { projectFrame(frame) }

    // Reusable sort keys: (order-preserving z bits << 32) | index into `scratch`. Grown, never shrunk.
    private var keys = LongArray(0)
    private var scratch = arrayOfNulls<CanvasVoxel>(0)

    private fun projectFrame(frame: VoxelFrame): LumosCanvasFrame {
        val cells = frame.cells
        val ambient = frame.ambient

        val cosX = cos(ambient.orbRotationX.toDouble()).toFloat()
        val sinX = sin(ambient.orbRotationX.toDouble()).toFloat()
        val cosY = cos(ambient.orbRotationY.toDouble()).toFloat()
        val sinY = sin(ambient.orbRotationY.toDouble()).toFloat()
        val cosZ = cos(ambient.orbRotationZ.toDouble()).toFloat()
        val sinZ = sin(ambient.orbRotationZ.toDouble()).toFloat()

        val invAspect = 1f / aspectRatio
        if (keys.size < cells.size) {
            keys = LongArray(cells.size)
            scratch = arrayOfNulls(cells.size)
        }
        var count = 0

        for (cell in cells) {
            if (cell.scale <= 0f) continue

            // Rotate around X axis
            val x1 = cell.x
            val y1 = cell.y * cosX - cell.z * sinX
            val z1 = cell.y * sinX + cell.z * cosX

            // Rotate around Y axis
            val x2 = x1 * cosY + z1 * sinY
            val y2 = y1
            val z2 = -x1 * sinY + z1 * cosY

            // Rotate around Z axis
            val rx = x2 * cosZ - y2 * sinZ
            var ry = x2 * sinZ + y2 * cosZ
            var rz = z2

            // View from above: tilt about the screen's X axis, after the orb's own rotation.
            if (tilted) {
                val ty = ry * cosTilt - rz * sinTilt
                rz = ry * sinTilt + rz * cosTilt
                ry = ty
            }

            val screenX = (rx + 1f) * 0.5f * widthPx
            val screenY = (1f - (ry + 1f) * 0.5f) * heightPx * invAspect

            if (screenX < 0f || screenX >= widthPx || screenY < 0f || screenY >= heightPx) continue

            scratch[count] =
                CanvasVoxel(
                    screenX = screenX,
                    screenY = screenY,
                    radiusPx = voxelRadiusPx * cell.scale,
                    red = cell.red,
                    green = cell.green,
                    blue = cell.blue,
                    alpha = cell.alpha ?: 1.0f,
                    z = rz,
                )
            // Flip the magnitude bits of negatives so signed-int order matches float order.
            val bits = rz.toRawBits()
            val ordered = bits xor ((bits shr 31) and 0x7fffffff)
            keys[count] = (ordered.toLong() shl 32) or count.toLong()
            count++
        }

        keys.sort(0, count)
        val out = ArrayList<CanvasVoxel>(count)
        for (i in 0 until count) {
            out += scratch[keys[i].toInt()]!!
        }
        scratch.fill(null, 0, count)

        return LumosCanvasFrame(
            width = widthPx,
            height = heightPx,
            voxels = out,
            ambient = ambient,
            glyph = frame.glyph,
            tick = frame.tick,
        )
    }

    private companion object {
        const val DEGREES_TO_RADIANS: Double = PI / 180.0
    }
}
