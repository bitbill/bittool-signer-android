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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.bittool.signer.core.designsystem.component.BottomBarFadeHeight
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps what the content brings into view — the field that gains focus, the caret line of the field being typed in, a
 * message that asks to be seen — visible above a bottom bar, also while that bar grows with the keyboard (QA setup-02).
 *
 * The pages with pinned actions are a `Scaffold` whose `bottomBar` is a [BottomActionBar]
 * (`com.bittool.signer.core.designsystem.component.BottomActionBar`), which rises above the keyboard, over a
 * `verticalScroll` column. Nothing in that layout re-runs bring-into-view when the bar grows as the keyboard opens: the
 * field being typed in, or the line with its caret, ends up behind the bar (or cut off at the bottom of the viewport),
 * and Compose's own "keep the focused child visible when the viewport shrinks" only follows the focused field's whole
 * box (not the caret of a tall editor) and gives up while a scroll is already running, which the frame-by-frame
 * keyboard animation always causes.
 *
 * This modifier sits between the scroll container and its content (apply it right after `verticalScroll`) and passes
 * every bring-into-view request of the content on to the scroll container, with the part of the viewport the bar
 * covers added below the requested area, plus the bar's 12 dp edge fade. When [barHeight] grows (the keyboard opening,
 * a message appearing in the bar) while a descendant has focus, the latest requests are made again, so the focused field
 * (or at least the line with its caret) moves back above the bar. Pass the scaffold's bottom padding as [barHeight] (the
 * bar's current height) and say where the scaffold padding is applied:
 *
 * - [scrollsUnderBar] `true`: the padding is applied inside the scroll, after this modifier, so the viewport reaches the
 *   bottom of the screen and the content scrolls beneath the bar. Requests are extended by the bar's height.
 *   ```
 *   Modifier.fillMaxSize()
 *       .verticalScroll(scroll)
 *       .keepInViewAboveBar(padding.calculateBottomPadding(), scrollsUnderBar = true)
 *       .padding(padding)
 *   ```
 * - [scrollsUnderBar] `false`: the padding is applied before the scroll, so the viewport already ends at the bar's top
 *   edge and shrinks as the bar grows (the setup pages, `SetupScaffold`). Requests are not extended by the bar (that
 *   would count it twice), only by its fade, and made again when it grows.
 *   ```
 *   Modifier.fillMaxSize()
 *       .padding(padding)
 *       .verticalScroll(scroll)
 *       .keepInViewAboveBar(padding.calculateBottomPadding(), scrollsUnderBar = false)
 *   ```
 *
 * There is no default for [scrollsUnderBar]: the wrong one either leaves the field under the bar or scrolls it a bar's
 * height too far. For a `verticalScroll` container only: a lazy list's own scrolling sits inside its layout, below this
 * modifier.
 */
fun Modifier.keepInViewAboveBar(barHeight: Dp, scrollsUnderBar: Boolean): Modifier =
    this then KeepInViewAboveBarElement(barHeight, scrollsUnderBar)

private data class KeepInViewAboveBarElement(val barHeight: Dp, val scrollsUnderBar: Boolean) :
    ModifierNodeElement<KeepInViewAboveBarNode>() {
    override fun create(): KeepInViewAboveBarNode = KeepInViewAboveBarNode(barHeight, scrollsUnderBar)

    override fun update(node: KeepInViewAboveBarNode) = node.update(barHeight, scrollsUnderBar)

    override fun InspectorInfo.inspectableProperties() {
        name = "keepInViewAboveBar"
        properties["barHeight"] = barHeight
        properties["scrollsUnderBar"] = scrollsUnderBar
    }
}

private class KeepInViewAboveBarNode(private var barHeight: Dp, private var scrollsUnderBar: Boolean) :
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

    fun update(height: Dp, underBar: Boolean) {
        scrollsUnderBar = underBar
        val grew = height > barHeight
        barHeight = height
        if (!grew || !isAttached || !focusInside) return
        val again = listOfNotNull(wholeElement, latest).distinct().filter { it.coordinates.isAttached }
        if (again.isEmpty()) return
        // The newest height wins: the requests made for the previous one are withdrawn. Dispatched, not run here: the
        // scroll container sees the viewport of this frame's layout (it shrinks with the bar when the padding sits
        // outside the scroll) before the requests are measured against it.
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

    /** Passes the request on to the scroll container with the part of the viewport the bar covers added below it. */
    private suspend fun forward(request: Request) {
        if (!isAttached) return
        val childCoordinates = request.coordinates
        val boundsProvider = request.bounds
        bringThisNodeIntoView {
            if (!isAttached || !childCoordinates.isAttached) return@bringThisNodeIntoView null
            val requested = boundsProvider() ?: return@bringThisNodeIntoView null
            val own = requireLayoutCoordinates()
            val topLeft = own.localPositionOf(childCoordinates, requested.topLeft)
            val covered = (if (scrollsUnderBar) barHeight else 0.dp) + BottomBarFadeHeight
            val below = with(requireDensity()) { covered.toPx() }
            Rect(topLeft.x, topLeft.y, topLeft.x + requested.width, topLeft.y + requested.height + below)
        }
    }
}
