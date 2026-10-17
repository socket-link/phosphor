# Lumos looks, atmospheres and glyphs

What the orb shows for an agent's state: how its cells move, what colours them, and the one-shot marks.

## Looks

How the cells move for a cognitive moment: Thinking Orbs' catalogue, one look per agent state. Wear one with `Look(LumosLook.WORKING)` or a variant with `Look(LumosLook.WORKING, "gyro")`; the constructor throws on a variant the look does not offer. Every orb on one look shares its clock, so they move in step.

| Look | Variant | Key | Period | Drawn | Meaning |
|---|---|---|---|---|---|
| `BASE` | `default` | `base` | — | yes | Idle, or anything without a look of its own. The atmosphere's own spin. |
| `WORKING` | `default` | `working` | 1.7 s | yes | Busy, running a tool. A ring of light runs down the sphere. |
| `WORKING` | `gyro` | `working-gyro` | 1.7 s | as `default` |  |
| `REASONING` | `default` | `reasoning` | 0.22 s | yes | Thinking. A lit walk hops between neighbouring cells, steered toward the viewer. |
| `REASONING` | `twins` | `reasoning-twins` | 0.22 s | as `default` |  |
| `SEARCHING` | `default` | `searching` | 1.8 s | yes | Searching the web or files. A lens hops between spots on the front of the sphere, magnifying what it covers. |
| `SEARCHING` | `lighthouse` | `searching-lighthouse` | 1.8 s | as `default` |  |
| `BACKGROUND` | `default` | `background` | — | as BASE | Background work nobody is watching, like a dev server. |
| `BACKGROUND` | `spiral` | `background-spiral` | — | as BASE |  |
| `RETRYING` | `default` | `retrying` | 1.6 s | yes | Retrying after an error. The orb turns, brakes, springs back 60 % of the turn and tries again; it owns the spin while worn. |
| `RETRYING` | `surge` | `retrying-surge` | 1.6 s | as `default` |  |
| `COMPACTING` | `default` | `compacting` | — | as BASE | Compacting the context window, or consolidating what was learned. |
| `COMPACTING` | `squeeze` | `compacting-squeeze` | — | as BASE |  |
| `COMPACTING` | `fuse` | `compacting-fuse` | — | as BASE |  |
| `WAITING` | `default` | `waiting` | — | as BASE | Waiting on a human: worn while an agent is escalated. |

Period is how often the look's timing repeats: a loop capture of it wants a multiple. *Drawn* says what you see today: "as BASE" is a look that is declared but not yet ported, and draws the resting spin; "as `default`" is a variant that draws its look's default motion. Write the look you mean anyway: it starts drawing as itself when it is ported.

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.Material
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.compose.LumosOrb
import link.socket.phosphor.palette.AtmospherePresets

