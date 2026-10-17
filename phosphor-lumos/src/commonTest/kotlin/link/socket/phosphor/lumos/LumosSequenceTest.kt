package link.socket.phosphor.lumos

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import link.socket.phosphor.field.Choreography

/**
 * PHO-65 Task B: the format. Validation names the step, `sample` is a pure seek with a tolerance
 * at its boundaries, arrivals default to what the step names, and JSON round-trips.
 */
class LumosSequenceTest {
    @Test
    fun `durations and starts add up`() {
        val sequence = demo()

        assertEquals(listOf(0f, 2f, 2.8f, 4.8f), sequence.stepStarts)
        assertEquals(5.8f, sequence.durationSeconds, 1e-6f)
    }

    @Test
    fun `sample lands each step from its start and wraps a loop`() {
        val sequence = demo()

        assertEquals(0, sequence.sample(0f).stepIndex)
        assertEquals(0, sequence.sample(1.999f).stepIndex)
        assertEquals(1, sequence.sample(2f).stepIndex)
        assertEquals(1, sequence.sample(2.799f).stepIndex)
        assertEquals(2, sequence.sample(2.8f).stepIndex)
        assertEquals(3, sequence.sample(5.7f).stepIndex)

        val wrapped = sequence.sample(5.8f)
        assertEquals(0, wrapped.stepIndex)
        assertEquals(1, wrapped.cycle)
        assertEquals(0f, wrapped.elapsedSeconds)
        assertFalse(wrapped.ended)

        val secondPass = sequence.sample(5.8f + 2.5f)
        assertEquals(1, secondPass.stepIndex)
        assertEquals(1, secondPass.cycle)
        assertEquals(0.5f, secondPass.elapsedSeconds, 1e-4f)
    }

    @Test
    fun `a boundary is passed within a tenth of a millisecond and not before`() {
        val sequence = demo()

        assertEquals(1, sequence.sample(2f - 0.5e-4f).stepIndex)
        assertEquals(0, sequence.sample(2f - 1e-3f).stepIndex)
    }

    @Test
    fun `a finite sequence ends on its last step`() {
        val sequence = demo().copy(loop = false)

        val ended = sequence.sample(10f)

        assertTrue(ended.ended)
        assertEquals(3, ended.stepIndex)
        assertEquals(1f, ended.progress)
        assertEquals(0, ended.cycle)
        assertFalse(sequence.sample(5.7f).ended)
    }

    @Test
    fun `negative and NaN time are the start`() {
        val sequence = demo()

        assertEquals(0f, sequence.sample(-3f).elapsedSeconds)
        assertEquals(0, sequence.sample(Float.NaN).stepIndex)
    }

    @Test
    fun `progress and elapsed follow the step`() {
        val snapshot = demo().sample(2.4f)

        assertEquals(1, snapshot.stepIndex)
        assertEquals(0.4f, snapshot.elapsedSeconds, 1e-6f)
        assertEquals(0.5f, snapshot.progress, 1e-6f)
        assertEquals(2.4f, snapshot.timeSeconds)
    }

    @Test
    fun `a preset step arrives on the choreographer's table`() {
        val sequence = demo()

        // idle→thinking is 0.8 s on the table; thinking→idle is 1.0 s.
        assertEquals(0.8f, sequence.transitionSeconds[1])
        assertEquals(1.0f, sequence.transitionSeconds[3])
        assertTrue(sequence.sample(2.5f).transitioning)

        val held =
            LumosSequence(
                steps =
                    listOf(
                        LumosStep("idle", 1f, atmospherePreset = "idle"),
                        LumosStep("idle→thinking", 2f, atmospherePreset = "thinking"),
                    ),
            )
        assertTrue(held.sample(1.5f).transitioning)
        assertFalse(held.sample(1.9f).transitioning)
    }

    @Test
    fun `a loop step naming the state it rests in arrives in no time`() {
        // "thinking" after "idle→thinking" applies nothing, so nothing is in flight.
        assertEquals(0f, demo().transitionSeconds[2])
        assertFalse(demo().sample(2.85f).transitioning)
    }

    @Test
    fun `the first step of a looping sequence reads what the last one left`() {
        val sequence = demo()

        // idle after thinking→idle names idle again: nothing arrives.
        assertEquals(0f, sequence.transitionSeconds[0])
        // With no loop nothing comes before it, and the untabled default applies.
        assertEquals(1.1f, sequence.copy(loop = false).transitionSeconds[0])
    }

