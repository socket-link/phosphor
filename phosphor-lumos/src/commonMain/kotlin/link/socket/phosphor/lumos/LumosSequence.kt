package link.socket.phosphor.lumos

import kotlin.math.max
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import link.socket.phosphor.choreography.AtmosphereChoreographer
import link.socket.phosphor.field.Choreography
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.timeline.Easing

/**
 * One beat of a [LumosSequence]: what the orb shows, for how long, and how it gets there.
 *
 * A step names exactly one of [look], [form], [atmospherePreset] or [glyph], or a [look]
 * together with an [atmospherePreset]. When the sequence reaches the step, whatever it names is
 * applied — the atmosphere transitions, the form morphs, the look blends, the glyph fires — and
 * the orb then rests there for the remainder of [durationSeconds]. A step that names what the
 * orb already shows applies nothing, so a loop step may follow the one-shot that landed its
 * state and name the same preset without restarting the transition. A glyph is a one-shot and
 * fires every time its step is entered.
 *
 * Captured by `Oscilloscope`, a step is one `TraceSegment`: [name] is the segment's name, so the
 * `TraceStateMachine` convention applies — a resting beat is `"idle"`, a transition between two
 * beats is `"idle→thinking"`, a morph is `"sphere→fibonacci"` — and [loop] is carried. A
 * `loop` step should start where its arrival has landed, as a cue-captured loop does: a loop that
 * captures its own transition pops when it wraps.
 *
 * @property name What the step is called; non-blank and unique within its sequence. Becomes the
 *  `TraceSegment` name when captured.
 * @property durationSeconds The step's length including its arriving transition, in
 *  [MIN_DURATION_SECONDS]..[MAX_DURATION_SECONDS].
 * @property look The [Look] to blend to, or null to leave the look alone.
 * @property form The form to morph to, by catalogue name (`Forms.byName`: `"fibonacci"`,
 *  `"cube"`) or [LATTICE_FORM_NAME], the lattice at the atmosphere's resolution. Null leaves the
 *  form alone.
 * @property atmospherePreset The atmosphere preset to transition to, by name
 *  (`AtmospherePresets.byName`, case-insensitive). Null leaves the atmosphere alone.
 * @property glyph The glyph to fire on entering the step, or null.
 * @property transition How the step arrives; null takes the default for what it names — the
 *  `AtmosphereChoreographer` table for a preset, `VoxelFrameBuilder.DEFAULT_MORPH_SECONDS` for a
 *  form, `VoxelFrameBuilder.LOOK_BLEND_SECONDS` for a look, the generator's default window for a
 *  glyph.
 * @property loop Whether a player of the captured clip should loop this step rather than play it
 *  once. Does not change how the live sequence advances.
 */
@Serializable
data class LumosStep(
    val name: String,
    val durationSeconds: Float,
    val look: Look? = null,
    val form: String? = null,
    val atmospherePreset: String? = null,
    val glyph: LumosGlyph? = null,
    val transition: LumosTransition? = null,
    val loop: Boolean = false,
) {
    /** Whether this step changes the atmosphere, form or look — anything but a glyph alone. */
    val changesState: Boolean get() = look != null || form != null || atmospherePreset != null

    companion object {
        /** The shortest step, in seconds. */
        const val MIN_DURATION_SECONDS: Float = 0.05f

        /** The longest step, in seconds: an hour. */
        const val MAX_DURATION_SECONDS: Float = 3600f

        /** The [form] name for the lattice every preset renders on, as `TraceSegment` names it. */
        const val LATTICE_FORM_NAME: String = "sphere"
    }
}

/**
 * How a [LumosStep] arrives.
 *
 * @property durationSeconds How long the arrival takes; `>= 0`, and no longer than the step. `0`
 *  snaps.
 * @property choreography The arc cells travel on when the step morphs a form; atmosphere, look
 *  and glyph steps ignore it.
 * @property easingName An `Easing.byName` identifier for the atmosphere's curve or the morph's
 *  arc envelope.
 */
@Serializable
data class LumosTransition(
    val durationSeconds: Float,
    val choreography: Choreography = Choreography.FLOW,
    val easingName: String = DEFAULT_EASING,
) {
    companion object {
        /** The easing when none is given: the choreographer's own default. */
        const val DEFAULT_EASING: String = "easeInOut"
    }
}

