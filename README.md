<div align="center">

# Phosphor

**Turn invisible cognition into visible terminal light.**

[![Maven Central](https://img.shields.io/maven-central/v/link.socket/phosphor-core)](https://central.sonatype.com/artifact/link.socket/phosphor-core)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Build](https://github.com/socket-link/phosphor/actions/workflows/ci.yml/badge.svg)](https://github.com/socket-link/phosphor/actions/workflows/ci.yml)

</div>

Phosphor is a Kotlin Multiplatform rendering library that transduces cognitive signals into ASCII luminance, color ramps, particle physics, and 3D waveform surfaces. The same pipeline drives every output — from ANSI escape codes to Compose Canvas.

Just as CRT phosphor converts electron energy into visible light, this library converts an AI agent's internal state into something you can see: sparse floating dots coalescing into dense bright strokes as cognition shifts from perception to execution.

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

`phosphor-core` has zero UI framework dependencies. Surface adapters (phosphor-mosaic, phosphor-compose, phosphor-ansi) live in separate modules.

---

## What It Looks Like

Each cognitive phase has a distinct visual texture. The eye reads the shift instantly — no labels needed.

| Phase | Cell Feel | Color Feel |
|-------|-----------|------------|
| PERCEIVE | Sparse, circular, floating (` ·∙.:∘○◌◯`) | Cool blues → white |
| RECALL | Warm, solid, crystallizing (` .·*✦⬡◆●`) | Dark amber → warm gold |
| PLAN | Structured, branching, grid-like (` ░▒▓│├┤┬┴┼`) | Teal → cyan |
| EXECUTE | Dense, aggressive, lightning (` .:;+=*#%@█⚡`) | Red → yellow → white |
| EVALUATE | Diffuse, reflective, fading (` .·:*∘○◌`) | Purple → dim lavender |

---

## Quick Start

> **Prerequisites:** Java 21+

Add the dependency:

```kotlin
// build.gradle.kts
dependencies {
    implementation("link.socket:phosphor-core:0.2.2")
}
```

Build a frame:

```kotlin
// 1. Define an agent's visible state
val agent = AgentVisualState(
    id = "agent-1",
    position = Vector2(40f, 12f),
    cognitivePhase = CognitivePhase.EXECUTE
)

// 2. Create the animation field
val particles = ParticleSystem(maxParticles = 500)
val substrate = SubstrateState.create(width = 80, height = 24)

// 3. Let the choreographer respond to phase transitions
val choreographer = CognitiveChoreographer(particles, substrateAnimator)
choreographer.update(agentLayer, substrate, deltaTime = 0.016f)

// 4. Map luminance to cells
val palette = AsciiLuminancePalette.EXECUTE
val colorRamp = CognitiveColorRamp.forPhase(CognitivePhase.EXECUTE)
val cell = AsciiCell.fromSurfaceDithered(
    luminance = 0.8f,
    normalX = 0.5f, normalY = 0.2f,
    screenX = col, screenY = row,
    palette = palette,
    colorRamp = colorRamp
)

// 5. Write to a cell buffer and pass to your surface adapter
val buffer = CellBuffer(width = 80, height = 24)
buffer[row, col] = cell
```

Phase transitions trigger effects automatically — spark bursts, substrate ripples, particle acceleration — all from the cognitive signal alone.

---

## Platforms

Phosphor compiles to:

- **JVM** (Java 21)
- **JavaScript** (Kotlin/JS IR)
- **WebAssembly** (Kotlin/WASM)
- **iOS** (x64, ARM64, Simulator ARM64 — packaged as XCFrameworks)

---

## Building

```bash
./gradlew build                    # Build all platforms
./gradlew jvmTest                  # Run JVM tests (fastest gate)
./gradlew allTests                 # Run tests on all platforms
./gradlew ktlintFormat             # Auto-format code
./gradlew dokkaHtml                # Generate API documentation
```

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
