# AGENTS.md

Phosphor is a Kotlin Multiplatform rendering library that translates cognitive state into visible light — ASCII luminance, color ramps, particle physics, 3D waveform surfaces, and the Lumos voxel-orb companion visualization with Compose Canvas support. Read [SOUL.md](SOUL.md) for project philosophy and values.

## Development Commands

### Build & Test
```bash
./gradlew build                        # Build the project
./gradlew allTests                     # Run all tests
./gradlew jvmTest                      # Run JVM-specific tests (fastest, primary gate)
./gradlew iosSimulatorArm64Test        # Run iOS tests (Apple Silicon simulator)
./gradlew wasmJsBrowserTest            # Run WASM browser tests
```

### Code Quality
```bash
./gradlew ktlintFormat                 # Auto-format code
./gradlew ktlintCheck                  # Check formatting
./gradlew dokkaGeneratePublicationHtml # Generate API documentation
```

### Release
```bash
scripts/release-preflight.sh 0.7.0     # Gate a release, on the commit you intend to tag
scripts/bump-version.sh 0.8.0          # Close out 0.7.0 and open the next cycle — AFTER the tag
./gradlew publishToMavenLocal          # Publish to local Maven for integration testing
```

Publishing is one action — pushing a `v*` tag — and Maven Central is permanent.
Work through [docs/RELEASING.md](docs/RELEASING.md) in order; never skip the
pre-flight, which covers what PR CI does not. `main` always carries the *next*,
unreleased version, so the number to release is the one already in
`gradle.properties` and the tag goes on a commit from before the next bump.
Never bump to release.

## Changelog

`CHANGELOG.md` is hand-written and it is the release's written record — GitHub's
generated notes are commit subjects, which do not explain a behavior change to
someone upgrading. Add an entry under `## [Unreleased]` for anything a consumer
can observe: new API, a changed default, a moved convention, a fixed defect.
Match the depth of what is already there — a `####` subsection naming the ticket
and PR, then prose that says what the output does differently now.

Internal refactors, test-only changes and doc edits do not need an entry.

Never add a version heading yourself. The close-out PR after a tag runs
`scripts/bump-version.sh`, which rolls `[Unreleased]` into a section dated from
the tag. Writing one by hand attributes entries to a version by guess, and the
pre-flight reads those headings to tell whether the last release was closed
out.

## Architecture at a Glance

Phosphor is a layered rendering pipeline: cognitive signals flow in, visible characters flow out. Each layer depends only on the layer below it — signal code never imports from palette, field code never imports from render.

| Layer | Location | Purpose |
|-------|----------|---------|
| Signal | `signal/` | CognitiveSignal data model — what the brain is doing, no knowledge of pixels or characters |
| Field | `field/` | ParticleField, SubstrateState, FlowLayer — animation physics at a fixed timestep |
| Palette | `palette/` | AsciiLuminancePalette, CognitiveColorRamp — mapping brightness and phase to characters and colors |
| Render | `render/` | AsciiCell, CellBuffer, 3D projection, waveform surface generation |
| Choreography | `choreography/` | CognitiveChoreographer — maps cognitive phases to particle behaviors and visual textures |
| Emitter | `emitter/` | EmitterEffect sealed hierarchy — transient visual events (SparkBurst, HeightPulse, Turbulence, ColorWash) |
| Bridge | `bridge/` | Adapters connecting live runtime state to the animation field |

### Active Modules

| Module | Purpose |
|--------|---------|
| `phosphor-core/` | Shared rendering pipeline — signals, fields, palettes, cells, choreography, emitters, and runtime bridges |
| `phosphor-lumos/` | Framework-free voxel-orb companion visualization for cognitive state, built as a sibling output module over `phosphor-core` |
| `phosphor-lumos-cli/` | JVM terminal DTO and future CLI renderer surface for projected Lumos voxel frames |
| `phosphor-lumos-compose/` | Compose Multiplatform DTO and Canvas rendering surface for projected Lumos voxel frames |

### Future / Aspirational Modules

