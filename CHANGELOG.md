# Changelog

All notable changes to Phosphor are documented in this file.

## [Unreleased]

### Added

#### Loops that wrap: `seamError`, baked seam blends, and carried rotation in `:phosphor-trace` (PHO-43 / #89)

A `loop` segment did not wrap, and nothing said so. `Oscilloscope.capture` records whatever the simulation produced between a segment's two frames, and the breath pulse, pattern, surface bump, and orb rotation advance at rates that share no period. Measured at 20 fps and resolution 10, every canonical preset jumped at its seam at both 4 s and 8 s: rotation by the loop's whole travel (23° to 155°), and colour and position by 10 to 80 frame steps in their worst channel. In a Compose raster that is 3 to 21 times an ordinary frame's pixel change; through `CliLattice` it is a colour-band jump. `TransitionPolicy.FinishLoopThenSwitch`, the default, plays that seam on every iteration — a 4 s idle loop popped fifteen times a minute while the orb sat idle.

**`VoxelTrace.seamError(segment)` and `VoxelTrace.seamErrors()` measure it.** Each `SeamError` reports five channels — mean per-cell position, scale, and colour distance, ambient glow, and ambient orb rotation on the shortest arc — as a `SeamChannel` of the raw `jump` beside the segment's `medianStep`. `ratio` divides the two, so about `1` is a seam indistinguishable from an ordinary frame step and `10` is a ten-frame jump. `SeamError.contentRatio` is the worst channel other than rotation, which is the number to hold to a ceiling (see below); `SeamError.ratio` includes rotation. The metric is computed from the columnar channels on demand, and the format is unchanged.

**`CaptureConfig.seamBlendFrames` bakes the content seam out.** Over the last *B* frames of every `loop` segment, cells and halo fade toward the *B* frames that precede the segment's start — synthesized from the loop's own steady state, see PHO-49 below — so the final frame is the one before the first and wrapping reads as one more step. With 20 frames at 20 fps every canonical preset wraps at a `contentRatio` below 2, at the cost of content moving up to about four times its normal speed through the window. It defaults to `0`, which records the simulation untouched and leaves a capture's bytes as they were. The blend pairs cells by index, so it is rejected alongside `LumosRenderConfig.omitBelowScale > 0`, as is a blend as long as a loop.

**`TraceStateMachine` carries rotation across seams instead of replaying it.** A loop shorter than a full turn — 14 to 63 s for the canonical presets — cannot wrap a monotonic rotation, and a baked blend that tried would spin the orb backwards through the window at three to seven times its normal speed. Cells are recorded unrotated and the angles live in `VoxelAmbient`, so the machine offsets them: each loop iteration, and each segment a head enters — finishing a loop into another, a recorded transition, either side of a crossfade — starts where the last frame left off, one ordinary step on. Only the three ambient angles change; the cells are the recorded ones, a frame whose offset is zero is the recorded instance, and a held carried frame is one instance across refreshes. This is on by default and changes what the machine emits: `ambient.orbRotation*` no longer equals the recorded value after the first pass through a segment. `TraceStateMachine(…, carryRotation = false)` restores replayed rotations. `TracePlayer` is unchanged and still replays rotations as recorded; `LumosSource.Trace` carries them too (PHO-47, below).

`FinishLoopThenSwitch`'s KDoc no longer says loops are recorded to wrap. It states content continuity as a precondition and points at `seamBlendFrames` and `contentRatio`.

`./gradlew :phosphor-lumos-cli:runStateMachineDemo` captures with a 12-frame seam blend and prints each loop's measured seam; since PHO-48 the terminal turns the orb too, so the rotation carry shows there as well as through `ComposeLattice`. `./gradlew :phosphor-lumos-cli:runSeamRecon` reproduces the measurements above.

### Fixed

#### `CliLattice` turns the orb by `VoxelAmbient.orbRotation*` (PHO-48 / #94)

