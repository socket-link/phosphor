package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.palette.AtmospherePresets

/**
 * PHO-53: a labelled orb is one image a screen reader describes by its label; an unlabelled one
 * leaves nothing for a screen reader to stop on.
 *
 * The orb's frame loop never ends, so the test clock is advanced by hand rather than until idle.
 */
@OptIn(ExperimentalTestApi::class)
class LumosOrbSemanticsTest {
    @Test
    fun `a labelled orb is one image described by its label`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent { LumosOrb(source(), LumosConfig(label = LABEL), Modifier.size(64.dp)) }
            mainClock.advanceTimeByFrame()

            val image = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)
            onNodeWithContentDescription(LABEL).assert(image)
            onAllNodesWithContentDescription(LABEL).assertCountEquals(1)
        }

    @Test
    fun `an unlabelled orb leaves nothing for a screen reader`() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent { LumosOrb(source(), LumosConfig(), Modifier.size(64.dp)) }
            mainClock.advanceTimeByFrame()

            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)).assertCountEquals(0)
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)).assertCountEquals(0)
        }

    private fun source() = LiveFrameSource(AtmospherePresets.THINKING.copy(resolution = 3))

    private companion object {
        const val LABEL = "Lumos is thinking"
    }
}