    @Test
    fun `form look and glyph steps arrive on their own defaults`() {
        val sequence =
            LumosSequence(
                steps =
                    listOf(
                        LumosStep("to-cube", 3f, form = "cube"),
                        LumosStep("working", 1f, look = Look(LumosLook.WORKING)),
                        LumosStep("done", 1f, glyph = LumosGlyph.CHECK),
                        LumosStep(
                            "fast",
                            1f,
                            form = "sphere",
                            transition = LumosTransition(0.25f, Choreography.SCATTER),
                        ),
                    ),
            )

        assertEquals(
            listOf(VoxelFrameBuilder.DEFAULT_MORPH_SECONDS, VoxelFrameBuilder.LOOK_BLEND_SECONDS, 0f, 0.25f),
            sequence.transitionSeconds,
        )
    }

    @Test
    fun `a sequence has one to 128 steps`() {
        assertIs<LumosSequenceError.StepCount>(
            assertFailsWith<LumosSequenceError> { LumosSequence(steps = emptyList()) },
        )
        val many = List(129) { LumosStep("step-$it", 1f, atmospherePreset = "idle") }
        assertIs<LumosSequenceError.StepCount>(assertFailsWith<LumosSequenceError> { LumosSequence(steps = many) })
        LumosSequence(steps = many.take(128))
    }

    @Test
    fun `only version 1 is read`() {
        val error =
            assertFailsWith<LumosSequenceError.UnsupportedVersion> {
                LumosSequence(version = 2, steps = listOf(LumosStep("idle", 1f, atmospherePreset = "idle")))
            }
        assertEquals(2, error.version)
    }

    @Test
    fun `a step error names the step`() {
        val error =
            stepError(
                LumosStep("idle", 1f, atmospherePreset = "idle"),
                LumosStep("idle", 1f, atmospherePreset = "idle"),
            )

        assertEquals(1, error.index)
        assertEquals("idle", error.name)
        assertTrue("unique" in error.reason, error.reason)
    }

    @Test
    fun `a step needs a name a duration in range and one primary field`() {
        assertTrue("blank" in stepError(LumosStep("  ", 1f, atmospherePreset = "idle")).reason)
        assertTrue("durationSeconds" in stepError(LumosStep("short", 0.04f, atmospherePreset = "idle")).reason)
        assertTrue("durationSeconds" in stepError(LumosStep("long", 3600.5f, atmospherePreset = "idle")).reason)
        assertTrue("names one of" in stepError(LumosStep("empty", 1f)).reason)
        assertTrue("exactly one" in stepError(LumosStep("two", 1f, form = "cube", glyph = LumosGlyph.STAR)).reason)
    }

    @Test
    fun `a look may ride with a preset and nothing else may`() {
        LumosSequence(
            steps = listOf(LumosStep("both", 1f, look = Look(LumosLook.WORKING), atmospherePreset = "thinking")),
        )

        val error =
            stepError(
                LumosStep(
                    "three",
                    1f,
                    look = Look(LumosLook.WORKING),
                    atmospherePreset = "thinking",
                    glyph = LumosGlyph.CHECK,
                ),
            )
        assertTrue("exactly one" in error.reason, error.reason)
    }

    @Test
    fun `unknown names fail at the step`() {
        assertTrue("unknown form" in stepError(LumosStep("x", 1f, form = "torus")).reason)
        assertTrue("unknown atmosphere preset" in stepError(LumosStep("x", 1f, atmospherePreset = "serene")).reason)
        val easing =
            stepError(LumosStep("x", 1f, form = "cube", transition = LumosTransition(0.5f, easingName = "zoom")))
        assertTrue("unknown easing" in easing.reason)
    }

    @Test
    fun `names resolve case-insensitively`() {
        LumosSequence(
            steps = listOf(LumosStep("x", 1f, form = "Cube"), LumosStep("y", 1f, atmospherePreset = "Thinking")),
        )
    }