`VoxelFrameBuilder` records cells unrotated and leaves the orb's integrated rotation in `VoxelAmbient.orbRotationX/Y/Z` for the surface adapter to apply. `ComposeLattice` applied it; `CliLattice` read `cell.x/y/z` directly, so the terminal orb never turned — every preset's 0.10–0.45 rad/s spin was invisible on the CLI, and the two surfaces disagreed about what the same frame looked like. `CliLattice.project` now rotates each voxel X → Y → Z by the frame's ambient angles, exactly as `ComposeLattice` does, before placing it. Occlusion follows the rotated depth: the voxel that ends up nearest the camera wins its character cell, not the one recorded nearest. On screen the silhouette barely changes, since the orb is a sphere; what moves is the colour pattern, UNCERTAIN's bipolar bands, and the glyph, which is carved on the face the rotation brings to the camera and now lands where the terminal can see it. A frame whose three angles are zero projects exactly as before. The ambient still passes through to `LumosTerminalFrame` unchanged.

#### Baked seams fade toward a synthesized lead-in, so a loop can follow a transition directly (PHO-49 / #95)

`seamBlendFrames` fades a loop's tail toward the *B* frames before the loop's start, and those frames had to already look like the loop. When a recorded transition preceded the loop they were the transition's tail, still moving, and the loop's last half-second leaned toward the state it had come from — a faint ghost once per iteration that the seam metric cannot see, because the seam itself is continuous by construction. `StateMachineDemo` worked around it by padding every one-shot with *B* frames of settled hold, which made each transition about 0.5 s longer at 24 fps and the asset *B* frames bigger per transition.

Capture now synthesizes the lead-in instead of recording it. In steady state a frame is a pure function of the atmosphere and four phase accumulators, each advancing linearly at the atmosphere's rate, so at a loop's first frame capture reads the generator's atmosphere and phases, rewinds the phases a step at a time, and builds the *B* frames the simulation would have produced there with no transition and no glyph. The loop's tail fades onto its own motion whatever was recorded before it. On the demo's script with unpadded transitions (24 fps, resolution 10, *B* = 12) every loop wraps at a `contentRatio` below 2, and the blend window's halo and mean colour stay inside the loop's own range; fading toward the recorded frames instead pulls the `uncertain` loop's halo 0.05 toward idle, in sRGB distance over channels in `0..1`, where the synthesized lead-in moves it by exactly zero.

Two consequences for captures with `seamBlendFrames > 0`. A loop at the head of the clip no longer gets a hidden warm-up, so recorded ticks and timestamps are the simulation's own and `paramSnapshot` no longer carries `capture.warmUpFrames`. And a loop preceded by a transition no longer needs a settled hold: start it on the frame after the transition lands. A cue still in motion at the loop's first frame makes the seam close onto a frame the loop never shows, so that remains the caller's to avoid. Captures with `seamBlendFrames = 0` are unchanged.

The seam into the builder is public and small. `FramePhases` in `:phosphor-lumos` is the value of the four accumulators, with `advanced(atmosphere, dt)` as the integration `VoxelFrameBuilder.build` applies each tick, written as a pure function; a negative `dt` rewinds. `VoxelFrameBuilder.phases` reads the current value, and `VoxelFrameBuilder.buildSteadyState(atmosphere, phases, tick, timestampEpochMillis)` renders the frame the builder would produce at those phases in steady state without advancing anything; it requires the atmosphere's resolution to match the builder's lattice. `pulsePhase`, `patternPhase`, `orbRotationX`, and `orbRotationY` read as before. `SignalGenerator` composes the two for `Oscilloscope` behind an internal `steadyStateLeadIn(frames)`.

`StateMachineDemo`'s one-shots are now exactly as long as their authored transitions — 20, 24, 34, and 28 frames — so `state` flips about half a second sooner after a key press and the clip is 48 frames shorter.

### Changed

#### `LumosSource.Trace` carries rotation across loop seams (PHO-47 / #93)

