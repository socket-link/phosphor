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
scripts/bump-version.sh 0.8.0          # Move main to the next version — AFTER the v0.7.0 tag
./gradlew publishToMavenLocal          # Publish to local Maven for integration testing
```

Publishing is one action — pushing a `v*` tag — and Maven Central is permanent.
Work through [docs/RELEASING.md](docs/RELEASING.md) in order; never skip the
pre-flight, which covers what PR CI does not. `main` always carries the *next*,
unreleased version, so the number to release is the one already in
`gradle.properties` and the tag goes on a commit from before the next bump.
Never bump to release.

## Changelog

There is no `CHANGELOG.md`. Do not create one, and do not add a changelog entry
as part of a PR — every parallel PR editing the same "Unreleased" section
guarantees merge conflicts and resets CI on unrelated branches. Release notes
are generated at release time from commit/PR history instead.

## Dependencies and the Yarn Lockfiles

`phosphor-core`, `phosphor-lumos` and `phosphor-lumos-compose` have JS and Wasm
targets, so the npm packages that Kotlin dependencies bring in (kotlinx-datetime's
`@js-joda/core`, Compose's webpack and Skiko tooling) are pinned in two committed lockfiles: `kotlin-js-store/yarn.lock` (JS) and
`kotlin-js-store/wasm/yarn.lock` (Wasm). CI's Web job fails before any test runs
when either one is out of date:

```
Execution failed for task ':kotlinStoreYarnLock'.
> Lock file was changed. Run the `kotlinUpgradeYarnLock` task to actualize lock file
```

That breaks main for every open branch, not only yours. #160 removed
kotlinx-datetime without regenerating the lockfiles, got merged on a red Web job,
and every PR after it inherited the failure.

- If you add, remove, or bump a dependency (any `build.gradle.kts` dependency
  block, a plugin version, or the Kotlin or Compose version), regenerate
  both lockfiles in the same PR and commit what changes under `kotlin-js-store/`:
  ```bash
  ./gradlew kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock
  ```
  These tasks resolve dependencies and run yarn. They don't compile Kotlin, so
  running them costs much less than `jvmTest`.
- If you didn't touch dependencies, the lockfiles shouldn't change. If they do,
  find out why before committing them.
- A Web job that fails on `kotlinStoreYarnLock` or `kotlinWasmStoreYarnLock` is
  never a flake. Don't merge on it. If main already fails this way, fix it on main
  in its own PR. Don't regenerate the lockfiles on an unrelated branch.
- Don't edit the lockfiles by hand. Removing one dependency's stanza is the only
  exception, and only when nothing else in the file lists it under `dependencies:`.

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
| `phosphor-lumos/` | Framework-free voxel-orb companion visualization for cognitive state, built as a sibling output module over `phosphor-core`; also the `LumosCanvasFrame` DTO, `PixelLattice` projection and `OrbStepper` clock every pixel surface shares |
| `phosphor-lumos-cli/` | JVM terminal frame DTOs, the `CliLattice` projection, and the `CliOrb` ANSI renderer for Lumos voxel frames |
| `phosphor-lumos-compose/` | Compose Multiplatform Canvas rendering surface (`LumosCanvas`, `LumosOrb`) for projected Lumos voxel frames |
| `phosphor-trace/` | Recorded `VoxelTrace` format, capture, codec, and playback — versioned segments and a CBOR + gzip codec |

### Future / Aspirational Modules

| Module | Purpose |
|--------|---------|
| `phosphor-mosaic/` | Mosaic (Compose-for-terminal) adapter — AnnotatedString output |
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
| `phosphor-lumos-compose/src/commonMain/` | Compose Multiplatform Canvas surface for Lumos frames |
| `phosphor-lumos-compose/src/commonTest/` | Compose surface and Compose-state tests |

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
- **Never** create `CHANGELOG.md` or add a changelog entry to a PR — see [Changelog](#changelog)
- **Never** change a dependency without regenerating and committing `kotlin-js-store/` — see [Dependencies and the Yarn Lockfiles](#dependencies-and-the-yarn-lockfiles)
- **Never** merge on a red Web job that failed on `kotlinStoreYarnLock` / `kotlinWasmStoreYarnLock`; it is not a flake
- **Never** push a `v*` tag without `scripts/release-preflight.sh` passing on that exact commit — Maven Central is permanent
- **Always** run `./gradlew jvmTest` before considering work complete
- **Always** run `./gradlew ktlintFormat` before committing
- **Always** verify demo output visually when changing palette or projection code

## Permissions

- **File read:** any file in the repository
- **File write:** `src/`, `test/`, `docs/`, config files
- **Git:** branch creation, commits (with review)
- **Build/test:** full access to `./gradlew` commands
- **External:** no network requests — this is an offline rendering library
