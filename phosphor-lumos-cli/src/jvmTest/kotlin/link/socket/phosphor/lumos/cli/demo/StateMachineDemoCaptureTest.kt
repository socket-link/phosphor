package link.socket.phosphor.lumos.cli.demo

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import link.socket.phosphor.trace.TraceSegment
import link.socket.phosphor.trace.VoxelTrace
import link.socket.phosphor.trace.seamErrors

/**
 * PHO-49's acceptance check on the demo's own script: every loop follows its
 * transition directly, with no settled hold, and still wraps without a ghost of
 * the state it came from.
 *
 * Both captures run at the demo's full resolution and frame rate, so this is
 * the clip the demo plays rather than a model of it.
 */
class StateMachineDemoCaptureTest {
    @Test
    fun `every loop wraps like one more frame`() {
        baked.seamErrors().forEach { seam ->
            println("${seam.segment.name}: seam reads ${seam.contentRatio}f frame steps once baked")
            assertTrue(seam.contentRatio < SEAM_CEILING, "${seam.segment.name} should wrap once baked, was $seam")
        }
    }

    @Test
    fun `no loop's tail leans toward the state it came from`() {
        baked.segments.filter(TraceSegment::loop).forEach { loop ->
            val halo = excessDrift(loop, VoxelTrace::halo)
            val tint = excessDrift(loop, VoxelTrace::tint)
            println("${loop.name}: window strays ${halo}f in halo and ${tint}f in colour beyond the loop's own range")
            assertTrue(halo <= HALO_TOLERANCE, "${loop.name}: halo drifts $halo beyond the loop's own range")
            assertTrue(tint <= TINT_TOLERANCE, "${loop.name}: colour drifts $tint beyond the loop's own range")
        }
    }

    @Test
    fun `fading toward the recorded frames instead would have ghosted the previous state`() {
        // What the blend did before PHO-49: the uncertain loop's tail faded toward
        // the last frames of idle→uncertain, still mid-transition. The halo blends
        // as a plain lerp, so that window can be rebuilt from the ambient channel
        // alone — and the measure that passes above has to see it.
        val uncertain = raw.segments.single { it.name == "uncertain" }
        val own = (uncertain.startFrame..uncertain.endFrame).map { raw.halo(it) }
        val ghosted =
            List(SEAM_BLEND_FRAMES) { step ->
                lerp(
                    from = raw.halo(uncertain.endFrame - SEAM_BLEND_FRAMES + 1 + step),
                    to = raw.halo(uncertain.startFrame - SEAM_BLEND_FRAMES + step),
                    t = (step + 1).toFloat() / SEAM_BLEND_FRAMES,
                )
            }

        val drift = excess(own, ghosted)

        println("uncertain: a recorded lead-in would have pulled the halo ${drift}f toward idle")
        assertTrue(
            drift > HALO_TOLERANCE,
            "the recorded lead-in should have pulled the halo toward idle, drift was $drift",
        )
    }

    /**
     * How far the blend window of [loop] in the baked capture strays from the
     * loop's mean in [channel], beyond the furthest the loop itself strays in the
     * raw capture — its own range, measured before anything was blended.
     */
    private fun excessDrift(
        loop: TraceSegment,
        channel: VoxelTrace.(Int) -> FloatArray,
    ): Float {
        val own = (loop.startFrame..loop.endFrame).map { raw.channel(it) }
        val window = (loop.endFrame - SEAM_BLEND_FRAMES + 1..loop.endFrame).map { baked.channel(it) }
        return excess(own, window)
    }

    private companion object {
        /** About 1 wraps like one more frame; SeamBlendCaptureTest holds the presets to the same ceiling. */
        const val SEAM_CEILING = 2f

        /** The halo is the atmosphere's colour alone, so a window that holds the loop's atmosphere holds it exactly. */
        const val HALO_TOLERANCE = 1e-5f

        /** Mean cell colour moves with the pattern phase; the lead-in sits at phases the loop never reaches. */
        const val TINT_TOLERANCE = 0.01f

        // Shared across test instances: each capture is 250 frames at resolution 10.
        val baked: VoxelTrace by lazy { captureDemoTrace().getOrThrow() }
        val raw: VoxelTrace by lazy { captureDemoTrace(seamBlendFrames = 0).getOrThrow() }
    }
}

/** The halo: the atmosphere's colour and glow, with nothing of the lattice in it. */
private fun VoxelTrace.halo(frame: Int): FloatArray =
    ambient[frame].let { floatArrayOf(it.glowRed, it.glowGreen, it.glowBlue, it.glowIntensity) }

/** The frame's mean cell colour. */
private fun VoxelTrace.tint(frame: Int): FloatArray {
    var start = 0
    repeat(frame) { start += cellCounts[it] }
    val count = cellCounts[frame]
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    for (cell in start until start + count) {
        red += cellRed[cell]
        green += cellGreen[cell]
        blue += cellBlue[cell]
    }
    return floatArrayOf((red / count).toFloat(), (green / count).toFloat(), (blue / count).toFloat())
}

/**
 * How far [window] strays from the centre of [own], beyond the furthest [own]
 * strays itself. At or below zero the window stays inside the loop's own range.
 */
private fun excess(
    own: List<FloatArray>,
    window: List<FloatArray>,
): Float {
    val centre = mean(own)
    return window.maxOf { distance(it, centre) } - own.maxOf { distance(it, centre) }
}

private fun mean(vectors: List<FloatArray>): FloatArray {
    val sum = DoubleArray(vectors.first().size)
    vectors.forEach { vector -> vector.forEachIndexed { index, value -> sum[index] += value } }
    return FloatArray(sum.size) { (sum[it] / vectors.size).toFloat() }
}

private fun distance(
    a: FloatArray,
    b: FloatArray,
): Float {
    var sum = 0.0
    for (index in a.indices) {
        val delta = (a[index] - b[index]).toDouble()
        sum += delta * delta
    }
    return sqrt(sum).toFloat()
}

private fun lerp(
    from: FloatArray,
    to: FloatArray,
    t: Float,
): FloatArray = FloatArray(from.size) { from[it] + (to[it] - from[it]) * t }