PHO-43 carried rotation only inside `TraceStateMachine`, so a looping segment played through `LumosSource.Trace(trace, initialSegment)` — the documented one-line swap for a live source — still replayed the recorded angle and dropped the orb back by the loop's whole travel at every wrap: 23° to 155° per seam for 4–8 s canonical-preset loops, the largest pop left on Compose. A baked `seamBlendFrames` capture does not help here, because the bake leaves rotation as recorded.

`source.frames()` on a trace source now applies the machine's carry: each further iteration of a looping segment is offset so it starts where the last frame left off, one ordinary step on. Only the three `ambient.orbRotation*` angles change. The cells are the recorded ones, the first pass through a segment is the recording itself, and a held frame is one instance, so a paused or slower-than-display stream still emits nothing while it holds. The stream now emits when the carried frame changes rather than when the recorded index does, because a loop's first frame on its second pass is the same index at a new angle. The carry covers the wrap only: `player.setSegment` and `player.seek` are cuts, and restart rotation from the recorded angle. Playing a whole trace, or a segment that does not loop, emits exactly what it did before.

This is on by default and changes what the stream emits after a loop's first pass. `LumosSource.Trace(…, carryRotation = false)` restores replayed rotations. The carry is applied where frames leave the source, so `TracePlayer` itself — `currentFrame`, `advance`, `frameAt`, and its own `frames` — still replays exactly what was recorded. `LumosSource.Trace` gains a `carryRotation` property, which adds a parameter to the data class's `copy` and a `component2`.

#### Recorded transitions blend in from wherever the loop is: `OneShotEntry` in `:phosphor-trace` (PHO-46 / #92)

`TraceStateMachine` started a recorded `"from→to"` one-shot on its first frame the instant it was asked for. That frame is continuous only with the frame recorded just before it, and the outgoing loop's playhead is almost never there: its pattern, surface bump, and breath pulse have drifted by up to a loop's worth. PHO-43 already carries rotation into the one-shot; the cells still jumped. Measured by entering each of `StateMachineDemo`'s four recorded transitions from every frame of a 4 s loop at 24 fps, the typical entry step was 29 to 45 times the loop's median frame step in its worst cell channel, and 6 to 21 times an ordinary frame's pixel change in a Compose raster.

**Recorded transitions now fade in from the outgoing loop by default.** `TraceStateMachine(…, oneShotEntry = …)` takes a new sealed `OneShotEntry`. The default, `OneShotEntry.BlendIn(durationMs = 500)`, runs the outgoing loop and the one-shot on two heads over the one-shot's first 500 ms and ramps a linear weight between them, which is the machinery `TransitionPolicy.Crossfade` already uses between loops. On baked loops (captured with `CaptureConfig.seamBlendFrames`) this brings the worst step anywhere in the window to 4 to 7 median steps, and the Compose raster's worst to 2.1 to 6.4. Both are in line with the one-shot's own authored motion and the loop's largest ordinary step. The one-shot keeps its length, so `state` flips exactly when it did before. A window longer than the one-shot is cut to it. Each blended frame costs one `FrameBlend.transduce`: about 35 µs on a desktop JVM at 4,801 voxels, or 0.4 ms per switch. A trace whose frames do not all carry the same voxels, such as one captured with `omitBelowScale > 0`, enters immediately instead of blending unpaired cells.

**This changes what the machine emits.** For the first 500 ms of a recorded transition:
- `blend`, `incomingSegment` and `incomingFrameIndex` are non-null.
- `activeSegment` names the outgoing loop until the fade lands.

`OneShotEntry.Immediate` restores the first-frame entry, which costs nothing and jumps.

**Waiting for the loop seam was measured and rejected.** A baked loop ends on the frame before its *own* start, not the frame before the one-shot, so its seam still jumps 22 to 73 steps. Only an unbaked loop recorded immediately before its one-shot meets it at the seam, and that loop pops on every wrap. No loop frame is a good place to wait either: the closest one in a baked loop is still ten or more steps away. Blend-in has the same precondition as `FinishLoopThenSwitch`, a loop that wraps continuously. The outgoing loop keeps playing through the window, so on an unbaked loop an entry close to the seam blends that seam's jump in as well.

