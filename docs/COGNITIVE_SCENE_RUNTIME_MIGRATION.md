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

## Notes

- `CognitiveSceneRuntime` is timing-agnostic. You own timers and frame pacing.
- Existing subsystem APIs remain available for direct use.
- For deterministic replay, reuse identical `SceneConfiguration.seed` and `dt` sequence.
- Disable unused systems with configuration toggles to avoid allocations and update work.
