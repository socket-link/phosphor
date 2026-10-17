package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.signal.LumosInput

/**
 * PHO-83: [LumosOrb] answers the pointer through [lumosPointer] — hover and touch, mapped into
 * the orb's view space — without taking any gesture from its parents, and tells its source the
 * pointer has gone when it leaves composition. Driven through [SteppedScene]'s pointer
 * passthrough, on the JVM.
 */
class LumosOrbPointerTest {
    @Test
    fun `hover at the centre reports the origin and at the top-left corner minus one plus one`() {
        val source = RecordingSource()
        val scene = SteppedScene(ORB, ORB) { LumosOrb(source, LumosConfig(), Modifier.size(ORB.dp)) }
        try {
            scene.render()

            scene.sendPointerEvent(PointerEventType.Enter, CENTRE)
            scene.sendPointerEvent(PointerEventType.Move, CENTRE)
            val centre = assertNotNull(source.inputs.lastOrNull()?.pointer, "the hover at the centre was not reported")
            assertNear(0f, centre.x, "x at the centre")
            assertNear(0f, centre.y, "y at the centre")
            assertFalse(source.inputs.last().pressed)

            scene.sendPointerEvent(PointerEventType.Move, Offset.Zero)
            val corner = assertNotNull(source.inputs.last().pointer)
            assertNear(-1f, corner.x, "x at the top-left corner")
            assertNear(1f, corner.y, "y at the top-left corner")

            scene.sendPointerEvent(PointerEventType.Exit, Offset(-1f, -1f))
            assertEquals(LumosInput.NONE, source.inputs.last(), "Exit reports NONE")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a mouse button follows pressed without ending the hover`() {
        val source = RecordingSource()
        val scene = SteppedScene(ORB, ORB) { LumosOrb(source, LumosConfig(), Modifier.size(ORB.dp)) }
        try {
            scene.render()
            scene.sendPointerEvent(PointerEventType.Enter, CENTRE)
            scene.sendPointerEvent(PointerEventType.Move, CENTRE)

            scene.sendPointerEvent(PointerEventType.Press, CENTRE, PRIMARY_DOWN, PointerButton.Primary)
            assertTrue(source.inputs.last().pressed, "the press is reported")
            assertNotNull(source.inputs.last().pointer, "a press keeps the pointer")

            scene.sendPointerEvent(PointerEventType.Release, CENTRE, PointerButtons(), PointerButton.Primary)
            assertFalse(source.inputs.last().pressed, "the release is reported")
            assertNotNull(source.inputs.last().pointer, "a mouse release keeps the hover")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a touch press reports pressed and a release reports NONE`() {
        val source = RecordingSource()
        val scene = SteppedScene(ORB, ORB) { LumosOrb(source, LumosConfig(), Modifier.size(ORB.dp)) }
        try {
            scene.render()

            scene.sendTouch(PointerEventType.Press, CENTRE, pressed = true)
            val press = source.inputs.last()
            assertTrue(press.pressed, "a touch is pressed")
            assertNear(0f, assertNotNull(press.pointer).x, "x of the touch")

            scene.sendTouch(PointerEventType.Move, Offset(150f, 100f), pressed = true)
            val move = source.inputs.last()
            assertTrue(move.pressed)
            assertNear(0.5f, assertNotNull(move.pointer).x, "x of the moved touch")

            scene.sendTouch(PointerEventType.Release, Offset(150f, 100f), pressed = false)
            assertEquals(LumosInput.NONE, source.inputs.last(), "a release reports NONE")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a touch drag that starts on the orb scrolls the column and the orb lets go`() {
        val source = RecordingSource()
        var scrolled = 0
        val scene =
            SteppedScene(ORB, ORB + 100) {
                val state = rememberScrollState()
                scrolled = state.value
                Column(Modifier.verticalScroll(state)) {
                    LumosOrb(source, LumosConfig(), Modifier.size(ORB.dp))
                    Spacer(Modifier.height(1000.dp))
                }
            }
        try {
            scene.render()

            scene.sendTouch(PointerEventType.Press, Offset(100f, 150f), pressed = true)
            assertTrue(source.inputs.last().pressed, "the orb saw the touch land")
            var y = 150f
            repeat(6) {
                y -= 20f
                scene.sendTouch(PointerEventType.Move, Offset(100f, y), pressed = true)
                scene.renderNextFrame()
            }
            val letGoAt = source.inputs.indexOf(LumosInput.NONE)
            assertTrue(letGoAt > 0, "the orb never let go: ${source.inputs}")
            assertTrue(source.inputs.drop(letGoAt).all { it == LumosInput.NONE }, "the orb reported after it let go")

            scene.sendTouch(PointerEventType.Release, Offset(100f, y), pressed = false)
            scene.renderNextFrame()

            assertTrue(scrolled > 0, "the column did not scroll")
            assertEquals(LumosInput.NONE, source.inputs.last())
        } finally {
            scene.close()
        }
    }

    @Test
    fun `removing the orb from composition reports NONE`() {
        val source = RecordingSource()
        var shown by mutableStateOf(true)
        val scene =
            SteppedScene(ORB, ORB) {
                if (shown) LumosOrb(source, LumosConfig(), Modifier.size(ORB.dp))
            }
        try {
            scene.render()
            scene.sendPointerEvent(PointerEventType.Enter, CENTRE)
            scene.sendPointerEvent(PointerEventType.Move, CENTRE)
            assertNotNull(source.inputs.last().pointer)

            shown = false
            Snapshot.sendApplyNotifications()
            scene.renderNextFrame()

            assertEquals(LumosInput.NONE, source.inputs.last())
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a 30 degree tilt maps the front centre as the formula says`() {
        val source = RecordingSource()
        val config = LumosConfig(tiltDegrees = 30f)
        val scene = SteppedScene(ORB, ORB) { LumosOrb(source, config, Modifier.size(ORB.dp)) }
        try {
            scene.render()
            scene.sendPointerEvent(PointerEventType.Enter, CENTRE)
            scene.sendPointerEvent(PointerEventType.Move, CENTRE)

            val pointer = assertNotNull(source.inputs.last().pointer)
            assertNear(0f, pointer.x, "x")
            assertNear(sin(Math.toRadians(30.0)).toFloat(), pointer.y, "y = sy·cos t + √(1 − x² − sy²)·sin t")
        } finally {
            scene.close()
        }
    }

    private fun assertNear(
        expected: Float,
        actual: Float,
        label: String,
    ) = assertTrue(abs(expected - actual) < 1e-3f, "$label: expected $expected, was $actual")

    /** A source that records what it is handed, over a live THINKING scene at a small resolution. */
    private class RecordingSource(
        private val live: LiveFrameSource = LiveFrameSource(AtmospherePresets.THINKING.copy(resolution = 3)),
    ) : LumosFrameSource by live {
        val inputs = mutableListOf<LumosInput>()

        override fun setInput(input: LumosInput) {
            inputs += input
            live.setInput(input)
        }
    }

    private companion object {
        const val ORB = 200
        val CENTRE = Offset(100f, 100f)
        val PRIMARY_DOWN = PointerButtons(isPrimaryPressed = true)
    }
}
