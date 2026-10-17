package link.socket.phosphor.lumos.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PHO-75: the control keeps click and semantics under the overlay, the overlay contributes no
 * semantics node, a target is clickable at alpha 0 before any cell has reached it, and a target
 * with no surface above it says so.
 *
 * The surface's frame loop never ends on its own mid-flight, so the clock is advanced by hand.
 */
@OptIn(ExperimentalTestApi::class)
class LumosSurfaceSemanticsTest {
    @Test
    fun `the control keeps click and semantics under the overlay`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val state = previewSurfaceState()
            var clicks = 0
            setContent { LumosSurfacePreview(state, assembleAfterSeconds = 0f, onTurnOn = { clicks++ }) }
            repeat(3) { mainClock.advanceTimeByFrame() }

            assertIs<SurfacePhase.Assembling>(state.phase, "the overlay must be composed for this to mean anything")
            assertTrue(state.alphaFor(PREVIEW_TARGET) < 1f)

            onNodeWithText(PREVIEW_LABEL).performClick()
            assertEquals(1, clicks)
            onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertCountEquals(1)
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertCountEquals(0)
        }

    @Test
    fun `a target is clickable at alpha 0 before the cells reach it`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            val state = previewSurfaceState()
            var clicks = 0
            setContent { LumosSurfacePreview(state, assembleAfterSeconds = 100f, onTurnOn = { clicks++ }) }
            mainClock.advanceTimeByFrame()

            assertEquals(SurfacePhase.Orb, state.phase)
            assertEquals(0f, state.alphaFor(PREVIEW_TARGET))

            onNodeWithText(PREVIEW_LABEL).performClick()
            assertEquals(1, clicks)
        }

    @Test
    fun `lumosTarget outside a LumosSurface throws and says so`() {
        val failure =
            assertFails {
                runComposeUiTest {
                    setContent { Box(Modifier.lumosTarget("lost").size(10.dp)) }
                }
            }
        val messages = generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(messages.any { it.contains("LumosSurface") }, "unexpected failure: $messages")
    }
}
