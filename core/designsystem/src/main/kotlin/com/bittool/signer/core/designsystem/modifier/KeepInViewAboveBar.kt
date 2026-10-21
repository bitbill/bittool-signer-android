package com.bittool.signer.core.designsystem.modifier

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.relocation.bringIntoView as bringThisNodeIntoView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps what the content brings into view — the field that gains focus, the caret line of the field being typed in, a
 * message that asks to be seen — clear of a bottom bar the content scrolls beneath, also while that bar grows with the
 * keyboard (QA setup-02).
 *
 * The layout the app uses for pages with pinned actions is a `Scaffold` whose `bottomBar` is a [BottomActionBar]
 * (`com.bittool.signer.core.designsystem.component.BottomActionBar`), which rises above the keyboard, and a
 * `verticalScroll` column that applies the scaffold padding inside the scroll, so the content scrolls under the bar.
 * The scroll container then counts the area under the bar as visible: a focused field half under the bar, or the line
 * being typed, is never scrolled up, and nothing re-runs bring-into-view when the bar grows as the keyboard opens.
 *
 * This modifier sits between the scroll container and its content. Every bring-into-view request from the content
 * passes through it and is extended by [barHeight] below, so the scroll stops with the requested area above the bar.
 * When [barHeight] grows (the keyboard opening, a message appearing in the bar) while a descendant has focus, the latest
 * requests are made again, so the focused field (or at least the line with its caret) moves back above the bar. Apply it after `verticalScroll`
 * and before the scaffold padding, passing the scaffold's bottom padding (the bar's current height):
 *
 * ```
 * Column(
 *     Modifier.fillMaxSize()
 *         .verticalScroll(scroll)
 *         .keepInViewAboveBar(padding.calculateBottomPadding())
 *         .padding(padding),
 * )
 * ```
 *
 * For a `verticalScroll` container only: a lazy list's own scrolling sits inside its layout, below this modifier.
 */
fun Modifier.keepInViewAboveBar(barHeight: Dp): Modifier = this then KeepInViewAboveBarElement(barHeight)

private data class KeepInViewAboveBarElement(val barHeight: Dp) : ModifierNodeElement<KeepInViewAboveBarNode>() {
    override fun create(): KeepInViewAboveBarNode = KeepInViewAboveBarNode(barHeight)

    override fun update(node: KeepInViewAboveBarNode) = node.updateBarHeight(barHeight)

    override fun InspectorInfo.inspectableProperties() {
        name = "keepInViewAboveBar"
        properties["barHeight"] = barHeight
    }
}

private class KeepInViewAboveBarNode(private var barHeight: Dp) :
    Modifier.Node(), BringIntoViewModifierNode, FocusEventModifierNode {

    private class Request(val coordinates: LayoutCoordinates, val bounds: () -> Rect?)

    /**
     * The latest request for a whole element (a field that gained focus asks for its whole box) and the latest request
     * of any kind (the caret of the field being typed in asks for its line). Made again together, the scroll container
     * shows the larger one when it fits above the bar, else the caret line.
     */
    private var wholeElement: Request? = null
    private var latest: Request? = null
    private var focusInside = false
    private var repeat: Job? = null

    override val shouldAutoInvalidate: Boolean get() = false

    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
        val request = Request(childCoordinates, boundsProvider)
        val asked = boundsProvider()
        val whole = asked != null && asked.topLeft == Offset.Zero && asked.size == childCoordinates.size.toSize()
        if (whole) wholeElement = request
        latest = request
        forward(request)
    }

    override fun onFocusEvent(focusState: FocusState) {
        focusInside = focusState.hasFocus
        if (!focusInside) {
            wholeElement = null
            latest = null
            repeat?.cancel()
        }
    }

    fun updateBarHeight(height: Dp) {
        val grew = height > barHeight
        barHeight = height
        if (!grew || !isAttached || !focusInside) return
        val again = listOfNotNull(wholeElement, latest).distinct().filter { it.coordinates.isAttached }
        if (again.isEmpty()) return
        // The newest height wins: the requests made for the previous one are withdrawn.
        repeat?.cancel()
        repeat = coroutineScope.launch {
            for (request in again) launch { forward(request) }
        }
    }

    override fun onDetach() {
        wholeElement = null
        latest = null
        repeat = null
    }

    /** Passes the request on to the scroll container with the area under the bar added below it. */
    private suspend fun forward(request: Request) {
        if (!isAttached) return
        val childCoordinates = request.coordinates
        val boundsProvider = request.bounds
        bringThisNodeIntoView {
            if (!isAttached || !childCoordinates.isAttached) return@bringThisNodeIntoView null
            val requested = boundsProvider() ?: return@bringThisNodeIntoView null
            val own = requireLayoutCoordinates()
            val topLeft = own.localPositionOf(childCoordinates, requested.topLeft)
            val below = with(requireDensity()) { barHeight.toPx() }
            Rect(topLeft.x, topLeft.y, topLeft.x + requested.width, topLeft.y + requested.height + below)
        }
    }
}
