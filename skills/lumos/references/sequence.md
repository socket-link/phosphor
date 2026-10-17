# Changing a Lumos orb over time

Looks, forms, atmospheres and glyphs in motion; an agent's state driving the orb; a recording replayed.

## Changing the orb over time

Every change below carries on from where the orb is. Make it on the UI thread, where `LumosOrb` steps the source: in a `LaunchedEffect`, a `SideEffect` or a UI callback.

| Change | Call | Default |
|---|---|---|
| Look | `source.builder.lookTo(look, durationSeconds)` | blends over 0.6 s |
| Form | `source.builder.morphTo(form, count, choreography, durationSeconds)` | springs over 2.4 s on `FLOW` |
| A push | `source.builder.burst(strength)` | cells fly out and spring home; Dots' burst is about 6 |
| Atmosphere | `source.runtime.setAtmospherePreset(name)` | blends over the preset pair's duration |
| Glyph | `source.builder.queueGlyph(glyph, durationSeconds)` | shows for 1.5 s |
| Agent state | `generator.setPhase(...)`, `setActivity(...)`, `setEscalated(...)` | picks the look by the table above and blends to it |

Setting `source.builder.look` or `source.builder.form` directly is a cut: the next frame wears the
new look, or rests on the new form, outright. A look asked for mid-blend waits for the blend to land,
so a signal that flickers changes the look at most once per blend; asking for the look being left
reverses in place. `morphTo` keeps every cell's identity and springs it to the nearest free place in
the new form; calling it mid-morph starts a new trip from wherever the cells are.

| Choreography | How cells travel in a morph |
|---|---|
| `FLOW` | A gentle arc scaled to the distance: the body reshaping. The default. |
| `SCATTER` | Every cell flies out before settling: the body bursts and regathers. |
| `DIRECT` | The straight line each cell's spring gives it. |

## Driving the orb from an agent's state

`SignalGenerator` (`:phosphor-trace`) runs the scene and its frame builder together. Its first setter adds an agent to the scene and installs the look switch, so the orb follows the agent-state table above and fires its glyphs. `setLook(look)` picks a look by hand.

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.compose.LumosOrb
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase
import link.socket.phosphor.trace.SignalGenerator

@Composable
fun AgentOrb(
    phase: CognitivePhase,
    activity: AgentActivityState,
    escalated: Boolean,
) {
    // 1. A scene with nothing in it but an atmosphere; the first setter adds one agent to it
    //    and installs CognitiveLookSwitch's default phase → look table
    val generator =
        remember {
            SignalGenerator(
                SceneConfiguration(
                    width = 1,
                    height = 1,
                    enableWaveform = false,
                    enableParticles = false,
                    enableFlow = false,
                    enableEmitters = false,
                    enableCamera = false,
                    enableAtmosphere = true,
                    initialAtmosphere = AtmospherePresets.THINKING,
                ),
            )
        }
    val source = remember(generator) { generator.asFrameSource() }

    // 2. Hand it the agent's signal; the orb picks its look and fires the row's glyph
    LaunchedEffect(phase, activity, escalated) {
        generator.setPhase(phase)
        generator.setActivity(activity)
        generator.setEscalated(escalated)
    }

    LumosOrb(
        source = source,
        config = LumosConfig(label = "Lumos, ${phase.name.lowercase()}"),
        modifier = Modifier.size(160.dp),
    )
}
```

The generator is the handle: it keeps its scene and frame builder to itself, so there is no `generator.builder`. `setAtmospherePreset(name)`, `setAtmosphere(state)`, `morphTo(form, count, choreography, durationSeconds)`, `queueGlyph(glyph)` and `setLook(look)` do what the `source.runtime` and `source.builder` calls above do. To give it a small orb's form, call `generator.morphTo(Forms.Fibonacci, count = Forms.inlineOrbCount(sizePx = 20), durationSeconds = 0f)` once, inside its `remember`: a duration of 0 snaps.

Drive a generator one way at a time. Through `asFrameSource()` the orb steps it; its own `frames()` and `nextFrame()` step it at a fixed rate for recording, and using both steps the scene twice.

## Replaying a recording

A `.vxt` is a recorded orb: `VoxelTrace`, encoded by `TraceCodec` (`:phosphor-trace`). `LumosSource.Trace` plays one; `asFrameSource()` hands it to `LumosOrb`. Projection, rotation, halo and the glyph overlay stay live over a replay.

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.compose.LumosOrb
import link.socket.phosphor.trace.LumosSource
import link.socket.phosphor.trace.TraceCodec
import link.socket.phosphor.trace.asFrameSource

@Composable
fun ReplayedOrb(vxt: ByteArray) {
    // 1. Decode a recorded .vxt once; the bytes come from your resources, a file or the network.
    //    LumosSource.Trace(trace, initialSegment = "thinking") starts on a named segment.
    val replay = remember(vxt) { LumosSource.Trace(TraceCodec.decode(vxt).getOrThrow()) }
    val source = remember(replay) { replay.asFrameSource() }

    // 2. The same orb and the same config as a live one: only the source changed
    LumosOrb(
        source = source,
        config = LumosConfig(label = "Lumos, replayed"),
        modifier = Modifier.size(160.dp),
    )

    // 3. The player is the handle, on the UI thread: replay.player.pause(), .play(), .setSegment("ready")
}
```

A looping segment's rotation is carried across its seam, so the orb does not drop back each pass; pass `carryRotation = false` for the recording untouched. `setSegment` and `seek` are cuts.

---

Generated from the Lumos catalogues by `./gradlew :phosphor-lumos:generateAgentDocs`. Do not edit by hand.
