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
 * Gives a lazy list's rows back their place when its viewport shrinks again after it grew (QA regression-04).
 *
 * A `LazyColumn` keeps its first visible row where it is when its height changes, with one exception: it never shows
 * empty space after its last row, so near its end it scrolls back by itself when it gets taller (or when the space
 * after its last row gets shorter). It does not scroll forward again when it gets its old height back; it keeps the
 * first row and cuts the end off instead. On the wallet page that happened behind the "Rename Wallet" dialog: the
 * dialog's keyboard hid the tab bar, so the page grew and shrank again (and its bottom padding followed the
 * navigation bar inset the bar stops and starts consuming). After Cancel or Save the page sat up to about 128 dp lower
 * than before, with Delete Wallet under the tab bar. iOS leaves the page where it was. The shell now keeps the bar for
 * a dialog's keyboard (`MainScaffold`, only this window's keyboard moves it); this node still keeps the rows in place
 * whenever the page's height changes back and forth (the bar going for a keyboard of the page, a window resize).
 *
 * Place it before the list's size modifiers (`Modifier.undoesScrollBackOnShrink(state).fillMaxSize()`). It adds up how
 * far the list moved its rows down by itself since it was last scrolled, and when the list's maximum height shrinks it
 * requests the same distance forward again (at most by how much the height shrank) before the list measures. The list
 * then lays out in that pass at the requested position, so the rows follow the viewport's edge back and end exactly
 * where they were, with no frame cut off. A page that is not near its end, or not scrolled, is never moved. A scroll
 * (a drag, a fling, an accessibility or programmatic scroll) starts the count again: nothing pulls the page back to a
 * position the person scrolled away from.
 */
internal fun Modifier.undoesScrollBackOnShrink(state: LazyListState): Modifier = this then UndoScrollBackElement(state)

private data class UndoScrollBackElement(val state: LazyListState) : ModifierNodeElement<UndoScrollBackNode>() {
    override fun create() = UndoScrollBackNode(state)

    override fun update(node: UndoScrollBackNode) {
        node.state = state
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "undoesScrollBackOnShrink"
        properties["state"] = state
    }
}

/** The first visible row and its offset in the viewport (px, negative when scrolled partly out). */
private data class Anchor(val key: Any, val offset: Int)

private class UndoScrollBackNode(state: LazyListState) : Modifier.Node(), LayoutModifierNode {
    var state: LazyListState = state
        set(value) {
            if (field !== value) reset()
            field = value
        }

    /** The maximum height of the previous measure, or -1 before the first one (or while unbounded). */
    private var lastMaxHeight = -1

    /** The first visible row after the previous measure, to tell a scroll between two measures. */
    private var lastAnchor: Anchor? = null

    /** How far (px) the list moved its rows down by itself since it was last scrolled, not yet given back. */
    private var scrolledBack = 0

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        if (isLookingAhead) {
            val placeable = measurable.measure(constraints)
            return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else -1
        // The list's state from its previous measure, read without subscribing: this node must not measure again for
        // every scroll.
        val list = state
        val (before, scrolling) = Snapshot.withoutReadObservation { list.anchor() to list.isScrollInProgress }
        // A scroll applied between two measures (small drags are, without a measure) moved the rows itself.
        if (scrolling || before == null || before != lastAnchor) scrolledBack = 0
        var given = 0
        if (scrolledBack > 0 && maxHeight in 0 until lastMaxHeight) {
            given = minOf(scrolledBack, lastMaxHeight - maxHeight)
            scrolledBack -= given
            Snapshot.withoutReadObservation {
                list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset + given)
            }
        }
        lastMaxHeight = maxHeight
        val placeable = measurable.measure(constraints)
        val (after, rowOffset) = Snapshot.withoutReadObservation { list.anchor() to before?.let { list.offsetOf(it.key) } }
        // How far that row moved down in this pass beyond the distance given back: the list scrolled back by itself.
        // During a scroll, or moved up further than requested, or out of view, it was scrolled.
        val scrolledBackNow = if (before != null && rowOffset != null) rowOffset - (before.offset - given) else null
        scrolledBack = when {
            scrolling || scrolledBackNow == null || scrolledBackNow < 0 -> 0
            else -> scrolledBack + scrolledBackNow
        }
        lastAnchor = after
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun onDetach() = reset()

    private fun reset() {
        lastMaxHeight = -1
        lastAnchor = null
        scrolledBack = 0
    }
}

private fun LazyListState.anchor(): Anchor? {
    val index = firstVisibleItemIndex
    return layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.let { Anchor(it.key, it.offset) }
}

private fun LazyListState.offsetOf(key: Any): Int? = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset
