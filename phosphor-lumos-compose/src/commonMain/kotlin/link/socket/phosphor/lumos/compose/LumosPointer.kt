package link.socket.phosphor.lumos.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import link.socket.phosphor.math.Vector2
import link.socket.phosphor.signal.LumosInput

/**
 * Report where the pointer is over this node, as a [LumosInput] in the orb's view space. Never
 * consumes.
 *
 * [LumosOrb] carries this itself. It is for a consumer who draws [LumosCanvas] directly and wants
 * the same plumbing: hand what it reports to the source's `setInput`, and the next frame answers
 * it — the cells part when the scene is `interactive`, a gazing form looks, and the frame carries
 * the pointer for a material's light.
 *
 * - **Hover where the platform has it, touch everywhere.** A mouse or stylus reports on `Enter`
 *   and every `Move`, and `NONE` on `Exit`; a button press does not end the hover, `pressed`
 *   follows the button. A touch reports on `Press` and `Move` with `pressed = true`, and `NONE` on
 *   `Release`.
 * - **Never a gesture taken from a parent.** The node reads the final pass and consumes nothing,
 *   so an orb in a scrolling column still scrolls it. A change someone else consumed — a parent's
 *   scroll past touch slop — reports `NONE`, and the node then waits for every pointer to lift.
 * - **No ghost pointer.** When the node leaves composition it reports `NONE`, so no cells stay
 *   parted around a pointer that is no longer there.
 * - A new [onInput] on recomposition is taken up without restarting the handler.
 *
 * The mapping is the inverse of [link.socket.phosphor.lumos.projection.PixelLattice]'s projection:
 * `x = 2·px / w − 1`, `y = 1 − 2·py / h`, with `+y` up, then — for a [tiltDegrees] other than 0 —
 * un-tilted onto the front of the unit sphere, `y = sy·cos t + √(max(0, 1 − x² − sy²))·sin t`.
 * That is exact on the front surface, and exact everywhere at the default tilt of 0. A point past
 * the orb's square is reported as it is: the pointer can stray past the orb.
 *
 * Each pointer event allocates one [LumosInput] and one [Vector2].
 *
 * @param tiltDegrees The tilt the orb is projected with, `LumosConfig.tiltDegrees`, so the pointer
 *  is read on the surface the viewer sees. Must be finite.
 * @param onInput Where the pointer is, each time it changes.
 */
fun Modifier.lumosPointer(
    tiltDegrees: Float = 0f,
    onInput: (LumosInput) -> Unit,
): Modifier {
    require(tiltDegrees.isFinite()) { "tiltDegrees must be finite, was $tiltDegrees" }
    return this then LumosPointerElement(tiltDegrees, onInput)
}

/**
 * A pointer at ([px], [py]) on a node [width] × [height] pixels across, in the orb's view space:
 * `[-1, 1]` across the node with `+y` up, un-tilted onto the front of the unit sphere by the tilt
 * whose cosine and sine are [cosTilt] and [sinTilt]. See [Modifier.lumosPointer].
 */
internal fun viewSpacePointer(
    px: Float,
    py: Float,
    width: Float,
    height: Float,
    cosTilt: Float = 1f,
    sinTilt: Float = 0f,
): Vector2 {
    val x = 2f * px / width - 1f
    val sy = 1f - 2f * py / height
    if (sinTilt == 0f) return Vector2(x, sy)
    val z = sqrt(max(0f, 1f - x * x - sy * sy))
    return Vector2(x, sy * cosTilt + z * sinTilt)
}

private data class LumosPointerElement(
    val tiltDegrees: Float,
    val onInput: (LumosInput) -> Unit,
) : ModifierNodeElement<LumosPointerNode>() {
    override fun create(): LumosPointerNode = LumosPointerNode(tiltDegrees, onInput)

    override fun update(node: LumosPointerNode) {
        node.onInput = onInput
        node.tilt(tiltDegrees)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "lumosPointer"
        properties["tiltDegrees"] = tiltDegrees
    }
}

private class LumosPointerNode(
    tiltDegrees: Float,
    var onInput: (LumosInput) -> Unit,
) : DelegatingNode() {
    private var tiltDegrees: Float = tiltDegrees
    private var cosTilt: Float = cos(tiltDegrees * DEGREES_TO_RADIANS).toFloat()
    private var sinTilt: Float = sin(tiltDegrees * DEGREES_TO_RADIANS).toFloat()

    init {
        delegate(SuspendingPointerInputModifierNode { track() })
    }

    // The handler reads the tilt on each event, so a new tilt never restarts it.
    fun tilt(degrees: Float) {
        if (degrees == tiltDegrees) return
        tiltDegrees = degrees
        cosTilt = cos(degrees * DEGREES_TO_RADIANS).toFloat()
        sinTilt = sin(degrees * DEGREES_TO_RADIANS).toFloat()
    }

    private suspend fun PointerInputScope.track() {
        awaitPointerEventScope {
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.isConsumed) {
                        onInput(LumosInput.NONE)
                        awaitLift(event)
                        continue
                    }
                    val hovers = change.type == PointerType.Mouse || change.type == PointerType.Stylus
                    when (event.type) {
                        PointerEventType.Exit -> onInput(LumosInput.NONE)
                        PointerEventType.Release -> if (hovers) report(change) else onInput(LumosInput.NONE)
                        PointerEventType.Enter, PointerEventType.Move, PointerEventType.Press -> report(change)
                        else -> Unit
                    }
                }
            } finally {
                onInput(LumosInput.NONE)
            }
        }
    }

    // Someone else took the gesture: say nothing more until every pointer has lifted.
    private suspend fun AwaitPointerEventScope.awaitLift(consumed: PointerEvent) {
        var event = consumed
        while (event.changes.any { it.pressed }) event = awaitPointerEvent(PointerEventPass.Final)
    }

    private fun AwaitPointerEventScope.report(change: PointerInputChange) {
        val width = size.width
        val height = size.height
        if (width <= 0 || height <= 0) return
        val position = change.position
        val pointer = viewSpacePointer(position.x, position.y, width.toFloat(), height.toFloat(), cosTilt, sinTilt)
        onInput(LumosInput(pointer = pointer, pressed = change.pressed))
    }

    private companion object {
        const val DEGREES_TO_RADIANS: Double = PI / 180.0
    }
}
