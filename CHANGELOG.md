# Changelog

All notable changes to Phosphor are documented in this file.

## [Unreleased]

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
