package link.socket.phosphor.lumos

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.field.Choreography
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.lumos.look.ViewPoint
import link.socket.phosphor.lumos.look.ViewRotation
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneSnapshot
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.signal.CognitivePhase

/**
 * PHO-72 Tasks B and C in the builder: a [ViewForm] is handed the rotation of
 * the frame being built, and a [FramedForm.rigid] form holds the atmosphere's
 * skin off, blended by the morph's progress, while `rigid = false` changes
 * nothing.
 */
class VoxelFrameBuilderViewFormTest {
    // Every skin term on, and the orb turning about both axes.
    private val skinned =
        AtmospherePresets.IDLE.copy(
            resolution = RESOLUTION,
            noise = 0.35f,
            surfaceBump = 0.12f,
            pulseAmplitude = 0.08f,
            ySquash = 0.85f,
            rotationX = 0.4f,
            rotationY = 0.9f,
        )

    // The same atmosphere with the skin off: where a rigid form at rest is drawn.
    private val skinless = skinned.copy(noise = 0f, surfaceBump = 0f, pulseAmplitude = 0f, ySquash = 1f)

    private val viewport = OrbViewport(centerXPx = 100f, centerYPx = 100f, radiusPx = 100f)
    private val button = RectPx(left = 40f, top = 80f, width = 120f, height = 40f, cornerRadius = 10f)

    @Test
    fun `a view form is handed the rotation the frame is projected with`() {
        val form = RecordingViewForm()
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.form = form
        repeat(6) {
            val frame = builder.build(snapshot(skinned, it / 10f), dt = 0.1f)
            assertProjectedWith(frame.ambient, form)
        }
    }

    @Test
    fun `under a look that owns the yaw a view form is handed that yaw from the first frame`() {
        val form = RecordingViewForm()
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.form = form
        builder.look = Look(LumosLook.RETRYING)
        repeat(6) {
            val frame = builder.build(snapshot(skinned, it / 10f), dt = 0.1f)
            assertProjectedWith(frame.ambient, form)
        }
        // Back to a look without a yaw of its own: the atmosphere's again.
        builder.look = Look(LumosLook.WORKING)
        val frame = builder.build(snapshot(skinned, 1f), dt = 0.1f)
        assertProjectedWith(frame.ambient, form)
    }

    @Test
    fun `across a blend between looks a view form is handed the yaw the frame turns between them`() {
        val form = RecordingViewForm()
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.form = form
        builder.look = Look(LumosLook.WORKING)
        builder.build(snapshot(skinned, 0f), dt = 0.1f)
        builder.lookTo(Look(LumosLook.RETRYING), durationSeconds = 0.6f)
        var frames = 0
        while (builder.isLookBlending) {
            val frame = builder.build(snapshot(skinned, 0.1f + frames / 20f), dt = 0.05f)
            assertProjectedWith(frame.ambient, form)
            frames++
        }
        assertTrue(frames > 5, "the blend ran over several frames, $frames")
        builder.lookTo(Look(LumosLook.WORKING), durationSeconds = 0.6f)
        repeat(15) {
            val frame = builder.build(snapshot(skinned, 2f + it / 20f), dt = 0.05f)
            assertProjectedWith(frame.ambient, form)
        }
    }

    @Test
    fun `a rigid view form is drawn on its layout at every rotation whatever the skin`() {
        val form = PixelForm.roundedRect(button, viewport)
        val laidOut = form.sample(COUNT, LumosLook.BASE, 0f).positions.toList()
        val builder = VoxelFrameBuilder(initialResolution = RESOLUTION)
        builder.form = form.counted(COUNT)
        val rotation = ViewRotation()
        val view = ViewPoint()
        repeat(20) {
            val frame = builder.build(snapshot(skinned, it * 0.37f), dt = 0.37f)
            assertEquals(COUNT, frame.cells.size)
            rotation.set(frame.ambient.orbRotationX, frame.ambient.orbRotationY, 0f, 0f)
            for (i in 0 until COUNT) {
                val cell = frame.cells[i]
                rotation.place(view, cell.x, cell.y, cell.z, 0f, 0f, 1f)
                assertEquals(laidOut[i].x, view.x, 1e-5f, "x of cell $i, frame $it")
                assertEquals(laidOut[i].y, view.y, 1e-5f, "y of cell $i, frame $it")
                assertEquals(0f, view.z, 1e-5f, "z of cell $i, frame $it")
            }
        }
    }

    @Test
    fun `a rigid form at rest is drawn as the skinless atmosphere draws it`() {
        val rigid = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val plain = VoxelFrameBuilder(initialResolution = RESOLUTION)
        rigid.form = Wrapped(Forms.Fibonacci, rigid = true)
        plain.form = Wrapped(Forms.Fibonacci, rigid = false)
        repeat(5) {
            val held = rigid.build(snapshot(skinned, it / 10f), dt = 0.1f)
            val bare = plain.build(snapshot(skinless, it / 10f), dt = 0.1f)
            assertEquals(bare.cells, held.cells, "frame $it")
        }
    }