/**
 * Where a [LumosSequence] is at one moment: a pure function of sequence time, which is what lets
 * a capture and a live player agree.
 *
 * @property stepIndex Index of the current step in [LumosSequence.steps].
 * @property step The current step.
 * @property elapsedSeconds Time into the step, `0 <= elapsed < step.durationSeconds` except at
 *  the end of a sequence that does not loop, where it equals the duration.
 * @property progress [elapsedSeconds] over the step's duration, in `0..1`.
 * @property transitioning Whether the step's arrival is still in flight: [elapsedSeconds] is
 *  shorter than its arriving transition.
 * @property cycle How many times the sequence has wrapped; always 0 for one that does not loop.
 * @property timeSeconds The time the snapshot was sampled at, as given.
 * @property ended Whether a sequence that does not loop has run out.
 */
data class LumosSequenceSnapshot(
    val stepIndex: Int,
    val step: LumosStep,
    val elapsedSeconds: Float,
    val progress: Float,
    val transitioning: Boolean,
    val cycle: Int,
    val timeSeconds: Float,
    val ended: Boolean,
)

/**
 * Why a [LumosSequence] is not one. Thrown by the constructor, so a sequence that exists is
 * valid, and returned inside [LumosSequence.fromJson]'s failure.
 */
sealed class LumosSequenceError(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause) {
    /** The JSON could not be read as a sequence at all. */
    class Malformed(
        message: String,
        cause: Throwable? = null,
    ) : LumosSequenceError(message, cause)

    /** The document's [version] is not one this library reads. */
    class UnsupportedVersion(
        val version: Int,
    ) : LumosSequenceError(
            "version $version is not supported; this library reads version ${LumosSequence.VERSION}",
        )

    /** Fewer than [LumosSequence.MIN_STEPS] or more than [LumosSequence.MAX_STEPS] steps. */
    class StepCount(
        val count: Int,
    ) : LumosSequenceError(
            "a sequence has ${LumosSequence.MIN_STEPS}..${LumosSequence.MAX_STEPS} steps, this one has $count",
        )

    /** One step is wrong; [index] and [name] say which. */
    class Step(
        val index: Int,
        val name: String,
        val reason: String,
    ) : LumosSequenceError("step $index \"$name\": $reason")
}

/**
 * An orb's choreography as data: steps of look, form, atmosphere and glyph with durations and
 * transitions, that `Oscilloscope` captures into named segments and `SignalGenerator` plays
 * live, from one definition. [fromJson] is what a consumer bundles; [LumosSequencePlayer] is the
 * clock.
 *
 * ```json
 * {
 *   "version": 1,
 *   "loop": true,
 *   "steps": [
 *     { "name": "idle", "durationSeconds": 2.0, "atmospherePreset": "idle", "loop": true },
 *     { "name": "idle→thinking", "durationSeconds": 0.8, "atmospherePreset": "thinking" },
 *     { "name": "thinking", "durationSeconds": 2.0, "atmospherePreset": "thinking", "loop": true },
 *     { "name": "thinking→idle", "durationSeconds": 1.0, "atmospherePreset": "idle" }
 *   ]
 * }
 * ```
 *
 * [sample] is a pure seek: the same time always gives the same snapshot, so a capture that steps
 * the sequence at a fixed `1 / fps` and a player that steps it by a measured frame gap are
 * looking at one function. Step boundaries are compared with a [BOUNDARY_TOLERANCE_SECONDS]
 * tolerance, so a clock accumulated as `Float` lands the frame the author expects.
 *
 * The constructor validates and throws a [LumosSequenceError] — 1..128 steps, each with a
 * non-blank unique name, a duration in range, exactly one primary field (or a look with a
 * preset), a transition no longer than the step, and form, preset and easing names that resolve.
 *
 * @property version Format version; [VERSION] is the only one read.
 * @property steps The beats, in order.
 * @property loop Whether the sequence wraps to its first step when it runs out, or ends.
 * @property defaults How a surface should drive and show an orb playing this sequence; a hint
 *  for the consumer, not applied by the player.
 */
