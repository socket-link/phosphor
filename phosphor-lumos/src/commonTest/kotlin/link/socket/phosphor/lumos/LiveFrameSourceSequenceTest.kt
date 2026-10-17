package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import link.socket.phosphor.palette.AtmospherePresets

/**
 * PHO-65 Task B: a [LiveFrameSource] following a [LumosSequencePlayer] applies each step as the
 * sequence enters it — preset, form, look, glyph — and nothing a step names that the orb already
 * shows. With `clock = true` it advances the player after building, so frame `k` of a fixed-step
 * stream is the sequence at `k / fps`.
 */
class LiveFrameSourceSequenceTest {
    @Test
    fun `following applies the current step at once`() {
        val source = LiveFrameSource(AtmospherePresets.IDLE)
        val player = LumosSequencePlayer(presets("thinking", "idle"))

        source.follow(player)

        assertEquals("thinking", source.lookKey)
        assertEquals("thinking", source.runtime.atmosphereChoreographer?.activeTransition?.toPresetName)
    }

    @Test
    fun `a step naming the preset the scene is in applies nothing`() {
        val source = LiveFrameSource(AtmospherePresets.IDLE)
        val player = LumosSequencePlayer(presets("idle", "thinking"))
        val before = source.revision

        source.follow(player)

        assertNull(source.runtime.atmosphereChoreographer?.activeTransition)
        assertEquals(before, source.revision)
    }

    @Test
    fun `a state step bumps the revision so a paused orb redraws`() {
        val source = LiveFrameSource(AtmospherePresets.IDLE)
        val before = source.revision

        source.follow(LumosSequencePlayer(presets("thinking", "idle")))

        assertTrue(source.revision > before)
    }

    @Test
    fun `the clocked source advances the player after building each frame`() {
        val source = LiveFrameSource(AtmospherePresets.IDLE.copy(resolution = 2))
        val player = LumosSequencePlayer(presets("thinking", "idle", seconds = 0.5f))

        source.follow(player, clock = true)
        assertEquals(1, player.connections)
        repeat(10) { source.next(0.05f) }

        assertEquals(0.5f, player.timeSeconds, 1e-5f)
        // Frame 10 is the sequence at 0.5 s: the second step, applied before it is built.
        source.next(0.05f)
        assertEquals("idle", source.runtime.atmosphereChoreographer?.activeTransition?.toPresetName)
    }

    @Test
    fun `an unclocked source leaves time to the driver`() {
        val source = LiveFrameSource(AtmospherePresets.IDLE.copy(resolution = 2))
        val player = LumosSequencePlayer(presets("thinking", "idle", seconds = 0.5f))
        player.connect()

        source.follow(player)
        repeat(10) { source.next(0.05f) }

        assertEquals(0f, player.timeSeconds)
        assertEquals(1, player.connections)
    }

    @Test
    fun `leaving a sequence releases the clocked connection`() {
        val source = LiveFrameSource(AtmospherePresets.IDLE.copy(resolution = 2))
        val player = LumosSequencePlayer(presets("thinking", "idle"))
        source.follow(player, clock = true)

        source.follow(null)

        assertEquals(0, player.connections)
        assertNull(source.sequence)
    }

    @Test
    fun `a form step morphs and the same form named again does not`() {
        val source = LiveFrameSource(AtmospherePresets.IDLE.copy(resolution = 2))
        val player =
            LumosSequencePlayer(
                LumosSequence(
                    steps =
                        listOf(
                            LumosStep("sphere", 0.1f, form = "sphere"),
                            LumosStep("sphere→fibonacci", 0.1f, form = "fibonacci"),
                            LumosStep("fibonacci", 0.1f, form = "fibonacci"),
                        ),
                    loop = false,
                ),
            )
        player.connect()

        // Already the lattice: naming it is not a morph.
        source.follow(player)
        source.next(0.05f)
        assertFalse(source.builder.isMorphing)

        val lattice = source.builder.form
        player.seek(0.1f)
        source.next(0.05f)
        assertTrue(source.builder.isMorphing)
        val form = source.builder.form
        assertNotEquals(lattice, form)

        // Still morphing when the loop step names the form again: no new trip is started.
        player.seek(0.2f)
        source.next(0.05f)
        assertSame(form, source.builder.form)
        assertEquals(2, source.sequence?.snapshot?.stepIndex)
    }

    @Test
    fun `a look step blends to the look`() {
        val source = LiveFrameSource(AtmospherePresets.IDLE.copy(resolution = 2))
        val working = LumosSequence(steps = listOf(LumosStep("working", 1f, look = Look(LumosLook.WORKING))))
        val player = LumosSequencePlayer(working)

        source.follow(player)
        source.next(0.05f)

        assertEquals(Look(LumosLook.WORKING), source.builder.look)
        assertTrue(source.builder.isLookBlending)
    }

    @Test
    fun `a glyph fires on every entry including after a wrap`() {
        val source = LiveFrameSource(AtmospherePresets.IDLE.copy(resolution = 2))
        val player =
            LumosSequencePlayer(
                LumosSequence(
                    steps =
                        listOf(
                            LumosStep("done", 0.2f, glyph = LumosGlyph.CHECK, transition = LumosTransition(0.1f)),
                        ),
                ),
            )
        player.connect()
        source.follow(player)

        assertNotNull(source.next(0.05f).glyph)
        player.seek(0.15f)
        assertNull(source.next(0.05f).glyph, "the 0.1 s window has closed")
        player.seek(0.2f)
        assertEquals("CHECK", source.next(0.05f).glyph?.glyphName)
    }

    @Test
    fun `an explicit transition overrides the table`() {
        val source = LiveFrameSource(AtmospherePresets.IDLE)
        val player =
            LumosSequencePlayer(
                LumosSequence(
                    steps =
                        listOf(
                            LumosStep(
                                "idle→thinking",
                                1f,
                                atmospherePreset = "thinking",
                                transition = LumosTransition(0.25f, easingName = "eager"),
                            ),
                        ),
                ),
            )

        source.follow(player)

        val transition = assertNotNull(source.runtime.atmosphereChoreographer?.activeTransition)
        assertEquals("eager", transition.easingName)
    }

    private fun presets(
        vararg names: String,
        seconds: Float = 1f,
    ): LumosSequence =
        LumosSequence(
            steps = names.map { LumosStep(it, seconds, atmospherePreset = it, loop = true) },
        )
}