@Composable
fun WorkingOrb(working: Boolean) {
    val source = remember { LiveFrameSource(AtmospherePresets.THINKING) }

    // A look is how the cells move; lookTo blends to it. LaunchedEffect runs on the UI thread,
    // the thread LumosOrb steps the source on, so the builder is never touched from two threads.
    LaunchedEffect(working) {
        source.builder.lookTo(if (working) Look(LumosLook.WORKING) else Look(LumosLook.BASE))
    }

    LumosOrb(
        source = source,
        config =
            LumosConfig(
                style = SurfaceStyle.Dots,
                material = Material.Neon(glow = 0.5f),
                label = if (working) "Lumos is working" else "Lumos is thinking",
            ),
        modifier = Modifier.size(160.dp),
    )
}
```

## Agent state to look

`CognitiveLookSwitch.DEFAULT` is the table that picks a look from an agent's PROPEL phase (`link.socket.phosphor.signal.CognitivePhase`) and activity (`AgentActivityState`). `SignalGenerator` installs it; see Driving the orb from an agent's state.

| Phase | Activity | Look | Glyph on arrival |
|---|---|---|---|
| `PERCEIVE` | any | `SEARCHING` | — |
| `RECALL` | any | `REASONING` | — |
| `OBSERVE` | any | `SEARCHING` · `lighthouse` | — |
| `PLAN` | any | `REASONING` · `twins` | — |
| `EXECUTE` | any | `WORKING` | — |
| `LEARN` | any | `COMPACTING` · `fuse` | — |
| `LOOP` | any | `BASE` | — |
| `NONE` | `IDLE` | `BASE` | — |
| `NONE` | `PROCESSING` | `WORKING` · `gyro` | — |
| any | `SPAWNING` | `BASE` | — |
| any | `COMPLETE` | `BASE` | `CHECK` |

Precedence, highest first:

1. Escalated, whatever the table says: `WAITING`, with `QUESTION`.
2. Unattended (`LumosRenderConfig.unattended`, an orb nobody is watching): the look becomes `BACKGROUND` · `spiral`, keeping the row's glyph.
3. The table, most specific key first: phase and activity, then activity alone, then phase alone, then `BASE` with no glyph.

So an activity-only row is a lifecycle override that beats any phase, and a phase-only row holds
across activities. Pass your own map to `CognitiveLookSwitch(table = ...)` to change a row: a host
that fires its own CHECK on completion drops that row's glyph.

## Atmospheres

What colours the orb and how its colour moves: `AtmosphereState`, in `link.socket.phosphor.signal`. `AtmospherePresets` (in `link.socket.phosphor.palette`) holds the five a host normally uses, by constant (`AtmospherePresets.THINKING`) or by name (`AtmospherePresets.byName("thinking")`). Start a live source on one with `LiveFrameSource(AtmospherePresets.THINKING)`.

| Preset | Use for | Hues | Pattern | Pattern speed | Spin (y, x) | Bipolar | Resolution |
|---|---|---|---|---|---|---|---|
| `idle` | Nothing in flight. | 250° → 175° | `LONGITUDE` | 0.25 | 0.14, 0 | 0 | 10 (4,801 cells) |
| `listening` | Taking input: the person is speaking or typing. | 195° → 270° | `PLASMA` | 1.15 | 0.16, 0.1 | 0 | 10 (4,801 cells) |
| `thinking` | Working on it. | 244° → 185° | `SPIRAL` | 0.7 | 0.45, 0.1 | 0 | 10 (4,801 cells) |
| `uncertain` | Unsure, or waiting on a human: two hues held apart. | 32° → 280° | `SPIRAL` | 1 | 0.1, 0.1 | 0.45 | 10 (4,801 cells) |
| `ready` | Done, with something to show. | 249° → 197° | `PULSE` | 1.3 | 0.2, -0.2 | 0 | 10 (4,801 cells) |

Bipolar above 0 holds the two hues apart as two poles instead of blending them. Every preset's other fields are in `catalog.json`. Patterns:

| Pattern | Meaning |
|---|---|
| `LONGITUDE` | Bands around the vertical axis. |
| `LATITUDE` | Bands around the polar axis. |
| `SPIRAL` | A combined sweep of both angles. |
| `SCAN` | A sweep along the vertical axis. |
| `PLASMA` | Three-axis noise. |
| `PULSE` | Rings out from the centre. |
| `SOLID` | No variation. |

Change the atmosphere with `source.runtime.setAtmospherePreset("ready")` or `source.runtime.setAtmosphere(state)`: it blends from wherever it is, over a duration the preset pair sets. Pass `AtmosphereTransitionSpec.Immediate` (`link.socket.phosphor.choreography`) as `spec` to snap.

## Glyphs

A one-shot mark carved into the cells and overlaid at the centre, fading in and out over its window: `LumosGlyph`. Fire one with `source.builder.queueGlyph(LumosGlyph.CHECK)` (1.5 s by default); it replaces any glyph showing. Under the look switch, a row's glyph fires by itself when the row is reached.

| Glyph | Meaning | Accent (HSL) |
|---|---|---|
| `CHECK` | Done: a task completed. | 145°, 65 %, 55 % |
| `EXCLAIM` | Attention required: a warning. | 32°, 95 %, 55 % |
| `QUESTION` | Uncertain, or needs a human: fired when an agent escalates. | 280°, 70 %, 60 % |
| `HEART` | Affirmation, or a preference noted. | 340°, 80 %, 60 % |
| `STAR` | An achievement or milestone. | 50°, 95 %, 65 % |
| `LIGHTNING` | Active execution: a spark. | 244°, 85 %, 60 % |

---

Generated from the Lumos catalogues by `./gradlew :phosphor-lumos:generateAgentDocs`. Do not edit by hand.