@Serializable
data class LumosSequence(
    val version: Int = VERSION,
    val steps: List<LumosStep>,
    val loop: Boolean = true,
    val defaults: LumosConfig = LumosConfig(),
) {
    init {
        validate()?.let { throw it }
    }

    /** Where each step starts, in seconds from the sequence's start; one entry per step. */
    @Transient
    val stepStarts: List<Float> =
        buildList(steps.size) {
            var cursor = 0f
            steps.forEach { step ->
                add(cursor)
                cursor += step.durationSeconds
            }
        }

    /** The sum of every step's duration, in seconds: one pass of the sequence. */
    @Transient
    val durationSeconds: Float = stepStarts.last() + steps.last().durationSeconds

    /**
     * How long each step's arrival lasts, in seconds: its explicit transition, else the default
     * for what it names — the choreographer's table for a preset (looked up from the preset the
     * step before it names, or the sequence's last preset when it loops), the morph default for a
     * form, the look blend for a look, and `0` for a glyph alone, which has no arrival. A step
     * that names only what the step before it already named arrives in `0`: a loop step resting
     * where its one-shot landed applies nothing, and says so.
     */
    @Transient
    val transitionSeconds: List<Float> =
        List(steps.size) { index ->
            val step = steps[index]
            step.transition?.durationSeconds ?: defaultTransitionSeconds(index)
        }

    /**
     * Where the sequence is at [seconds] from its start.
     *
     * Past the end, a looping sequence wraps and a finite one holds its last step with
     * [LumosSequenceSnapshot.ended] set. Negative time is the start.
     */
    fun sample(seconds: Float): LumosSequenceSnapshot {
        val clamped = if (seconds.isNaN()) 0f else seconds.coerceAtLeast(0f)
        val cycle: Int
        val local: Float
        val ended: Boolean
        if (clamped >= durationSeconds - BOUNDARY_TOLERANCE_SECONDS) {
            if (loop) {
                val passes = (clamped / durationSeconds).toInt()
                val remainder = clamped - passes * durationSeconds
                // Float residue can leave the remainder a hair short of a full pass.
                if (remainder >= durationSeconds - BOUNDARY_TOLERANCE_SECONDS) {
                    cycle = passes + 1
                    local = 0f
                } else {
                    cycle = passes
                    local = remainder
                }
                ended = false
            } else {
                val last = steps.lastIndex
                return LumosSequenceSnapshot(
                    stepIndex = last,
                    step = steps[last],
                    elapsedSeconds = steps[last].durationSeconds,
                    progress = 1f,
                    transitioning = false,
                    cycle = 0,
                    timeSeconds = seconds,
                    ended = true,
                )
            }
        } else {
            cycle = 0
            local = clamped
            ended = false
        }
        var index = 0
        while (index + 1 < steps.size && local >= stepStarts[index + 1] - BOUNDARY_TOLERANCE_SECONDS) {
            index++
        }
        val step = steps[index]
        val elapsed = (local - stepStarts[index]).coerceIn(0f, step.durationSeconds)
        return LumosSequenceSnapshot(
            stepIndex = index,
            step = step,
            elapsedSeconds = elapsed,
            progress = (elapsed / step.durationSeconds).coerceIn(0f, 1f),
            transitioning = elapsed < transitionSeconds[index],
            cycle = cycle,
            timeSeconds = seconds,
            ended = ended,
        )
    }

    /** This sequence as JSON, as [fromJson] reads it. Defaults are left out. */
    fun toJson(): String = json().encodeToString(serializer(), this)

    /** The index of the step called [name], or -1. */
    fun indexOf(name: String): Int = steps.indexOfFirst { it.name == name }

    private fun defaultTransitionSeconds(index: Int): Float {
        val step = steps[index]
        var arrival = 0f
        step.atmospherePreset?.lowercase()?.let { preset ->
            val from = before(index) { it.atmospherePreset?.lowercase() }
            if (preset != from) {
                arrival = max(arrival, AtmosphereChoreographer.transitionSpec(from, preset).durationSeconds)
            }
        }
        step.form?.lowercase()?.let { form ->
            if (form != before(index) { it.form?.lowercase() }) {
                arrival = max(arrival, VoxelFrameBuilder.DEFAULT_MORPH_SECONDS)
            }
        }
        step.look?.let { look ->
            if (look != before(index) { it.look }) {
                arrival = max(arrival, VoxelFrameBuilder.LOOK_BLEND_SECONDS)
            }
        }
        return arrival
    }

    // What the orb shows when step `index` arrives, as [field] reads it: the nearest earlier
    // step's value, wrapping to the end when the sequence loops. Null when no step before it
    // names one.
    private fun <T : Any> before(
        index: Int,
        field: (LumosStep) -> T?,
    ): T? {
        for (back in 1 until steps.size) {
            val at = index - back
            val resolved =
                when {
                    at >= 0 -> at
                    loop -> at + steps.size
                    else -> return null
                }
            field(steps[resolved])?.let { return it }
        }
        return null
    }

    private fun validate(): LumosSequenceError? {
        if (version != VERSION) return LumosSequenceError.UnsupportedVersion(version)
        if (steps.size !in MIN_STEPS..MAX_STEPS) return LumosSequenceError.StepCount(steps.size)
        val names = HashSet<String>()
        steps.forEachIndexed { index, step ->
            fun fail(reason: String) = LumosSequenceError.Step(index, step.name, reason)
            if (step.name.isBlank()) return fail("name must not be blank")
            if (!names.add(step.name)) return fail("name is used by an earlier step; names are unique")
            if (step.durationSeconds.isNaN() ||
                step.durationSeconds < LumosStep.MIN_DURATION_SECONDS ||
                step.durationSeconds > LumosStep.MAX_DURATION_SECONDS
            ) {
                return fail(
                    "durationSeconds must be in " +
                        "${LumosStep.MIN_DURATION_SECONDS}..${LumosStep.MAX_DURATION_SECONDS}, " +
                        "was ${step.durationSeconds}",
                )
            }
            val primaries =
                listOfNotNull(
                    step.look?.let { "look" },
                    step.form?.let { "form" },
                    step.atmospherePreset?.let { "atmospherePreset" },
                    step.glyph?.let { "glyph" },
                )
            val lookWithPreset = primaries.size == 2 && "look" in primaries && "atmospherePreset" in primaries
            if (primaries.isEmpty()) {
                return fail("a step names one of look, form, atmospherePreset or glyph")
            }
            if (primaries.size > 1 && !lookWithPreset) {
                return fail(
                    "a step names exactly one of look, form, atmospherePreset or glyph, or a look with an " +
                        "atmospherePreset; this one names ${primaries.joinToString(" + ")}",
                )
            }
            step.form?.let { form ->
                val lattice = form.equals(LumosStep.LATTICE_FORM_NAME, ignoreCase = true)
                if (!lattice && Forms.byName(form) == null) {
                    return fail(
                        "unknown form \"$form\"; the catalogue has ${Forms.All.map { it.first }} and " +
                            "\"${LumosStep.LATTICE_FORM_NAME}\"",
                    )
                }
            }
            step.atmospherePreset?.let { preset ->
                if (AtmospherePresets.byName(preset) == null) {
                    return fail(
                        "unknown atmosphere preset \"$preset\"; the presets are " +
                            "${AtmospherePresets.ALL.map { it.first }}",
                    )
                }
            }
            step.transition?.let { transition ->
                if (transition.durationSeconds.isNaN() || transition.durationSeconds < 0f) {
                    return fail("transition.durationSeconds must be >= 0, was ${transition.durationSeconds}")
                }
                if (transition.durationSeconds > step.durationSeconds) {
                    return fail(
                        "transition.durationSeconds ${transition.durationSeconds} is longer than the step's " +
                            "${step.durationSeconds}",
                    )
                }
                if (Easing.byName(transition.easingName) == null) {
                    return fail("unknown easing \"${transition.easingName}\"; see Easing.byName")
                }
            }
        }
        return null
    }

    companion object {
        /** The format version this library reads and writes. */
        const val VERSION: Int = 1

        /** The fewest steps a sequence has. */
        const val MIN_STEPS: Int = 1

        /** The most steps a sequence has. */
        const val MAX_STEPS: Int = 128

        /**
         * How close to a step boundary counts as past it, in seconds: a tenth of a millisecond,
         * well under any frame and over the residue of summing `Float` steps.
         */
        const val BOUNDARY_TOLERANCE_SECONDS: Float = 1e-4f

        // A function, not a property: a companion property's initialiser runs whenever the class is
        // reached, and would carry kotlinx-serialization-json into every bundle that steps an orb —
        // :phosphor-lumos-dom reaches LumosSequence through OrbStepper and never parses one.
        private fun json(): Json =
            Json {
                ignoreUnknownKeys = true
                prettyPrint = false
            }

        /**
         * Read a sequence from JSON.
         *
         * @return The sequence, or a failure carrying a [LumosSequenceError]:
         *  [LumosSequenceError.Malformed] when the text is not a sequence document,
         *  otherwise the validation error the constructor would throw, which names the step.
         */
        fun fromJson(text: String): Result<LumosSequence> =
            try {
                Result.success(json().decodeFromString(serializer(), text))
            } catch (error: LumosSequenceError) {
                Result.failure(error)
            } catch (error: SerializationException) {
                Result.failure(unwrap(error) ?: LumosSequenceError.Malformed(error.message ?: "not a sequence", error))
            } catch (error: IllegalArgumentException) {
                // A Look with a variant its look does not offer, or a LumosConfig out of range.
                Result.failure(unwrap(error) ?: LumosSequenceError.Malformed(error.message ?: "not a sequence", error))
            }

        // A LumosSequenceError raised inside decoding may come back wrapped; find it.
        private fun unwrap(error: Throwable): LumosSequenceError? {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is LumosSequenceError) return cause
                cause = cause.cause
            }
            return null
        }
    }
}
