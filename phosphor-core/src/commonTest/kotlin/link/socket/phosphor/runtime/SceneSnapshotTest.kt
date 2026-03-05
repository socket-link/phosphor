package link.socket.phosphor.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import link.socket.phosphor.coordinate.CoordinateSpace
import link.socket.phosphor.field.SubstrateState
import link.socket.phosphor.signal.CognitivePhase

class SceneSnapshotTest {
    @Test
    fun `waveform height field uses content equality`() {
        val first = buildSnapshot(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
        val second = buildSnapshot(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `waveform height field detects content changes`() {
        val first = buildSnapshot(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f))
        val second = buildSnapshot(floatArrayOf(0.1f, 0.2f, 0.35f, 0.4f))

        assertNotEquals(first, second)
    }

    @Test
    fun `waveform dimensions are required when height field is present`() {
        assertFailsWith<IllegalArgumentException> {
            SceneSnapshot(
                frameIndex = 0,
                elapsedTimeSeconds = 0f,
                coordinateSpace = CoordinateSpace.WORLD_CENTERED,
                agentStates = emptyList(),
                substrateState = SubstrateState.create(2, 2),
                particleStates = emptyList(),
                flowConnections = emptyList(),
                flowField = null,
                waveformHeightField = floatArrayOf(0f, 0f, 0f, 0f),
                waveformGridWidth = null,
                waveformGridDepth = null,
                cameraTransform = null,
                emitterStates = emptyList(),
                choreographyPhase = CognitivePhase.NONE,
            )
        }
    }

    private fun buildSnapshot(waveformHeightField: FloatArray): SceneSnapshot {
        return SceneSnapshot(
            frameIndex = 12,
            elapsedTimeSeconds = 1.2f,
            coordinateSpace = CoordinateSpace.WORLD_CENTERED,
            agentStates = emptyList(),
            substrateState = SubstrateState.create(2, 2),
            particleStates = emptyList(),
            flowConnections = emptyList(),
            flowField = null,
            waveformHeightField = waveformHeightField,
            waveformGridWidth = 2,
            waveformGridDepth = 2,
            cameraTransform = null,
            emitterStates = emptyList(),
            choreographyPhase = CognitivePhase.NONE,
        )
    }
}
