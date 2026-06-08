# Lumos Orb Regression — Version-Axis Bisection Ledger

Tracking issue: **PHO-31** — *Lumos orb regression: version-axis bisection to locate the
broken Ampere/Phosphor release* (GitHub socket-link/phosphor#67).

Symptom: the Lumos orb renders as a **static radial gradient** (atmospheric-glow layer
alone, voxel layer dark) and the app is sluggish/unresponsive. Glow alive + voxels dead
localizes the break to the voxel feed/tick path driven by Phosphor's `SceneSnapshot`
stream (fed by AMPERE `CognitivePhase` events).

Oracle: frametime + "does the orb animate" — **PASS** = animates + responsive;
**FAIL** = static/frozen or janky.

> ### Provenance / scope of this ledger
> This bisection was run **producer-side** against the Phosphor source + git history,
> which is the only repository in this session's scope. The runtime lab described in the
> SOP lives in the **Socket consumer** repo (the `gradle/libs.versions.toml` version
> catalog + the running app Miley observes); that repo is **not available** in this
> session, and there is **no human oracle** attached. Where a fact required the Socket
> catalog or a live observation, it is marked **PENDING (needs Socket repo / Miley)**.
> Producer-side evidence was strong enough to localize the boundary to a single adjacent
> release pair without a sweep — see the determination at the bottom. Nothing was
> bumped, locked, or "fixed": this ledger is a provenance document only.

---

## Phase 0 — Lab setup / recon

### 0.3 Version keys + Maven coordinates (producer side)

All Phosphor modules are versioned by a **single** Gradle property and publish under group
`link.socket`. They **move together** — there is no way to bump one module independently.

| Source of truth | Value |
|---|---|
| Version property | `phosphorVersion` in `gradle.properties` (currently `0.6.2`) |
| Wiring | each module's `build.gradle.kts`: `version = phosphorVersion` → `coordinates("link.socket", "<module>", version)` |

Published Maven coordinates (all share `phosphorVersion`):

| Coordinate | Role |
|---|---|
| `link.socket:phosphor-core` | rendering pipeline — signals, palettes, choreography, `SceneSnapshot` runtime |
| `link.socket:phosphor-lumos` | framework-free voxel-orb frame data |
| `link.socket:phosphor-lumos-cli` | JVM terminal surface |
| `link.socket:phosphor-lumos-compose` | **Compose Canvas surface — the orb the app renders** |

**Multi-pin flag (SOP 0.3):** ✅ confirmed relevant. A Socket app rendering the orb pins
**at least** `phosphor-core` **and** `phosphor-lumos-compose` (and likely `phosphor-lumos`).
Because all coordinates share one version, the consumer's catalog entries must move in
lockstep — bumping `phosphor-lumos-compose` without matching `phosphor-core` is not a valid
checkpoint. The exact catalog keys are **PENDING (needs Socket repo `gradle/libs.versions.toml`)**.

### 0.4 Published Phosphor releases (ascending) — the search space

| Version | Bump commit | Notes for the orb |
|---|---|---|
| `0.5.0` | `22a3ec7` (#51) | No `phosphor-lumos-compose` module at all. |
| `0.6.0` | `0369504` (#58) | lumos-compose source exists (PHO-25/26/27) but **NOT published** — the 0.6.0 publish workflow shipped only core/lumos/lumos-cli (#59 added compose afterward). `phosphor-lumos-compose:0.6.0` does not exist on Maven Central. Not a consumable corner for the orb. |
| `0.6.1` | `e77c8c2` (#60) | **First release that publishes `phosphor-lumos-compose`** (#59). Pre-enum-change. **← GREEN candidate.** |
| `0.6.2` | `54e7a9d` (#63) | Contains PHO-28 (#62) `CognitivePhase` enum reorder/rename. Current pin. **← RED.** |

Commits **strictly between** green `0.6.1` and red `0.6.2`:

```
40ebf92  PHO-28/#61 rename CognitivePhase.EVALUATE → LEARN, add OBSERVE (#62)   ← only functional change
54e7a9d  Bump version to 0.6.2 (#63)                                            ← version bump only
```

So the green→red window contains exactly **one** behavioural commit. The window is already
adjacent: **(good 0.6.1, bad 0.6.2)** — no midpoint exists to bisect.

### 0.5 Dependency locking

Producer side: **no locking** (no `*.lockfile`, no `dependencyLocking {}` in any
`build.gradle.kts`). Whether the **Socket consumer** enables Gradle dependency locking is
**PENDING (needs Socket repo)** — if enabled, sweeps there must use selective
`--update-locks <coord>`, never a blanket `--write-locks`.

### 0.6 Ledger initialized ✅ (this file)

---

## Checkpoint ledger

Runtime columns (Build = `--refresh-dependencies` result in the Socket app; Orb / Frametime
= Miley's observation) are **PENDING** because the Socket lab + human oracle are out of
session scope. The "Source verdict" column is the producer-side determination from reading
the Phosphor diff between the two pins.

| # | Window | Target (the swept axis) | core / lumos / lumos-compose | Build | Orb | Frametime | Source verdict |
|---|---|---|---|---|---|---|---|
| C0-GREEN | — | baseline | `0.6.1` / `0.6.1` / `0.6.1` | PENDING | PENDING (expect PASS) | PENDING | last-good — pre-enum-change |
| C0-RED | — | current HEAD pins | `0.6.2` / `0.6.2` / `0.6.2` | PENDING | PENDING (expect FAIL) | PENDING | first-bad — contains PHO-28 |
| C1 | `[0.6.1, 0.6.2]` | **Phosphor → 0.6.2** (Ampere held at green) | `0.6.2` / `0.6.2` / `0.6.2` | PENDING | PENDING | PENDING | **FAIL expected** — single decisive test == boundary; window already adjacent |

There is no Phase 2 binary-search row: the green→red window is a single adjacent step, so
the Phase 1 decisive Phosphor test **is** the terminal checkpoint.

---

## Determination (producer-side, pending runtime confirmation)

* **Boundary:** last-good `0.6.1`, first-bad `0.6.2`, on the **Phosphor** axis.
* **Version-borne?** Yes. The sole behavioural change in the window is **PHO-28 (`40ebf92`)**,
  a **breaking change to the public `CognitivePhase` enum API**:
  `EVALUATE` renamed to `LEARN`, and `OBSERVE` inserted between `RECALL` and `PLAN`
  (final order `PERCEIVE, RECALL, OBSERVE, PLAN, EXECUTE, LEARN, LOOP, NONE`).
* **Why it presents as "glow alive, voxels dark" rather than a crash inside Phosphor:**
  within Phosphor the change is internally consistent — every `when (phase)` was updated
  exhaustively (`CognitiveChoreographer`, `PhaseBlender`, `CognitiveColorRamp`,
  `AsciiLuminancePalette`), ramps/palettes for `OBSERVE`/`LEARN` were added, and tests were
  updated. So Phosphor compiles and its own renderer still animates. The break surfaces at
  the **Socket-side AMPERE→Phosphor bridge** that feeds the voxel `SceneSnapshot` stream:
  AMPERE and Phosphor keep **separate** `CognitivePhase` enums (by design — see the note in
  `signal/CognitivePhase.kt`). A bridge that maps by name
  (`Phosphor CognitivePhase.valueOf(amperePhase.name)`) now throws on `"EVALUATE"`, and any
  non-exhaustive `when` that silently drops the new `OBSERVE` starves the voxel feed — while
  the cheap atmospheric-glow background pass (not phase-driven) keeps painting. That is the
  observed signature.
* **Recommended next step (a separate human decision, per SOP termination):** this is
  **fix-forward in the consumer**, not source-level commit bisection — the offending change
  is a deliberate, isolated API break, not a mystery regression needing `includeBuild`.
  Update the Socket AMPERE→Phosphor phase bridge for the renamed/added phases
  (`EVALUATE → LEARN`, handle `OBSERVE`) and re-pin Phosphor to `0.6.2`.

## Gates still open (cannot be closed in this session)

1. **C0-GREEN / C0-RED runtime confirmation** — Miley builds Socket at `0.6.1` (orb animates)
   and at `0.6.2` (orb static) and records frametimes.
2. **Socket catalog facts** — exact `libs.versions.toml` keys for the Phosphor coordinates,
   the pinned Ampere coordinate/version (the held axis), and whether dependency locking is
   enabled.
3. **C1 decisive test** — in the Socket lab, from the green corner bump only Phosphor to
   `0.6.2`, `git diff` shows exactly the one (or lockstep) coordinate line(s), build with
   `--refresh-dependencies`, observe. Expected: FAIL, confirming the boundary above.