`./gradlew :phosphor-lumos-cli:runEntryRecon` reproduces the measurements. `runStateMachineDemo`'s status line now names a blend-in separately from a crossfade.

#### `VoxelTrace` format v3 — positions delta-coded against the previous frame, about 25% smaller than v2 (PHO-44 / #90)

`TraceCodec` now writes **format v3** for every new trace: `VoxelTrace.CURRENT_FORMAT_VERSION` is `3`, so v3 rather than v2 (below) is the format this release writes. v3 keeps v2's quantization and wire layout and changes one thing: each frame's 16-bit position codes are stored as the difference from the same cell in the previous frame. A Lumos voxel moves a few dozen `2⁻¹²` steps per frame, so the high byte plane of every position channel is almost constant and gzip stores it for close to nothing.

On the same real captures as v2 (resolution 10, 4,801 voxels, 20 fps, one 4 s loop per preset):

| Loop | v2 B/voxel-frame | v3 B/voxel-frame |
|------|------------------|------------------|
| IDLE | 3.41 | 2.07 |
| LISTENING | 3.74 | 3.00 |
| THINKING | 4.20 | 3.18 |
| UNCERTAIN | 4.28 | 3.30 |
| READY | 3.71 | 2.93 |

The five loops come to **5.57 MB**, down from 7.43 MB, which leaves about 4.4 MB of SCKT-392's 10 MB budget for recorded transitions. The full 25-segment set would still be about 13 MB, so v3 alone does not fit it. The PHO-35 synthetic clip roughly halves, from 0.68 MB to 0.35 MB.

**Nothing on screen changes.** The deltas are taken between integer codes after quantization, never between floats, and wrap modulo 2¹⁶. A v3 payload therefore decodes to exactly the columns a v2 payload of the same trace would, with v2's tolerance and no accumulated error, and a decoded v3 trace re-encodes to identical bytes. The deltas are undone once, on decode, so a decoded v3 trace keeps the same absolute quantized channels in memory as a decoded v2 trace (PHO-45, below): the same 11 bytes per voxel-frame, any frame readable on its own, and `TracePlayer`, `FrameBlend` and `TraceStateMachine` unchanged. Everything PHO-45 says of a decoded v2 trace holds for v3. The first frame stores absolute codes, as does any frame whose cell count differs from the one before it (`LumosRenderConfig.omitBelowScale > 0`). There are no periodic keyframes: decoding expands the whole trace up front, so keyframes would cost bytes without making seeking any faster.

**v1 and v2 still read**, and a decoded v1 or v2 trace re-encodes in its own format. `TraceCodec.SUPPORTED_FORMAT_VERSIONS` is now `1..3`. A trace captured by this release needs a build that reads v3.

#### `VoxelTrace` format v2 — quantized cell channels, about 3.5× smaller (PHO-42 / #88)

`TraceCodec` now writes **format v2** for every new trace: `VoxelTrace.CURRENT_FORMAT_VERSION` is `2`, and `VoxelTrace.fromFrames` and `Oscilloscope.capture` stamp it. v1 stored every cell channel as a full `Float`, which measured 12.5–13.6 bytes per voxel-frame on real Lumos captures (resolution 10, 4,801 voxels, 20 fps). That put a 4 s loop near 5 MB and Socket's five-loop asset set at 24.8 MB against SCKT-392's 10 MB budget. v2 measures **3.4–4.3 bytes per voxel-frame** on the same captures, and the five loops come to **7.4 MB**. The PHO-35 synthetic clip drops from 2.83 MB to 0.68 MB.

