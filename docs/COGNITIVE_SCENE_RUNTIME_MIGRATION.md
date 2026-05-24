# CognitiveSceneRuntime Migration Notes

`CognitiveSceneRuntime` centralizes scene orchestration in one deterministic `update(dt)` call.

## Before

Consumers manually advanced every subsystem in the frame loop:

1. choreographer
2. substrate animator
3. agent layer
4. emitters
5. particles
6. flow
7. waveform
8. camera

## After

`runtime.update(dtSeconds)` now executes nine ordered steps. Atmosphere
advances between agent state and emitter emission so downstream consumers tick
against the latest scene-global character:

1. `CognitiveChoreographer` phase advance (per-agent transitions, substrate effects)
2. Ambient substrate animation
3. Agent layer update
4. `AtmosphereChoreographer.update(dt)` — interpolates `AtmosphereState`, advances phase accumulators, surfaces crossfade snapshots
5. Emitter emission pass and lifecycle update
6. Particle simulation
7. Flow field advection
8. Waveform sampling
9. Camera orbit

Consumers call `runtime.update(dtSeconds)` and render from the returned `SceneSnapshot`.

```kotlin
val runtime = CognitiveSceneRuntime(sceneConfiguration)

fun onFrame(deltaSeconds: Float) {
    val snapshot = runtime.update(deltaSeconds)
    renderSnapshot(snapshot)
}
```

## Thin Adapter Pattern

Use an adapter at the application boundary to translate runtime snapshots into your UI model.

```kotlin
data class DashboardFrame(
    val agents: List<AgentVisualState>,
    val particles: List<ParticleState>,
    val waveform: FloatArray?,
)

class RuntimeAdapter(
    private val runtime: CognitiveSceneRuntime,
) {
    fun nextFrame(dtSeconds: Float): DashboardFrame {
        val snapshot = runtime.update(dtSeconds)
        return DashboardFrame(
            agents = snapshot.agentStates,
            particles = snapshot.particleStates,
            waveform = snapshot.waveformHeightField,
        )
    }
}
```

## Atmosphere Snapshot Fields

Atmosphere is an opt-in subsystem. `SceneSnapshot.atmosphere` and
`SceneSnapshot.atmosphereTransition` are both `null` unless
`SceneConfiguration.enableAtmosphere` is set to `true`.

When enabled, the runtime constructs an `AtmosphereChoreographer` seeded with
`SceneConfiguration.initialAtmosphere` (defaults to `AtmospherePresets.IDLE`).
Two entry points request a transition to a new atmosphere:

- `runtime.setAtmosphere(state)` — accepts any `AtmosphereState`; the
  choreographer reverse-looks-up the value against the preset table to resolve
  a tabled transition spec, falling back to the default 1.1s easeInOut.
- `runtime.setAtmospherePreset(name)` — case-insensitive lookup via
  `AtmospherePresets.byName`; throws `IllegalArgumentException` for unknown
  names. The resolved preset identifier is passed to the choreographer so the
  default transition table can match by name.

`SceneSnapshot.atmosphere` exposes the interpolated state for the current
tick. `SceneSnapshot.atmosphereTransition` is non-null while a transition is
in progress and carries linear and eased progress, the easing identifier, the
duration, and the from/to endpoints. Renderers (Lumos) consume both fields to
crossfade patterns and to blend bipolar color configurations in OKLab space
via the snapshots surfaced by the choreographer.

## Notes

- `CognitiveSceneRuntime` is timing-agnostic. You own timers and frame pacing.
- Existing subsystem APIs remain available for direct use.
- For deterministic replay, reuse identical `SceneConfiguration.seed` and `dt` sequence.
- Disable unused systems with configuration toggles to avoid allocations and update work.
