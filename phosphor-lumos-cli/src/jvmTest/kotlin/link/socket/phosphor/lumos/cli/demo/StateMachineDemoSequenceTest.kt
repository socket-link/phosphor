package link.socket.phosphor.lumos.cli.demo

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.LumosForm
import link.socket.phosphor.lumos.LumosStep
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.AtmosphereState
import link.socket.phosphor.trace.AtmosphereCue
import link.socket.phosphor.trace.CaptureConfig
import link.socket.phosphor.trace.CaptureParams
import link.socket.phosphor.trace.FormCue
import link.socket.phosphor.trace.Oscilloscope
import link.socket.phosphor.trace.TraceSegment
import link.socket.phosphor.trace.TraceStateMachine
import link.socket.phosphor.trace.VoxelTrace
import link.socket.phosphor.trace.frameLayout

/**
 * PHO-65 Task D: `demo-sequence.json` is the demo's script, as data. It lays out to the frames
 * the hand-written `Beat` list laid out to before it, captures to the same frames the cue path
 * captured, and every step name resolves through [TraceStateMachine].
 */
class StateMachineDemoSequenceTest {
    @Test
    fun `the bundled sequence reads and lays out to the frames the Beat script had`() {
        val sequence = demoSequence()
        val layout = sequence.frameLayout(FPS)

        assertEquals(BEATS.map(Beat::name), sequence.steps.map(LumosStep::name))
        assertEquals(BEATS.map(Beat::frames), layout.segments.map { it!!.frameCount })
        assertEquals(BEATS.map(Beat::loop), sequence.steps.map(LumosStep::loop))
        assertEquals(BEATS.sumOf(Beat::frames), layout.frameCount)
    }

    @Test
    fun `the sequence captures to the frames the cue script captured`() {
        val fromCues = captureBeats().getOrThrow()

        assertEquals(fromCues.segments, raw.segments)
        // Columns rather than frames: 580 frames of 4,801 cells as objects is more heap than the test runner has.
        assertContentEquals(fromCues.ticks, raw.ticks)
        assertContentEquals(fromCues.cellCounts, raw.cellCounts)
        assertEquals(fromCues.ambient, raw.ambient)
        assertEquals(fromCues.glyphs, raw.glyphs)
        assertContentEquals(fromCues.cellX, raw.cellX)
        assertContentEquals(fromCues.cellY, raw.cellY)
        assertContentEquals(fromCues.cellZ, raw.cellZ)
        assertContentEquals(fromCues.cellScale, raw.cellScale)
        assertContentEquals(fromCues.cellRed, raw.cellRed)
        assertContentEquals(fromCues.cellGreen, raw.cellGreen)
        assertContentEquals(fromCues.cellBlue, raw.cellBlue)
        assertContentEquals(fromCues.cellAlpha, raw.cellAlpha)
    }

    @Test
    fun `every step name resolves through the state machine`() {
        val machine = TraceStateMachine(raw, initialState = "idle")

        assertEquals(demoSequence().steps.map(LumosStep::name), machine.stateNames)
        raw.segments.filter(TraceSegment::loop).forEach { loop ->
            assertTrue(machine.requestState(loop.name).isSuccess, "${loop.name} should resolve")
        }
    }

    /** The old script, kept here as the reference the data is held to. */
    private data class Beat(
        val name: String,
        val frames: Int,
        val loop: Boolean,
        val cue: AtmosphereState? = null,
        val form: LumosForm? = null,
    )

    private fun captureBeats(): Result<VoxelTrace> {
        val totalFrames = BEATS.sumOf(Beat::frames)
        val segments = ArrayList<TraceSegment>(BEATS.size)
        val cues = ArrayList<AtmosphereCue>()
        val formCues = ArrayList<FormCue>()
        var cursor = 0
        BEATS.forEach { beat ->
            segments += TraceSegment(beat.name, cursor, cursor + beat.frames - 1, beat.loop)
            beat.cue?.let { cues += AtmosphereCue(atFrame = cursor, atmosphere = it) }
            beat.form?.let { formCues += FormCue(atFrame = cursor, form = it) }
            cursor += beat.frames
        }
        return Oscilloscope.capture(
            CaptureConfig(
                params = CaptureParams(atmosphere = AtmospherePresets.IDLE),
                seed = 0x10_5E55L,
                durationMs = (totalFrames * 1_000L + FPS - 1) / FPS,
                fps = FPS,
                segments = segments,
                cues = cues,
                formCues = formCues,
            ),
        )
    }

    private companion object {
        const val FPS = 24
        const val MORPH_FRAMES = 58
        val SPHERE: LumosForm = Forms.Lattice(AtmospherePresets.IDLE.resolution)

        val BEATS =
            listOf(
                Beat("idle", frames = 48, loop = true),
                Beat("idle→thinking", frames = 20, loop = false, cue = AtmospherePresets.THINKING),
                Beat("thinking", frames = 48, loop = true),
                Beat("thinking→idle", frames = 24, loop = false, cue = AtmospherePresets.IDLE),
                Beat("idle→uncertain", frames = 34, loop = false, cue = AtmospherePresets.UNCERTAIN),
                Beat("uncertain", frames = 48, loop = true),
                Beat("uncertain→idle", frames = 28, loop = false, cue = AtmospherePresets.IDLE),
                Beat("idle→fibonacci", frames = MORPH_FRAMES, loop = false, form = Forms.Fibonacci),
                Beat("fibonacci", frames = 48, loop = true),
                Beat("fibonacci→idle", frames = MORPH_FRAMES, loop = false, form = SPHERE),
                Beat("idle→cube", frames = MORPH_FRAMES, loop = false, form = Forms.Cube),
                Beat("cube", frames = 48, loop = true),
                Beat("cube→idle", frames = MORPH_FRAMES, loop = false, form = SPHERE),
            )

        // Unblended, so the comparison is of what the two scripts made the simulation do.
        val raw: VoxelTrace by lazy { captureDemoTrace(seamBlendFrames = 0).getOrThrow() }
    }
}