The v2 layout keeps v1's header and per-frame metadata (ticks, timestamps, ambient, glyph) unchanged and quantizes the per-cell channels:
- **Positions** become signed 16-bit multiples of a power-of-two step recorded in the header. For an orb the step is `2⁻¹²` lattice units; anything wider than ±8 gets a coarser step instead of overflowing.
- **Scale** becomes unsigned 16-bit multiples of `1/6561`.
- **Colour and alpha** become one byte each.

Each 16-bit channel is stored as a plane of high bytes followed by a plane of low bytes. That layout, rather than the narrower integers, is what gzip rewards: for positions alone it is the difference between 5.2 and 3.1 bytes per voxel-frame.

**What v2 changes on screen, and what it doesn't.** v2 is lossy, so `decode(encode(trace))` no longer equals `trace` for arbitrary floats. The quantization was chosen so the projected output only moves where it can't be avoided:
- **Colour is drawn identically.** v2 stores the byte Compose itself packs an sRGB float into, so `LumosCanvas` draws the same colour, and `CliLattice`'s truecolor and 256-colour escapes are unchanged.
- **Terminal characters from scale are unchanged.** `1/6561` puts every threshold of `CliLattice`'s ten-step luminance ramp on a grid edge, so a quantized scale never changes the ramp character. Every voxel of a Lumos orb shares one scale per frame, and IDLE's breath crosses a threshold, so a flip there would have changed the whole orb's character for a frame. A positive scale never quantizes to zero, so no voxel appears or disappears.
- **Positions move by at most `2⁻¹³` lattice units**, which is under an eighth of a pixel on a 1080 px `ComposeLattice`. Because `CliLattice` floors each voxel to a character cell, voxels sitting exactly on a cell boundary can land in the neighbouring cell. That affected about 0.6 % of occupied cells per frame at 80×40, against several hundred cells the live orb changes from one frame to the next.

Equivalence tests in `:phosphor-lumos-cli` and `:phosphor-lumos-compose` hold real captures to these bounds.

Re-encoding a decoded v2 trace produces identical bytes. `VoxelTrace` still exposes `FloatArray` columns whichever version it came from, so `TracePlayer`, `FrameBlend` and `TraceStateMachine` are unchanged. Cell index `i` is still the same lattice voxel in every frame.

**v1 still reads.** `TraceCodec.decode` reads v1 and v2 and still returns `TraceDecodeError.UnsupportedFormatVersion` for anything else; a frozen v1 payload in the test suite guards this. `TraceCodec.encode` now writes the format a trace's own `formatVersion` names: a decoded v1 trace re-encodes losslessly as v1 rather than being quantized behind its owner's back, and an unknown version throws `IllegalArgumentException`.

For v2, `encode` also throws on a non-finite position or colour, an infinite alpha, or a scale above `65535/6561 ≈ 9.99`.

`TraceCodec.SUPPORTED_FORMAT_VERSIONS` (`1..2`) is new. `TraceCodec.SUPPORTED_FORMAT_VERSION` and `UnsupportedFormatVersion.supportedVersion` now mean the newest version this build reads.

v2 shrinks the bundle; decoded RAM is the next entry. Previous-frame delta coding shipped as v3, above.

#### Decoded v2 traces stay quantized in memory, about 3× less RAM (PHO-45 / #91)

`TraceCodec.decode` used to expand a v2 payload into the same eight `FloatArray` columns as v1, 32 bytes per voxel-frame. Socket's five 4 s loops (1.92 M voxel-frames) came to about 61 MB resident. A decoded v2 trace now keeps the quantized channels it was stored with: 16-bit positions and scale, 8-bit colour, and no alpha channel when every alpha is null. That is **11 bytes per voxel-frame**, or 13 with alpha. The five loops now hold about 21 MB.

`TracePlayer` dequantizes only the frame it slices, once per recorded frame. Its output is bit-identical to what the float decode produced from the same bytes; a test holds the old decoder as the reference. A held frame is still the identical instance, `FrameBlend.isAligned` is unchanged, and `TraceStateMachine`, `LumosSource.Trace`, `VoxelTrace.toFrames` and `seamError` all read the quantized channels directly.

