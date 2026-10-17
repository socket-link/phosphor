<div align="center">

# Phosphor

**Turn invisible cognition into visible terminal light.**

[![Maven Central](https://img.shields.io/maven-central/v/link.socket/phosphor-core)](https://central.sonatype.com/artifact/link.socket/phosphor-core)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Build](https://github.com/socket-link/phosphor/actions/workflows/ci.yml/badge.svg)](https://github.com/socket-link/phosphor/actions/workflows/ci.yml)

</div>

Phosphor is a Kotlin Multiplatform rendering library that transduces cognitive signals into ASCII luminance, color ramps, particle physics, and 3D waveform surfaces. The same pipeline drives every output — from ANSI escape codes to Compose Canvas.

Just as CRT phosphor converts electron energy into visible light, this library converts an AI agent's internal state into something you can see: sparse floating dots coalescing into dense bright strokes as cognition shifts from perception to execution.

Phosphor ships `:phosphor-core` alongside Lumos sibling modules. `:phosphor-lumos` is the framework-free voxel-orb companion visualization for cognitive state; `:phosphor-lumos-cli` is the JVM terminal surface module for projected Lumos frame data.

---

## The Pipeline

Signals flow through seven strict layers. Each layer depends only on the one below it.

```
Signal → Field → Palette → Render → Choreography → Emitter → Surface Adapter
```

| Layer | Package | What it does |
|-------|---------|-------------|
| **Signal** | `signal/` | Cognitive phase and agent state — what the brain is doing, independent of rendering |
| **Field** | `field/` | Particle physics, flow fields, and substrate density — the animation engine |
| **Palette** | `palette/` | Maps luminance (0.0–1.0) to characters and ANSI 256-color codes |
| **Render** | `render/` | Assembles cells into buffers with 3D projection and surface lighting |
| **Choreography** | `choreography/` | Translates phase transitions into particle effects and substrate behavior |
| **Emitter** | `emitter/` | Transient effects — spark bursts, height pulses, turbulence — that decay naturally |
| **Bridge** | `bridge/` | Connects runtime cognitive state to the animation field |

`phosphor-core` has zero UI framework dependencies. Platform-specific rendering belongs in separate surface modules; `:phosphor-lumos` is the first concrete sibling module and establishes the boundary for framework-free voxel-orb frame data.

## Modules

| Module | Purpose |
|--------|---------|
| `:phosphor-core` | Shared rendering pipeline — signals, fields, palettes, cells, choreography, emitters, and runtime bridges |
| `:phosphor-lumos` | Voxel-orb companion visualization module for cognitive state — builds every `VoxelFrame`, defines the `LumosRenderer` contract, and holds what every pixel surface shares: the `LumosCanvasFrame` DTO, the `PixelLattice` projection and the `OrbStepper` clock, with no UI framework dependency |
| `:phosphor-lumos-cli` | JVM terminal frame DTOs, the `CliLattice` projection, and the `CliOrb` ANSI renderer for Lumos voxel frames |
| `:phosphor-lumos-compose` | Compose Multiplatform Canvas rendering surface (`LumosCanvas`, `LumosOrb`) for projected Lumos voxel frames |
| `:phosphor-lumos-surface` | Compose Multiplatform surface (`LumosSurface`, `Modifier.lumosTarget`) on which the orb's cells assemble into a real layout element and hand off to it |
| `:phosphor-lumos-dom` | Kotlin/JS DOM/SVG surface for web pages without Compose: an ES module whose `mount(svg, options)` draws a live orb into an `<svg>` |
| `:phosphor-trace` | Recorded `VoxelTrace` format, capture, codec, and playback for Lumos voxel frames |

---

## What It Looks Like

Each cognitive phase has a distinct visual texture. The eye reads the shift instantly — no labels needed.

| Phase | Cell Feel | Color Feel |
|-------|-----------|------------|
| PERCEIVE | Sparse, circular, floating (` ·∙.:∘○◌◯`) | Cool blues → white |
| RECALL | Warm, solid, crystallizing (` .·*✦⬡◆●`) | Dark amber → warm gold |
| OBSERVE | Sparse, scanning, circular (` ·∙.·:∘○◌◯`) | Cool blues → white |
| PLAN | Structured, branching, grid-like (` ░▒▓│├┤┬┴┼`) | Teal → cyan |
| EXECUTE | Dense, aggressive, lightning (` .:;+=*#%@█⚡`) | Red → yellow → white |
| LEARN | Diffuse, reflective, fading (` .·:*∘○◌`) | Purple → dim lavender |

---

## Quick Start

> **Prerequisites:** Java 21+

Add the dependency, using the version shown in the Maven Central badge above:

```kotlin
// build.gradle.kts
dependencies {
    implementation("link.socket:phosphor-core:<version>")
}
```

Build a frame:

```kotlin
import link.socket.phosphor.choreography.AgentLayer
import link.socket.phosphor.choreography.CognitiveChoreographer
import link.socket.phosphor.field.ParticleSystem
import link.socket.phosphor.field.SubstrateAnimator
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.palette.AsciiLuminancePalette
import link.socket.phosphor.palette.CognitiveColorRamp
import link.socket.phosphor.render.AsciiCell
import link.socket.phosphor.render.CellBuffer
import link.socket.phosphor.signal.AgentVisualState
import link.socket.phosphor.signal.CognitivePhase

// 1. Define an agent's visible state and add it to a layer
val agent =
    AgentVisualState(
        id = "agent-1",
        name = "Atlas",
        role = "reasoning",
        position = Vector2(40f, 12f),
        cognitivePhase = CognitivePhase.EXECUTE,
    )
val agentLayer = AgentLayer(width = 80, height = 24)
agentLayer.addAgent(agent)

// 2. Create the animation field
val particles = ParticleSystem(maxParticles = 500)
val substrate = SubstrateState.create(width = 80, height = 24)

// 3. Let the choreographer respond to phase transitions
val choreographer = CognitiveChoreographer(particles, SubstrateAnimator())
val nextSubstrate = choreographer.update(agents = agentLayer, substrate = substrate, deltaTime = 0.016f)

// 4. Map luminance to cells and write them to a cell buffer
val palette = AsciiLuminancePalette.EXECUTE
val colorRamp = CognitiveColorRamp.forPhase(CognitivePhase.EXECUTE)
val buffer = CellBuffer(width = 80, height = 24)
for (row in 0 until buffer.height) {
    for (col in 0 until buffer.width) {
        buffer[row, col] =
            AsciiCell.fromSurfaceDithered(
                luminance = 0.8f,
                normalX = 0.5f, normalY = 0.2f,
                screenX = col, screenY = row,
                palette = palette,
                colorRamp = colorRamp,
            )
    }
}

// 5. Pass the buffer to your surface adapter
```

The snippet above is compiled by `ReadmeQuickStartTest`; change one and the test fails until the other follows.

Phase transitions trigger effects automatically — spark bursts, substrate ripples, particle acceleration — all from the cognitive signal alone.

### A Lumos orb in Compose

`:phosphor-lumos-compose` draws Lumos, the voxel orb. `LumosOrb` owns its clock, so a screen hands it a source and a config and runs no loop of its own:

```kotlin
// build.gradle.kts
dependencies {
    implementation("link.socket:phosphor-lumos-compose:<version>")
}
```

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

The orb stops while it is scrolled off screen or its window is in the background, holds still when the platform asks for reduced motion, and is one labelled image to a screen reader — or hidden from one, without a label. Orbs in the same state share a clock, so a column of them breathes together. To drive a runtime and frame builder you already own, wrap them in `LiveFrameSource(runtime, builder)`; to play a recording from `:phosphor-trace`, open it with `asFrameSource()`. The snippet is compiled by `ReadmeLumosQuickStartTest`.

### The orb becomes a control

`:phosphor-lumos-surface` lets the orb's cells leave their slot, fly to a real layout element and become it, with the element keeping focus, click and semantics throughout and nothing ticking once it is solid:

```kotlin
val state = remember { LumosSurfaceState(LiveFrameSource(AtmospherePresets.IDLE)) }

LumosSurface(state, Modifier.fillMaxSize()) {
    // The orb's slot: LumosOrb draws here until the cells leave
    LumosSurfaceOrb(state, Modifier.align(Alignment.TopCenter).size(128.dp))

    // The control. Laid out and clickable from the start, invisible until the cells become it
    Button(onClick = onTurnOn, modifier = Modifier.align(Alignment.BottomEnd).lumosTarget("turn-on", cornerRadiusPx = 8f)) {
        Text("Turn on")
    }
}

// Later, from anywhere:
state.assembleInto("turn-on", tint = accent)
```

The cells burst, flow to the control's rounded rectangle and fade as it rises; arrival is measured on screen, in pixels, not read from the spring. Under reduced motion the control appears at once and no cell flies. Live only: a layout target cannot be recorded to a `.vxt`.

### A Lumos orb on a web page

`:phosphor-lumos-dom` draws the same orb into an `<svg>` on any page — plain HTML, React, anything that can hand over an element — with no Compose, no Skia and no npm package. It is Kotlin/JS, built as one ES module:

```bash
./gradlew :phosphor-lumos-dom:jsBrowserDistribution
# phosphor-lumos-dom/build/dist/js/productionExecutable/phosphor-lumos-dom.mjs
```

```html
<svg id="orb" style="width: 160px; height: 160px"></svg>
<script type="module">
  import { mount } from './phosphor-lumos-dom.mjs';

  const orb = mount(document.getElementById('orb'), {
    style: 'dots',             // 'squares' (default), 'dots' or 'dashes'
    atmosphere: 'thinking',    // 'idle' (default), 'listening', 'thinking', 'uncertain', 'ready'
    label: 'Lumos is thinking',
  });
  orb.setAtmosphere('ready');  // carries on from where it is
  orb.setLook('working');      // blends over 0.6 s
  // orb.pause(), orb.resume(), orb.stats(), orb.unmount()
</script>
```

In React, mount in an effect and unmount in its cleanup:

```jsx
import { useEffect, useRef } from 'react';
import { mount } from './phosphor-lumos-dom.mjs';

export function LumosOrb({ atmosphere = 'idle', label, ...options }) {
  const svg = useRef(null);
  const orb = useRef(null);
  useEffect(() => {
    orb.current = mount(svg.current, { ...options, atmosphere, label });
    return () => orb.current.unmount();
  }, []);
  useEffect(() => { orb.current?.setAtmosphere(atmosphere); }, [atmosphere]);
  return <svg ref={svg} style={{ width: 160, height: 160 }} />;
}
```

The orb follows the `<svg>`'s CSS size. It stops while scrolled out of view or while the page is hidden, holds still under `prefers-reduced-motion` or `reduceMotion: true`, and is one labelled image to a screen reader with a `label`, or hidden from one without. `tint: 'currentColor'` paints every cell in the CSS `color` of the `<svg>`, so an inline orb takes the colour of the text around it. Other options: `look`, `resolution`, `speed`, `dotSize`, `maxFps` (60 by default), `halo`, `glyph` and `paused`.

**Resolution.** Every cell is one SVG element. A preset's resolution of 10 is 4,801 cells, which holds 60 Hz in Chrome; for an orb a few tens of pixels across use `resolution: 4` or less. At 20 px and resolution 10, three quarters of the cells sit on the 0.45 px floor and the page does sixty times the work for nothing.

The distribution also carries two example pages beside the module: `index.html`, every style at 200 px and 20 px, and `react.html`. Serve the directory over HTTP to open them; an ES module does not load from `file://`.


### For coding agents

[`docs/lumos/llms.txt`](docs/lumos/llms.txt) is one page an agent can read to integrate Lumos: every form, look, atmosphere preset, surface style, material and glyph, the `LumosConfig` table with defaults and constraints, the agent-state-to-look table, and compiled examples. The same content is an installable agent skill:

```bash
npx skills add socket-link/phosphor --skill lumos
```

Both are generated from the code by `./gradlew :phosphor-lumos:generateAgentDocs`, and CI fails when they fall behind it.

---

## Metadata-Driven Emitters

Emitter instances can carry generic scalar metadata that effects consume at render time.

```kotlin
emitters.emit(
    effect = EmitterEffect.SparkBurst(),
    position = Vector3.ZERO,
    metadata = mapOf(
        MetadataKeys.INTENSITY to 1.4f,
        MetadataKeys.HEAT to 0.85f,
    ),
)
```

`SparkBurst` uses these values to scale brightness and expansion while preserving the same base effect shape. See [docs/METADATA_GUIDE.md](docs/METADATA_GUIDE.md) for the built-in keys and integration pattern.

---

## Platforms

Phosphor compiles to:

- **JVM** (Java 21)
- **Android** (minSdk 24 — `:phosphor-core`, `:phosphor-lumos`, `:phosphor-lumos-compose` and `:phosphor-lumos-surface`)
- **JavaScript** (Kotlin/JS IR)
- **WebAssembly** (Kotlin/WASM)
- **iOS** (x64, ARM64, Simulator ARM64 — packaged as XCFrameworks)

The `:phosphor-lumos-cli` module is JVM-only by design; non-terminal Lumos surfaces live in separate sibling modules.

`:phosphor-lumos-dom` is JavaScript-only by design: the same adapter from WebAssembly is two to three times slower, because every attribute write crosses the wasm/JS boundary as a string.

`:phosphor-trace` encodes only on the JVM and iOS. On JavaScript and WebAssembly it decodes and plays back `.vxt` traces but `TraceCodec.encode` throws, so encode on the JVM and ship the file to the browser.

---

## Building

```bash
./gradlew build                    # Build all platforms
./gradlew jvmTest                  # Run JVM tests (fastest gate)
./gradlew allTests                 # Run tests on all platforms
./gradlew ktlintFormat             # Auto-format code
./gradlew dokkaGeneratePublicationHtml  # Generate API documentation
```

Building the Android target needs an Android SDK: set `ANDROID_HOME`, or `sdk.dir` in `local.properties`.

---

## Design Principles

- **Physics over animation.** Define forces; let particles respond. No keyframe choreography.
- **Luminance is the primary channel.** Brightness encodes activity before color or motion.
- **Depth through projection.** Proper 3D camera-to-2D projection with surface normals — not decorative box-drawing.
- **Transient effects decay naturally.** An EmitterEffect fires once and fades without cleanup code.
- **Layer discipline is sacred.** Strict unidirectional flow prevents coupling between stages.
- **Pure core.** Zero UI framework dependencies. Platform-specific rendering belongs in surface adapters.

---

## Part of Ampere

Phosphor is the rendering engine extracted from [Ampere](https://github.com/socket-link/ampere), where it drives the real-time cognitive visualization of multi-agent coordination. Phosphor makes the invisible visible; Ampere gives it something to observe.

---

## License

Apache 2.0 — see [LICENSE.txt](LICENSE.txt) for details.

Copyright 2026 Miley Chandonnet, Stedfast Softworks LLC
