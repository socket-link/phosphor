# LumosSequence

A `LumosSequence` is an orb's choreography as data: steps of look, form,
atmosphere and glyph with durations and transitions. One definition is captured
by `Oscilloscope` into a `.vxt` with named segments and played live by
`SignalGenerator`, frame for frame, so a consumer authors an orb's "then this"
once and ships it as JSON.

It lives in `:phosphor-lumos`, is `@Serializable`, and needs no framework and no
coroutines. `LumosSequencePlayer` is its clock.

## Example

```json
{
  "version": 1,
  "loop": true,
  "steps": [
    { "name": "idle",           "durationSeconds": 2.0,  "atmospherePreset": "idle",     "loop": true },
    { "name": "idle→thinking",  "durationSeconds": 0.8,  "atmospherePreset": "thinking" },
    { "name": "thinking",       "durationSeconds": 2.0,  "atmospherePreset": "thinking", "loop": true,
      "look": { "look": "WORKING" } },
    { "name": "thinking→idle",  "durationSeconds": 1.0,  "atmospherePreset": "idle",
      "transition": { "durationSeconds": 1.0, "easingName": "settled" } },
    { "name": "done",           "durationSeconds": 1.5,  "glyph": "CHECK" },
    { "name": "idle→fibonacci", "durationSeconds": 2.5,  "form": "fibonacci",
      "transition": { "durationSeconds": 2.4, "choreography": "SCATTER" } },
    { "name": "fibonacci→idle", "durationSeconds": 2.5,  "form": "sphere" }
  ]
}
```

```kotlin
val sequence = LumosSequence.fromJson(text).getOrThrow()

// Live, the generator being the clock:
val player = generator.play(sequence)
player.pause(); player.seek(3f); player.play()

// Live, under an orb's clock (Compose):
val source = remember { LiveFrameSource(AtmospherePresets.IDLE).also { it.follow(player) } }
LumosOrb(source, player, modifier = Modifier.size(160.dp))

// Captured, one TraceSegment per step:
val trace = Oscilloscope.capture(CaptureConfig.forSequence(sequence, fps = 24)).getOrThrow()
TraceStateMachine(trace, initialState = "idle").requestState("thinking")
```

## The step

| Field | Meaning |
|---|---|
| `name` | Non-blank, unique in the sequence. Becomes the `TraceSegment` name, so follow its convention: a resting beat `"idle"`, a transition `"idle→thinking"`, a morph `"sphere→fibonacci"`. |
| `durationSeconds` | The step's length including its arriving transition, `0.05..3600`. |
| `look` | `{ "look": "WORKING", "variant": "gyro" }`; variant optional. Blends over `VoxelFrameBuilder.LOOK_BLEND_SECONDS` unless `transition` says. |
| `form` | A catalogue name (`fibonacci`, `cube`) or `sphere`, the lattice at the atmosphere's resolution. Morphs over `VoxelFrameBuilder.DEFAULT_MORPH_SECONDS` unless `transition` says. |
| `atmospherePreset` | A preset name, case-insensitive. Transitions on the `AtmosphereChoreographer` table unless `transition` says. |
| `glyph` | A `LumosGlyph` name. Fires on entering the step, every time, for 1.5 s unless `transition.durationSeconds` says. |
| `transition` | `{ durationSeconds, choreography = FLOW, easingName = "easeInOut" }`. No longer than the step. `choreography` is read by forms only. |
| `loop` | Carried onto the captured segment. Does not change how the live sequence advances. |

A step names exactly one of `look`, `form`, `atmospherePreset`, `glyph`, or a
`look` together with an `atmospherePreset`.

**A step that names what the orb already shows applies nothing.** The orb rests
in the state the previous one-shot landed, so `"thinking"` after
`"idle→thinking"` names `atmospherePreset: "thinking"` again without restarting
the transition, and `sample` reports no arrival for it. Glyphs are the exception:
they are one-shots and fire on every entry, including after a wrap.

**A `loop` step should start where its arrival has landed**, as a cue-captured
loop does. A loop step that carries its own transition pops when the recording
wraps. Write the transition as its own step, named with the arrow.

## Time

`sequence.sample(seconds)` is a pure seek: which step, how far in, whether its
arrival is still in flight, how many times the sequence has wrapped. Step
boundaries are compared with a 0.1 ms tolerance, so a clock accumulated as
`Float` lands the frame the author expects; write durations with enough digits
to land on whole frames at your capture rate (`20 / 24` is `0.83333`, not `0.8`).

`LumosSequencePlayer` ticks only while **connected** (`connect()` returned a
handle nobody released), **uncontrolled** (no `control()` handle outstanding)
and **playing**. Each `advance(dt)` is capped at 0.1 s, the same cap `OrbStepper`
puts on a frame gap. `control()` is how a scroll position or any external driver
owns time: while it holds, `advance` moves nothing and `seek` is the clock, and
the renderer keeps drawing. `connect()` lets several consumers share one clock.

Who advances the player:

| Driver | Clock |
|---|---|
| `SignalGenerator.play(sequence)` | the generator, by `1 / fps` after each `nextFrame`; frame `k` is the sequence at `k / fps` |
| `SignalGenerator.play(player)` | someone else; the generator only follows |
| `LumosOrb(source, player)` | the orb, by each frame's `dt`, connected only while the orb is on screen, started and not paused |
| `Oscilloscope.capture` with `CaptureConfig.sequence` | the fixed step, through `SignalGenerator.play(sequence)` |

## Capture

`CaptureConfig.forSequence(sequence, fps, …)` sizes a clip to one pass of the
sequence. Each step becomes one `TraceSegment(name, start, end, loop)`; the
layout is found by stepping a player at `1 / fps`, so it cannot disagree with
the frames. A step shorter than a frame, a rate under 10 fps, or a sequence given
alongside `segments`, `cues`, `formCues` or `phaseCues` is a
`CaptureError.InvalidConfig`. The trace's `paramSnapshot` carries the sequence's
JSON under `"sequence"`, so a clip can be traced back to its script.

Cue-driven capture produces the same frames: a sequence step naming a preset is
the `AtmosphereCue` at its first frame, a form step the `FormCue`.
`FormSeamByteIdentityTest` and `StateMachineDemoSequenceTest` hold the two
paths to the same bytes.

## Validation

`LumosSequence.fromJson` returns a `Result` whose failure is a sealed
`LumosSequenceError`:

| Error | When |
|---|---|
| `Malformed` | not a sequence document, or a value no type accepts (a `Look` variant its look does not offer) |
| `UnsupportedVersion` | `version` is not `1` |
| `StepCount` | fewer than 1 or more than 128 steps |
| `Step(index, name, reason)` | blank or duplicate name; duration out of range; not exactly one primary field; transition longer than the step or negative; unknown form, preset or easing |

The constructor throws the same errors, so a sequence that exists is valid.

## Not here

The `timeline { }` DSL in `phosphor-core` is code, not data, and is untouched.
Scroll-driven progress is enabled by `control()` but no scroll code lives in
Phosphor. Steps are discrete; interpolating arbitrary numeric channels between
them is not in the format.
