---
name: lumos
description: >-
  Integrate Phosphor Lumos, the Kotlin Multiplatform voxel orb that shows what an AI agent is
  doing, into an app: LumosOrb for Compose Multiplatform, LumosConfig, forms, looks, atmosphere
  presets, surface styles, materials, glyphs, the phase-to-look table and trace replay. Use when
  adding a Lumos orb, choosing how it looks for an agent's state, or playing a .vxt recording.
---

# Lumos

> Lumos is a voxel orb that shows what an AI agent is doing (thinking, searching, working, waiting on a person) as light. It ships as Kotlin Multiplatform libraries: `LumosOrb` for Compose Multiplatform, a terminal renderer, and a recorded-trace format for baked clips.

An orb is a sphere of cells, from a few dozen to a few thousand. Three independent things decide what
it shows. The **atmosphere** colours it and sets its pattern and spin (`AtmospherePresets`). The
**look** decides how its cells move for a cognitive moment (`LumosLook`). The **form** decides where
its cells rest (`Forms`). A **glyph** is a one-shot mark carved into the cells, such as a check on
completion. On screen, a **surface style** decides the mark each cell leaves and a **material** how
that mark takes the light; both are fields of `LumosConfig`.

`LumosOrb` is the driver. It owns the clock, steps a `LumosFrameSource` once a frame, and draws the
frame it returns. A source is a live scene (`LiveFrameSource`, or `SignalGenerator` from
`:phosphor-trace` when an agent's state drives it) or a recording (`LumosSource.Trace`). Classes are
in `link.socket.phosphor.lumos` unless an import says otherwise.

## Install

```kotlin
// build.gradle.kts. <version> is the latest release on Maven Central.
dependencies {
    implementation("link.socket:phosphor-lumos-compose:<version>") // LumosOrb, LumosCanvas
    implementation("link.socket:phosphor-trace:<version>") // SignalGenerator, LumosSource.Trace; optional
}
```

| Artifact | What it holds |
|---|---|
| `phosphor-lumos` | The framework-free orb: `VoxelFrameBuilder`, `LiveFrameSource`, forms, looks, `LumosConfig`. A dependency of the others. |
| `phosphor-lumos-compose` | `LumosOrb`, the driver, and `LumosCanvas`, which draws one projected frame. |
| `phosphor-trace` | `SignalGenerator` (an agent's state drives the orb), recordings (`VoxelTrace`, `TraceCodec`) and `LumosSource`. |
| `phosphor-lumos-cli` | `CliOrb`, the same orb as ANSI in a JVM terminal. |

## A minimal orb

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.compose.LumosOrb
import link.socket.phosphor.palette.AtmospherePresets

@Composable
fun ThinkingOrb() {
    // 1. A live scene with nothing in it but an atmosphere
    val source = remember { LiveFrameSource(AtmospherePresets.THINKING) }

    // 2. The orb owns the clock: speed, pause, reduced motion and the label are config
    LumosOrb(
        source = source,
        config = LumosConfig(label = "Lumos is thinking"),
        modifier = Modifier.size(160.dp),
    )

    // 3. Change what it shows from the UI thread, and it carries on from where it is:
    //    source.runtime.setAtmospherePreset("ready")
}
```

`LumosOrb` stops while it is scrolled off screen or its window is in the background, holds still when the platform asks for reduced motion, and is one labelled image to a screen reader, or hidden from one without a `label`. Orbs on the same look and atmosphere share a clock, so a column of them breathes together.

## References

| To | Read |
|---|---|
| Pick where the cells rest, size a small orb, or reshape it | [references/forms.md](references/forms.md) |
| Choose how the orb moves and what colours it for an agent's state, and fire glyphs | [references/looks.md](references/looks.md) |
| Configure `LumosOrb`: `LumosConfig`, surface styles, materials | [references/surface.md](references/surface.md) |
| Change the orb over time, drive it from an agent's state, replay a recording | [references/sequence.md](references/sequence.md) |
| Read every catalogue as data | [catalog.json](catalog.json) |

## Do not

- Do not make a source in composition without `remember`. A new source restarts the orb.
- Do not step one source from two `LumosOrb`s. It runs twice as fast. To show one orb twice, project its frame once and draw it with two `LumosCanvas`es.
- Do not touch a source, its `builder` or its `runtime` off the UI thread. They are single-threaded and `LumosOrb` steps them on the UI thread, so change them from `LaunchedEffect`, `SideEffect` or a UI callback.
- Do not leave `LumosOrb` unsized. Give it an explicit, square size.
- Do not set `builder.look` or `builder.form` to change a running orb. Both cut. Use `lookTo` and `morphTo`.
- Do not change the cell count or the atmosphere's resolution while a morph is in flight. Either is a cut, and the cells lose their identity.
- Do not put a small orb on the default lattice. At 20 px a resolution-10 lattice is 4,801 cells, most below the 0.45 px floor. Use `Forms.Fibonacci.counted(Forms.inlineOrbCount(sizePx))`, or on a `SignalGenerator` `generator.morphTo(Forms.Fibonacci, count = Forms.inlineOrbCount(sizePx), durationSeconds = 0f)`.
- Do not try to turn motion back on under the platform's reduced-motion setting. `reducedMotion = false` cannot overrule it.
- Do not pass `speed` outside 0.1..3, or `density` or `dotSize` at or below 0. `LumosConfig` throws.
- Do not expect `Material.Metal` or `Material.Glass` to draw yet. They draw `Material.Flat`.
- Do not drive a `SignalGenerator` through `asFrameSource()` and `frames()` at once. Its scene steps twice.
- Do not leave the orb without a `label` when it carries meaning. Without one, screen readers skip it.

---

Generated from the Lumos catalogues by `./gradlew :phosphor-lumos:generateAgentDocs`. Do not edit by hand.