The public shape of `VoxelTrace` is unchanged, including its constructor. `cellX` … `cellAlpha` still read as `FloatArray`. On a decoded v2 trace, the first read of any of them expands all eight columns and holds them, which costs the RAM this change saves, so avoid them on device. Writing into those arrays no longer changes what a decoded v2 trace plays. Equality and `hashCode` compare channel values, not storage, so a decoded trace still equals a float trace holding the same values. Re-encoding a decoded v2 trace writes its stored codes back unchanged, rather than requantizing them. v1 traces and traces built with `fromFrames` keep float columns.

## [0.7.0] - 2026-10-11

### Fixed

#### Prototype-parity defects in the Lumos renderer (PHO-38 Task A recon / #74)

Three behavioral divergences from the original voxel-orb prototype, found while enumerating the tunable parameter surface for the Workbench. The parameters themselves were all present and the five atmosphere presets matched the prototype exactly; what diverged was how three of them were consumed.

**`AtmosphereState.voxelGap` is now the empty fraction of a lattice cell, not the cube size.** `VoxelFrameBuilder` computed `scale = voxelGap * pulse * …`, so the canonical `voxelGap = 0.05` emitted every voxel at 5% of full size — a 0.2 px half-size through `ComposeLattice`'s stock `voxelRadiusPx`, and the blank end of the luminance ramp through `CliLattice`. A cube now fills `1 - voxelGap` of its cell, clamped to `0..1`. `VoxelSphere.worldScale` (`11f / resolution`, computed and tested but consumed by nothing) documents the convention this restores: a voxel's side is one lattice cell, minus the gap.

Renderers that compensated for the old scale by inflating their own voxel size will now draw too large. The matching base radius for an orthographic projector is half a lattice cell — `widthPx / (4 * resolution)`.

**`overshoot` easing is no longer clamped away.** `AtmosphereChoreographer.update` applied `coerceIn(0f, 1f)` to eased progress, which erased the excess that *is* the overshoot. Three authored transitions (`idle→ready`, `listening→ready`, `thinking→ready`) use it, and all three arrived flat. Only linear progress is bounded now, because it drives the crossfade weights and the completion test. `AtmosphereState.resolution` is held inside the transition's endpoints while eased progress runs past 1, since it is the one interpolated field that reallocates the voxel lattice.

### Added

#### `TraceStateMachine` — state-driven trace playback in `:phosphor-trace` (PHO-39 / #75)

`TraceStateMachine(trace, initialState, policy)` drives a recorded `VoxelTrace` through state changes without a visible pop. Named states map to `TraceSegment`s and a transition policy decides how the playhead moves between them. The type is deliberately generic — it maps strings to segments and knows nothing about what the strings mean — so binding a live signal's states to segment names stays the caller's concern and everything emitting `VoxelFrame`s gets state-driven playback from one implementation.

Resolution order, checked at request time: a segment named `"$current→$target"` in the recording wins outright and starts immediately, with no loop boundary waited for; otherwise the configured `TransitionPolicy` decides. `FinishLoopThenSwitch` (the default) completes the current loop iteration and switches at the seam, which is visually continuous because loops are recorded to wrap. `Crossfade(durationMs)` runs two read heads and ramps a linear weight between them. With a full set of authored transitions recorded, the explicit-segment path covers every pair and the policies never fire.

`requestState(name): Result<Unit>` is Result-typed rather than throwing, which crosses the convention `TracePlayer` documents for playback failures. The divergence is deliberate: a state name arrives from outside the library, so an unmapped one is a data mismatch to report rather than a programming error, and a failed request must leave playback running. It fails with the existing `TracePlaybackError.UnknownSegment`. An unknown `initialState` still throws, because a caller writes that one.

