package link.socket.phosphor.color

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase

class CognitiveColorModelTest {
    private val ansi = AnsiColorAdapter.DEFAULT

    @Test
    fun `all cognitive phases have semantic colors and ramps`() {
        CognitivePhase.entries.forEach { phase ->
            assertTrue(CognitiveColorModel.phaseColors.containsKey(phase), "Missing phase color for $phase")
            assertTrue(CognitiveColorModel.phaseRamps.containsKey(phase), "Missing phase ramp for $phase")
        }
    }

    @Test
    fun `all agent states and activity states have assigned colors`() {
        AgentColorState.entries.forEach { state ->
            assertTrue(CognitiveColorModel.agentStateColors.containsKey(state), "Missing agent state color for $state")
        }

        AgentActivityState.entries.forEach { state ->
            assertTrue(
                CognitiveColorModel.agentActivityColors.containsKey(state),
                "Missing agent activity color for $state",
            )
        }
    }

    @Test
    fun `confidence and flow ramps have at least five stops`() {
        assertTrue(CognitiveColorModel.confidenceRamp.stops.size >= 5)
        assertTrue(CognitiveColorModel.flowIntensityRamp.stops.size >= 5)
    }

    @Test
    fun `phase ramps preserve legacy ansi identity`() {
        val expected =
            mapOf(
                CognitivePhase.PERCEIVE to listOf(17, 18, 24, 31, 38, 74, 110, 117, 153, 189, 231),
                CognitivePhase.RECALL to listOf(52, 94, 130, 136, 172, 178, 214, 220, 221),
                CognitivePhase.PLAN to listOf(23, 29, 30, 36, 37, 43, 79, 115, 159),
                CognitivePhase.EXECUTE to listOf(52, 88, 124, 160, 196, 202, 208, 214, 220, 226, 231),
                CognitivePhase.EVALUATE to listOf(53, 54, 91, 97, 134, 140, 141, 183, 189),
                CognitivePhase.LOOP to listOf(232, 236, 240, 244, 248, 252, 255),
                CognitivePhase.NONE to listOf(232, 236, 240, 244, 248, 252, 255),
            )

        expected.forEach { (phase, legacyStops) ->
            val adapted = CognitiveColorModel.phaseRampFor(phase).stops.map(ansi::ansi256Code)
            assertEquals(legacyStops, adapted, "ANSI stop mismatch for $phase")
        }
    }
}
