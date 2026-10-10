# Changelog

All notable changes to Phosphor are documented in this file.

## [Unreleased]

### Fixed

#### Prototype-parity defects in the Lumos renderer (PHO-38 Task A recon / #74)

Three behavioral divergences from the original voxel-orb prototype, found while enumerating the tunable parameter surface for the Workbench. The parameters themselves were all present and the five atmosphere presets matched the prototype exactly; what diverged was how three of them were consumed.

**`AtmosphereState.voxelGap` is now the empty fraction of a lattice cell, not the cube size.** `VoxelFrameBuilder` computed `scale = voxelGap * pulse * …`, so the canonical `voxelGap = 0.05` emitted every voxel at 5% of full size — a 0.2 px half-size through `ComposeLattice`'s stock `voxelRadiusPx`, and the blank end of the luminance ramp through `CliLattice`. A cube now fills `1 - voxelGap` of its cell, clamped to `0..1`. `VoxelSphere.worldScale` (`11f / resolution`, computed and tested but consumed by nothing) documents the convention this restores: a voxel's side is one lattice cell, minus the gap.

Renderers that compensated for the old scale by inflating their own voxel size will now draw too large. The matching base radius for an orthographic projector is half a lattice cell — `widthPx / (4 * resolution)`.

**`overshoot` easing is no longer clamped away.** `AtmosphereChoreographer.update` applied `coerceIn(0f, 1f)` to eased progress, which erased the excess that *is* the overshoot. Three authored transitions (`idle→ready`, `listening→ready`, `thinking→ready`) use it, and all three arrived flat. Only linear progress is bounded now, because it drives the crossfade weights and the completion test. `AtmosphereState.resolution` is held inside the transition's endpoints while eased progress runs past 1, since it is the one interpolated field that reallocates the voxel lattice.

### Added

#### Optional transition specs on atmosphere changes (PHO-38 / #74)

`AtmosphereChoreographer.setAtmosphere`, `CognitiveSceneRuntime.setAtmosphere`, `CognitiveSceneRuntime.setAtmospherePreset`, `SignalGenerator.setAtmosphere`, and `SignalGenerator.setAtmospherePreset` all accept an optional `spec: AtmosphereTransitionSpec?`. Null consults the default transition table, which is what a scene driven by cognitive state wants, so every existing call site is unaffected.

`AtmosphereTransitionSpec.Immediate` applies the target on the next update with no interpolation. A tuning surface needs this: it re-sets the atmosphere on every slider change, and a 1.1 s tabled transition per tick would leave the preview chasing the pointer rather than reading the parameter. An explicit spec also lets a caller replay one authored transition regardless of which preset pair it names.

`AtmosphereTransitionSpec` now requires `durationSeconds >= 0`.

#### `TracePlayer` and sealed `LumosSource` in `:phosphor-trace` (PHO-37 / #73)

`LumosSource` is the consumer-facing seam for a `VoxelFrame` stream: `LumosSource.Live(configuration, renderConfig, fps)` runs a simulation, `LumosSource.Trace(trace, initialSegment)` replays a recording, and `source.frames(timeSource): Flow<VoxelFrame>` opens either one. Swapping live for recorded is a one-line change at the declaration — the seam sits upstream of projection, so `ComposeLattice`, `CliLattice`, and `LumosCanvas` are untouched and rotation, depth sorting, halo, theming, and the glyph overlay all stay live over a replay.

`TracePlayer(trace, initialSegment)` maps wall time onto recorded frames by truncation, so every instant inside a frame's window resolves to that frame and a display refreshing faster than the recording holds the *identical* frame instance — nothing is allocated on a refresh that holds. It honors `loop` on the active segment, wrapping with no index gap at the seam, and supports `play`, `pause`, `seek(frameIndex)`, and `setSegment(name)`. Frames are reconstructed lazily by slicing the columnar channel arrays rather than through `VoxelTrace.toFrames()`, which materializes a whole recording at once. Unknown segment names throw `TracePlaybackError.UnknownSegment`; trace *loading* stays Result-typed through `TraceCodec.decode`.