Re-entrant requests collapse. Asking for the state already being headed toward is a no-op; asking for the current state while waiting on a loop seam cancels the wait; a request arriving mid-transition is held and resolved from wherever the machine lands, so a rapid `a → b → a` plays both recorded one-shots instead of cutting the first one short. `state: StateFlow<String>` holds the state whose loop is actually playing and `requestedState: StateFlow<String>` the one being arrived at, so an observer can tell "arriving" from "arrived" without inferring it.

**Blending happens upstream of projection.** `FrameBlend(outgoing, incoming, weight)` is the live crossfade, and `transduce()` collapses it into one ordinary `VoxelFrame` — so `LumosSource`, `CliOrb`, `LumosCanvas`, `CliLattice`, and `ComposeLattice` are untouched by a fade. A pairwise blend needs cell index `i` to mean the same lattice voxel on both sides, and both surface adapters destroy that: `ComposeLattice` drops zero-scale and off-screen voxels per frame, `CliLattice` keeps one z-winner per character cell and then quantizes scale onto a ten-step luminance ramp, which has no continuous interpolant at all. Upstream the channels are still parallel. Read `blend` directly to do the collapse somewhere cheaper instead.

The precondition is checkable: cell index `i` is the same voxel in every frame of a recording — the format requires a uniform resolution and `VoxelSphere` builds its voxels in a fixed order — unless the capture ran with `LumosRenderConfig.omitBelowScale > 0`, which compacts each frame differently. `FrameBlend.isAligned` reports it and `transduce()` cuts at weight `0.5` rather than blending unpaired cells, which would drag voxels across the orb. `paramSnapshot["render.omitBelowScale"]` carries the captured value, so a caller can check before the first fade.

The crossfade weight is **linear and quantized onto the recording's frame grid**. Linear because transition easing — `eager`, `overshoot`, `settled` — is applied by `AtmosphereChoreographer` at capture time and is therefore already baked into the recorded frames; re-applying a curve would ease an already-eased motion. Quantized because reading the weight off raw elapsed time would make a fade depend on how finely the caller ticks, and a 120 Hz consumer and a 30 fps one have to see the same fade for a playback log to be comparable across runs. It also keeps the blended frame cacheable, so a display refreshing faster than the recording reuses the identical instance and a held frame allocates nothing — the same contract `TracePlayer` holds for a recorded frame.

Ambient rotations blend on the **shortest arc**. `VoxelFrameBuilder` wraps its integrated rotations at 2π, so two heads at different points in their loops can sit either side of the wrap; a straight lerp from 6.2 rad to 0.1 rad takes the long way round and the orb visibly spins backwards through the fade.

`TracePlayer` is unchanged. Two read heads are two players over one recording, constructed once and swapped by role when a fade lands, so the player keeps its "one segment at a time" invariant and single-head consumers pay nothing. The machine owns the clock and drives each head through `frameAt`, exposing `advance(delta)` and `frames(timeSource, tickInterval)` of its own; given a deterministic clock the frame sequence is deterministic.

`./gradlew :phosphor-lumos-cli:runStateMachineDemo` switches a recorded orb between `idle`, `thinking`, and `uncertain` from the keyboard. `thinking↔uncertain` is deliberately left unrecorded so the crossfade fallback is visible, and the status line names which resolution path carried each switch.

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

#### `FrameProbe` — per-phase frame timing in `:phosphor-lumos` (PHO-34 / #70)

`FrameProbe` measures where a Lumos frame's milliseconds go, split across the three phases of the frame loop: `FramePhase.BUILD` (`VoxelFrameBuilder.build`), `FramePhase.PROJECT` (`ComposeLattice.project` and `CliLattice.project`), and `FramePhase.DRAW` (`LumosCanvas` and `CliOrb.render`). `RingBufferFrameProbe` is the enabled implementation: it keeps the most recent samples per phase in a preallocated ring (600 by default, `capacity` to change it), and `summary()` returns a `FrameProbeSummary` of p50, p95, and max nanoseconds per phase using nearest-rank percentiles. A phase with no samples reads as all zeros through `forPhase`, so a caller can render a full table without null checks.

