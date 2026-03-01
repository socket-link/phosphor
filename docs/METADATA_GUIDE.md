# Metadata-Driven Emitters

Phosphor emitters can now carry per-instance scalar metadata. This lets an excitation source keep the effect shape stable while varying the metabolic weight of each trigger.

## Overview

`EmitterManager.emit(...)` accepts a `metadata: Map<String, Float>` argument. The map is stored on the live `EmitterInstance` and passed into `EmitterEffect.influence(...)` on every query.

When metadata is absent, effects keep their legacy behavior.

## Built-in Keys

- `MetadataKeys.INTENSITY`: scales an effect's peak output.
- `MetadataKeys.HEAT`: biases effects toward hotter, more energetic behavior.
- `MetadataKeys.DENSITY`: reserved for effects that vary particle or fragment density.
- `MetadataKeys.DURATION_SCALE`: stretches or compresses effect lifetime.
- `MetadataKeys.RADIUS_SCALE`: widens or narrows spatial reach.

## Example

```kotlin
val emitters = EmitterManager()

emitters.emit(
    effect = EmitterEffect.SparkBurst(),
    position = Vector3.ZERO,
    metadata = mapOf(
        MetadataKeys.INTENSITY to 1.6f,
        MetadataKeys.HEAT to 0.9f,
        MetadataKeys.RADIUS_SCALE to 1.25f,
    ),
)
```

## Built-in Behavior

`EmitterEffect.SparkBurst` consumes metadata today:

- `INTENSITY` scales ring brightness.
- `HEAT` increases outward expansion speed.
- `DURATION_SCALE` stretches the burst lifetime.
- `RADIUS_SCALE` widens the area of influence.

Other built-in effects ignore metadata for now and remain behaviorally identical to earlier releases.

## Guidance For Consumers

- Keep keys generic. Map domain values onto visual semantics before they enter Phosphor.
- Prefer normalized floats where practical so effect tuning stays stable across inputs.
- Reuse `emptyMap()` when you have no metadata to avoid unnecessary allocations.