    @Test
    fun `rigid false is byte-identical to the form it wraps at rest and through a morph`() {
        val reference = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val wrapped = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val frames = mutableListOf<Pair<VoxelFrame, VoxelFrame>>()
        repeat(3) {
            frames += reference.build(snapshot(skinned, it / 10f), dt = 0.1f) to
                wrapped.build(snapshot(skinned, it / 10f), dt = 0.1f)
        }
        reference.morphTo(Forms.Cube, durationSeconds = 0.5f)
        wrapped.morphTo(Wrapped(Forms.Cube, rigid = false), durationSeconds = 0.5f)
        repeat(12) {
            frames += reference.build(snapshot(skinned, 0.3f + it / 10f), dt = 0.1f) to
                wrapped.build(snapshot(skinned, 0.3f + it / 10f), dt = 0.1f)
        }
        reference.morphTo(Forms.Fibonacci, count = 120)
        wrapped.morphTo(Wrapped(Forms.Fibonacci, rigid = false), count = 120)
        repeat(30) {
            frames += reference.build(snapshot(skinned, 1.5f + it / 10f), dt = 0.1f) to
                wrapped.build(snapshot(skinned, 1.5f + it / 10f), dt = 0.1f)
        }
        frames.forEachIndexed { i, (expected, actual) -> assertEquals(expected, actual, "frame $i") }
    }

    @Test
    fun `the skin fades out as a rigid body arrives and back in as it leaves`() {
        // Only the jitter, which neither time nor a cell's travel changes, so the gap between the
        // two builders is the blend alone: each cell's jitter times the rigidity.
        val steady = skinned.copy(surfaceBump = 0f, pulseAmplitude = 0f, ySquash = 1f, rotationX = 0f, rotationY = 0f)
        val rigid = VoxelFrameBuilder(initialResolution = RESOLUTION)
        val plain = VoxelFrameBuilder(initialResolution = RESOLUTION)
        var t = 0f

        fun step(): Float {
            t += 1f / 60f
            val a = rigid.build(snapshot(steady, t), dt = 1f / 60f)
            val b = plain.build(snapshot(steady, t), dt = 1f / 60f)
            return gap(a, b)
        }
        assertEquals(0f, step())

        rigid.morphTo(Wrapped(Forms.Fibonacci, rigid = true), choreography = Choreography.DIRECT)
        plain.morphTo(Wrapped(Forms.Fibonacci, rigid = false), choreography = Choreography.DIRECT)
        val arriving = mutableListOf<Float>()
        while (rigid.isMorphing) arriving += step()
        val atRest = step()
        assertTrue(atRest > 0.1f, "the skin is visibly held off at rest, gap $atRest")
        assertTrue(
            arriving.first() < 0.05f * atRest,
            "one frame in the skin is still on: ${arriving.first()} vs $atRest",
        )
        for (i in 1 until arriving.size) {
            assertTrue(arriving[i] >= arriving[i - 1] - 1e-6f, "the hold only grows: frame $i")
        }

        rigid.morphTo(Forms.Lattice(RESOLUTION), choreography = Choreography.DIRECT)
        plain.morphTo(Forms.Lattice(RESOLUTION), choreography = Choreography.DIRECT)
        val leaving = step()
        assertTrue(leaving > 0.5f * atRest, "one frame out the skin is still held off: $leaving vs $atRest")
        while (rigid.isMorphing) step()
        assertEquals(0f, step(), "back on the lattice the skin is the atmosphere's again")
    }

    private fun assertProjectedWith(
        ambient: VoxelAmbient,
        form: RecordingViewForm,
    ) {
        assertEquals(cos(ambient.orbRotationX.toDouble()).toFloat(), form.cosX, "cos X")
        assertEquals(sin(ambient.orbRotationX.toDouble()).toFloat(), form.sinX, "sin X")
        assertEquals(cos(ambient.orbRotationY.toDouble()).toFloat(), form.cosY, "cos Y")
        assertEquals(sin(ambient.orbRotationY.toDouble()).toFloat(), form.sinY, "sin Y")
    }

    /** Largest coordinate difference between two frames' cells, index for index. */
    private fun gap(
        a: VoxelFrame,
        b: VoxelFrame,
    ): Float {
        assertEquals(a.cells.size, b.cells.size)
        var worst = 0f
        for (i in a.cells.indices) {
            val p = a.cells[i]
            val q = b.cells[i]
            worst = max(worst, max(abs(p.x - q.x), max(abs(p.y - q.y), abs(p.z - q.z))))
        }
        return worst
    }

    /** A view form that remembers the rotation it was last handed, over the Fibonacci sphere. */
    private class RecordingViewForm : ViewForm {
        var cosX = Float.NaN
        var sinX = Float.NaN
        var cosY = Float.NaN
        var sinY = Float.NaN

        override fun sample(
            count: Int,
            look: LumosLook,
            time: Float,
            view: ViewRotation,
        ): FormSample {
            cosX = view.cosX
            sinX = view.sinX
            cosY = view.cosY
            sinY = view.sinY
            return Forms.Fibonacci.sample(count, look, time)
        }
    }

    /** [inner]'s positions and framing, with [rigid] said outright. */
    private class Wrapped(
        private val inner: FramedForm,
        override val rigid: Boolean,
    ) : FramedForm {
        override val scale: Float get() = inner.scale

        override fun cellRadius(count: Int): Float = inner.cellRadius(count)

        override fun sample(
            count: Int,
            look: LumosLook,
            time: Float,
        ): FormSample = inner.sample(count, look, time)
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
        const val RESOLUTION = 4
        const val COUNT = 150
    }
}
