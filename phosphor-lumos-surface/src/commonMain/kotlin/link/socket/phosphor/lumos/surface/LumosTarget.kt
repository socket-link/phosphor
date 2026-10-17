package link.socket.phosphor.lumos.surface

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints

/**
 * Make this node a target the orb's cells can become: it publishes its bounds to the nearest
 * [LumosSurface]'s state as target [id], and draws at [LumosSurfaceState.alphaFor] — 0 until the
 * cells have become it, their arrival while they do, 1 once it is solid.
 *
 * One modifier on the consumer's existing control, before the modifiers that size and paint it,
 * so the bounds published are the control's. `clickable`, focus and semantics are the control's
 * own and are untouched: the control is laid out and clickable at alpha 0, as in Dots, so a
 * user who finds it before the cells do is not refused.
 *
 * @param id The target's name, as passed to [LumosSurfaceState.assembleInto].
 * @param cornerRadiusPx The control's corner radius in pixels, for the rounded rectangle the
 *  cells trace; a modifier cannot read a sibling `clip` or `background` shape. Clamped to half
 *  the shorter side, so a radius past that is a pill.
 * @throws IllegalStateException at layout when there is no [LumosSurface] above this node.
 * @throws IllegalArgumentException when [cornerRadiusPx] is negative or not finite.
 */
fun Modifier.lumosTarget(
    id: String,
    cornerRadiusPx: Float = 0f,
): Modifier {
    require(cornerRadiusPx >= 0f && cornerRadiusPx.isFinite()) {
        "cornerRadiusPx must be >= 0 and finite, was $cornerRadiusPx"
    }
    return this then LumosTargetElement(id, cornerRadiusPx)
}

private data class LumosTargetElement(
    val id: String,
    val cornerRadiusPx: Float,
) : ModifierNodeElement<LumosTargetNode>() {
    override fun create(): LumosTargetNode = LumosTargetNode(id, cornerRadiusPx)

    override fun update(node: LumosTargetNode) {
        node.id = id
        node.cornerRadiusPx = cornerRadiusPx
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "lumosTarget"
        properties["id"] = id
        properties["cornerRadiusPx"] = cornerRadiusPx
    }
}

// Bounds from onGloballyPositioned; alpha through the placement layer, whose block is observed,
// so an arrival change updates the layer without a recomposition or a relayout.
private class LumosTargetNode(
    var id: String,
    var cornerRadiusPx: Float,
) : Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    GlobalPositionAwareModifierNode,
    LayoutModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val bounds = coordinates.boundsInRoot()
        if (bounds.isEmpty) return
        currentValueOf(LocalLumosSurfaceState).targetPositioned(id, bounds, cornerRadiusPx)
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        val state = currentValueOf(LocalLumosSurfaceState)
        val id = id
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) { alpha = state.alphaFor(id) }
        }
    }
}