| Module | Purpose |
|--------|---------|
| `phosphor-mosaic/` | Mosaic (Compose-for-terminal) adapter — AnnotatedString output |
| `phosphor-compose/` | Compose Multiplatform Canvas adapter — DrawScope rendering |
| `phosphor-ansi/` | Raw ANSI escape code output for non-Compose terminals |
| `phosphor-demo/` | Desktop demo app (Compose Desktop) |
| `phosphor-demo-cli/` | Terminal demo app |

## Key Paths

| Module | Purpose |
|--------|---------|
| `phosphor-core/src/commonMain/` | Shared rendering pipeline — signals, fields, palettes, cells |
| `phosphor-core/src/commonTest/` | Unit tests for palette mapping, projection math, particle physics |
| `phosphor-lumos/src/commonMain/` | Lumos module boundary for framework-free voxel-orb visualization data |
| `phosphor-lumos/src/commonTest/` | Lumos smoke tests and future voxel-frame API tests |
| `phosphor-lumos-cli/src/commonMain/` | JVM CLI-facing Lumos terminal frame DTOs |
| `phosphor-lumos-cli/src/commonTest/` | CLI DTO serialization and shape-contract tests |
| `phosphor-lumos-compose/src/commonMain/` | Compose Multiplatform Canvas-facing Lumos frame DTOs |
| `phosphor-lumos-compose/src/commonTest/` | Compose DTO serialization and shape-contract tests |

## Before You Change Anything

- Read the file you're modifying. Read its tests.
- Understand the layer boundary you're in. The pipeline flows strictly downward: Signal → Field → Palette → Render → Surface Adapter.
- If touching particle physics (`field/`), run the demo to visually verify — unit tests alone can't catch motion regressions.
- If adding a new EmitterEffect, follow the sealed class pattern in `emitter/EmitterEffect.kt`.

## Kotlin Conventions

- Sealed classes/interfaces for closed type hierarchies (EmitterEffect, CognitivePhase, RenderTarget)
- Data classes for immutable value types (AsciiCell, CognitiveColorRamp, Particle)
- `expect`/`actual` for platform-specific rendering only — core math stays in `commonMain`
- Pure functions wherever possible; animation state is the only mutable concern
- ktlint with IntelliJ IDEA code style (auto-format with `./gradlew ktlintFormat`)

## Rendering Pipeline Rules

- Every `AsciiCell` must be constructible from `luminance + normal + palette + colorRamp` — no shortcut paths that bypass the palette system
- Particle physics runs at a fixed timestep (`update(dt: Float)`) independent of framerate
- EmitterEffects are transient — they fire once and decay. Persistent visual states belong in `field/`
- Character ramps must be ordered darkest-to-brightest. Unit tests enforce this invariant
- Color ramps must have at least 5 stops for smooth interpolation
- 3D projection must be configurable (camera position, field of view) — never hardcode perspective
- CellBuffer is the universal interchange format between rendering logic and surface adapters

## Testing

- **Primary gate:** `./gradlew jvmTest` — run this after every change
- Test luminance mapping boundaries: `charForLuminance(0.0f)` returns space, `charForLuminance(1.0f)` returns the brightest character
- Test color ramp interpolation at 0.0, 0.5, and 1.0
- Test particle spawn/decay lifecycle to completion
- Test 3D projection against known camera→screen coordinate pairs
- Visual regression tests: render a known CellBuffer to string and compare against golden files in `testdata/`

## Safety Boundaries

- **Never** add UI framework dependencies to `phosphor-core` — it must remain pure Kotlin
- **Never** hardcode terminal dimensions — always accept width/height parameters
- **Never** assume ANSI color support — surface adapters handle capability detection
- **Never** commit binary test assets without approval
- **Never** push a `v*` tag without `scripts/release-preflight.sh` passing on that exact commit — Maven Central is permanent
- **Always** run `./gradlew jvmTest` before considering work complete
- **Always** run `./gradlew ktlintFormat` before committing
- **Always** add a `CHANGELOG.md` entry under `[Unreleased]` for a consumer-visible change
- **Always** verify demo output visually when changing palette or projection code

## Permissions

- **File read:** any file in the repository
- **File write:** `src/`, `test/`, `docs/`, config files
- **Git:** branch creation, commits (with review)
- **Build/test:** full access to `./gradlew` commands
- **External:** no network requests — this is an offline rendering library