`SignalGenerator` is the live half — the bench instrument opposite `Oscilloscope`, pairing a `CognitiveSceneRuntime` with a `VoxelFrameBuilder` and stepping them at a fixed `1 / fps`. `Oscilloscope.capture` now drives one, so capture and live playback share a single code path; that is what makes a replay frame-identical to the simulation it recorded rather than merely similar to it. Wall time decides when a step is taken, never how big it is.

`:phosphor-trace` now declares `api("org.jetbrains.kotlinx:kotlinx-coroutines-core")` because `Flow<VoxelFrame>` is part of its public API. Coroutines stay out of `:phosphor-core`, `:phosphor-lumos`, and `:phosphor-lumos-compose`, so renderer-only embedders do not pull them in. Trace playback is available on the JVM and Apple targets, matching the module's existing targets.

`GlyphLifecycle` in `:phosphor-lumos` is now public, so consumers can drive the same fade envelope when they overlay a glyph on frames they did not build — firing a glyph live over a baked trace, for instance.

`./gradlew :phosphor-lumos-cli:runTracePlayerDemo` replays a `.vxt` through `CliOrb` with a live glyph burning over the recorded voxels.

#### `Oscilloscope` headless capture in `:phosphor-trace` (PHO-36 / #72)

`Oscilloscope.capture(config: CaptureConfig): Result<VoxelTrace>` drives `CognitiveSceneRuntime` and `VoxelFrameBuilder` with no renderer and no UI clock, stepping the simulation at exactly `1f / fps`, and assembles the frames into a `VoxelTrace`. Equal configs produce byte-identical `.vxt` payloads; this is verified by tests. `CaptureConfig` carries the tuning `CaptureParams`, the seed, duration, fps, `TraceSegment`s, optional `AtmosphereCue`s for scripted transitions, and the header timestamp and version, so nothing is read from the environment during capture. Failures are Result-typed via `CaptureError`.

`:phosphor-trace` now declares `api(project(":phosphor-core"))` because `CaptureParams` exposes `AtmosphereState`.

### Breaking Changes

#### `CognitivePhase` enum updated to canonical PROPEL vocabulary

**`EVALUATE` renamed to `LEARN`** — The reflection phase is now named `LEARN` to match the canonical six-phase PROPEL model (`PERCEIVE / RECALL / OBSERVE / PLAN / EXECUTE / LEARN`).

**`OBSERVE` added** — A new phase between `RECALL` and `PLAN` representing pattern recognition (comparing input against retrieved context). Its visual ramp mirrors `PERCEIVE` (cool blues → white), consistent with the Wave 3 default mapping in AMPERE and Lumos.

**Final enum order:** `PERCEIVE, RECALL, OBSERVE, PLAN, EXECUTE, LEARN, LOOP, NONE`

`LOOP` and `NONE` are preserved — they are Phosphor-internal phases used by the cell-based renderer's scheduling and do not map to PROPEL directly.

#### Migration path for consumers

| Before | After |
|--------|-------|
| `CognitivePhase.EVALUATE` | `CognitivePhase.LEARN` |
| (absent) | `CognitivePhase.OBSERVE` |

Steps:
1. Bump your Phosphor dependency to this version.
2. Rename all `CognitivePhase.EVALUATE` references to `CognitivePhase.LEARN`.
3. Add `OBSERVE` branches to any exhaustive `when (phase: CognitivePhase)` expressions. The Kotlin compiler will surface every site that needs touching.
4. If you bridge to AMPERE's `CognitivePhase`, remove any `EVALUATE → LEARN` paveover now that both enums use canonical names.

#### Notes on the AMPERE coexistence

Phosphor's `link.socket.phosphor.signal.CognitivePhase` and AMPERE's `link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase` are separate enums that coexist by design. This release aligns Phosphor's vocabulary with the canonical PROPEL model that AMPERE already uses; downstream AMPERE cleanup (walking the seven files identified in the Wave 4 recon) is tracked separately.