    @Test
    fun `a transition fits inside its step`() {
        assertTrue(
            "longer than" in stepError(LumosStep("x", 1f, form = "cube", transition = LumosTransition(1.5f))).reason,
        )
        assertTrue(">= 0" in stepError(LumosStep("x", 1f, form = "cube", transition = LumosTransition(-0.1f))).reason)
        LumosSequence(steps = listOf(LumosStep("x", 1f, form = "cube", transition = LumosTransition(1f))))
    }

    @Test
    fun `JSON round-trips and leaves defaults out`() {
        val sequence =
            demo().copy(
                steps =
                    demo().steps +
                        LumosStep(
                            "gyro",
                            1f,
                            look = Look(LumosLook.WORKING, "gyro"),
                            atmospherePreset = "ready",
                            transition = LumosTransition(0.5f, Choreography.DIRECT, "eager"),
                        ) +
                        LumosStep("done", 1f, glyph = LumosGlyph.CHECK),
                defaults = LumosConfig(speed = 1.5f, label = "demo"),
            )

        val json = sequence.toJson()

        assertEquals(sequence, LumosSequence.fromJson(json).getOrThrow())
        assertFalse("\"transition\":null" in json, json)
        assertTrue("\"variant\":\"gyro\"" in json, json)
        assertFalse("\"variant\":\"default\"" in json, json)
    }

    @Test
    fun `fromJson reads a hand-written document and ignores keys it does not know`() {
        val sequence =
            LumosSequence
                .fromJson(
                    """
                    {
                      "version": 1,
                      "comment": "unknown keys are fine",
                      "steps": [
                        { "name": "idle", "durationSeconds": 2.0, "atmospherePreset": "idle", "loop": true },
                        { "name": "idle→thinking", "durationSeconds": 0.8, "atmospherePreset": "thinking",
                          "look": { "look": "WORKING" } },
                        { "name": "done", "durationSeconds": 1.5, "glyph": "CHECK",
                          "transition": { "durationSeconds": 1.0, "choreography": "SCATTER", "easingName": "overshoot" } }
                      ]
                    }
                    """.trimIndent(),
                ).getOrThrow()

        assertEquals(3, sequence.steps.size)
        assertTrue(sequence.loop)
        assertEquals(Look(LumosLook.WORKING), sequence.steps[1].look)
        assertEquals(LumosGlyph.CHECK, sequence.steps[2].glyph)
        assertEquals(Choreography.SCATTER, sequence.steps[2].transition?.choreography)
        assertEquals(LumosConfig(), sequence.defaults)
    }

    @Test
    fun `fromJson reports a malformed document`() {
        val error = LumosSequence.fromJson("{ not json").exceptionOrNull()

        assertIs<LumosSequenceError.Malformed>(error)
    }

    @Test
    fun `fromJson reports the offending step`() {
        val error =
            LumosSequence
                .fromJson(
                    """{"steps":[{"name":"idle","durationSeconds":1.0,"atmospherePreset":"idle"},""" +
                        """{"name":"torus","durationSeconds":1.0,"form":"torus"}]}""",
                ).exceptionOrNull()

        val step = assertIs<LumosSequenceError.Step>(error)
        assertEquals(1, step.index)
        assertEquals("torus", step.name)
    }

    @Test
    fun `fromJson reports a look variant its look does not offer as malformed`() {
        val error =
            LumosSequence
                .fromJson("""{"steps":[{"name":"x","durationSeconds":1.0,"look":{"look":"BASE","variant":"gyro"}}]}""")
                .exceptionOrNull()

        assertIs<LumosSequenceError.Malformed>(error)
    }

    @Test
    fun `indexOf finds a step by name`() {
        assertEquals(2, demo().indexOf("thinking"))
        assertEquals(-1, demo().indexOf("ready"))
    }

    private fun stepError(vararg steps: LumosStep): LumosSequenceError.Step =
        assertIs(assertFailsWith<LumosSequenceError> { LumosSequence(steps = steps.toList()) })

    private companion object {
        fun demo(): LumosSequence =
            LumosSequence(
                steps =
                    listOf(
                        LumosStep("idle", 2f, atmospherePreset = "idle", loop = true),
                        LumosStep("idle→thinking", 0.8f, atmospherePreset = "thinking"),
                        LumosStep("thinking", 2f, atmospherePreset = "thinking", loop = true),
                        LumosStep("thinking→idle", 1f, atmospherePreset = "idle"),
                    ),
            )
    }
}