**It is off by default and costs nothing off.** `VoxelFrameBuilder`, `ComposeLattice`, `CliLattice`, `LumosCanvas`, and `CliOrb` each take a defaulted `probe: FrameProbe = FrameProbe.Disabled`, so every existing call site is unaffected. `FrameProbe.measure` is an `inline` extension: with a disabled probe it is the wrapped block behind one branch — no clock read, no lambda allocation, no per-frame garbage. Enabled, `record` writes into the preallocated ring, so the hot path still does not allocate; only `summary()` allocates (it copies and sorts the retained samples), and it is meant to run off the frame loop, once, for reporting. A phase's time is recorded even when its block throws.

A probe is single-threaded by contract, matching the pull-based frame loop: drive it from the render thread only. `reset()` drops the retained samples, which is how a harness discards warm-up before a measured window.

`./gradlew :phosphor-lumos-cli:runFrameProbeBenchmark` drives the CLI path for a fixed window (60 s by default; `-Pduration=<seconds>` to change it) and prints the p50/p95/max table per phase alongside a probe-off versus probe-on overhead comparison.

#### `:phosphor-trace` — the recorded-trace format (PHO-35 / #71)

A new published module, `link.socket:phosphor-trace`, and the contract the rest of the trace pipeline builds on: a versioned on-disk format for recording a `VoxelFrame` stream and reading it back. It is framework-free `commonMain` built on `:phosphor-lumos`, available on the JVM and iOS targets. `Oscilloscope` captures into it, and `TracePlayer` and `TraceStateMachine` replay from it.

`VoxelTrace` is **columnar**: a small header — `formatVersion`, `fps`, `frameCount`, `phosphorVersion`, `seed`, a `paramSnapshot` of the tuning parameters in effect, a `StaticLattice(resolution)`, and the `segments` — followed by per-frame channel arrays. Every `VoxelCell` attribute is concatenated across all frames into one primitive array of its own (all the x's, then all the y's, and so on), which groups same-channel bytes so the gzip pass compresses far better than an interleaved array of structs would. Per-frame cell counts are stored explicitly because a producer may omit voxels from frame to frame, so a frame's cell count is not constant. `VoxelTrace.fromFrames(frames, fps, …)` builds a trace from captured frames, which must be non-empty and share one lattice resolution, and `toFrames()` projects it back.

The only datum that is static across a recording is its lattice resolution. A `VoxelFrame` carries positions already transformed per frame — noise, surface bump, breath pulse, Y-squash — so the rest lattice that `VoxelSphere` builds cannot be recovered from a recording and is not part of the format. Every cell attribute, plus tick, timestamp, ambient parameters, and glyph state, is stored per frame.

`TraceSegment(name, startFrame, endFrame, loop)` names an inclusive frame range that a player can loop or play once. Names follow the convention `TraceStateMachine` resolves against: `"idle"` for a resting beat, `"thinking"` for an active one, and `"idle→thinking"` for the transition between two.

`TraceCodec` reads and writes `.vxt`: kotlinx.serialization CBOR, gzipped, with a versioned header. gzip is `java.util.zip` on the JVM and `platform.zlib` on Apple, both with standard RFC 1952 framing, so an encoded asset moves between platforms. `decode` is Result-typed and never throws: it fails with `TraceDecodeError.Corrupt` for a payload that cannot be un-gzipped or parsed, and `TraceDecodeError.UnsupportedFormatVersion` for a header declaring a version this build does not read — the version field exists so a newer payload is rejected cleanly rather than silently misread.

Format v1 stores full-precision floats. A synthetic 5 s, 30 fps, 1500-voxel clip encodes to about 2.7 MiB, against 6.0 MiB of raw float channels. Quantization and inter-frame deltas are reserved for a later `formatVersion`.

## [0.6.2] - 2026-05-31

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
