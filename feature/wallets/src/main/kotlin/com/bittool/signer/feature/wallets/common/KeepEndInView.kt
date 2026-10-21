package com.bittool.signer.feature.wallets.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints

/**
 * Keeps a lazy list that rests at its end at its end when its viewport gets shorter (QA regression-04).
 *
 * A `LazyColumn` keeps its first visible row where it is when its height changes. When it grows at its end, it scrolls
 * back by itself (it never shows empty space after its last row). When it shrinks again, it keeps the first row and
 * cuts the end off. On the wallet page that happened behind the "Rename Wallet" dialog: the dialog's keyboard hides the
 * tab bar, so the page grows and shrinks again. After Cancel or Save the page sat about 128 dp lower than before, with
 * Delete Wallet under the tab bar. iOS leaves the page where it was.
 *
 * Place it before the list's size modifiers (`Modifier.keepsEndInView(state).fillMaxSize()`). When the list's maximum
 * height shrinks while the list is scrolled to its end ([LazyListState.canScrollForward] false after a scroll), the
 * last row is requested before the list measures. The list then lays out against its end in the same pass, with no
 * frame shown cut off. A list that is not scrolled, or not at its end, keeps its first visible row as usual.
 */
internal fun Modifier.keepsEndInView(state: LazyListState): Modifier = this then KeepEndInViewElement(state)

private data class KeepEndInViewElement(val state: LazyListState) : ModifierNodeElement<KeepEndInViewNode>() {
    override fun create() = KeepEndInViewNode(state)

    override fun update(node: KeepEndInViewNode) {
        node.state = state
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "keepsEndInView"
        properties["state"] = state
    }
}

private class KeepEndInViewNode(var state: LazyListState) : Modifier.Node(), LayoutModifierNode {
    /** The maximum height of the previous measure, or -1 before the first one (or while unbounded). */
    private var lastMaxHeight = -1

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else -1
        if (maxHeight in 0 until lastMaxHeight) keepEnd()
        lastMaxHeight = maxHeight
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun onDetach() {
        lastMaxHeight = -1
    }

    /**
     * The list's state from its previous measure, read without subscribing: this node must not measure again for every
     * scroll. The request only applies to the list's next measure (this pass), which lays the last row out against the
     * end of the shorter viewport.
     */
    private fun keepEnd() = Snapshot.withoutReadObservation {
        val list = state
        val last = list.layoutInfo.totalItemsCount - 1
        if (last >= 0 && list.canScrollBackward && !list.canScrollForward) list.requestScrollToItem(last)
    }
}
