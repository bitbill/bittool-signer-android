package com.bittool.signer.feature.wallets.detail

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.feature.wallets.WalletFixtures
import com.bittool.signer.feature.wallets.common.WalletIdentity
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * QA regression-04: scrolled to the bottom of the wallet page, "Rename Wallet" → Cancel or Save left the page about
 * 128 dp lower, with Delete Wallet under the tab bar. The dialog's keyboard hides the tab bar (it slides away while a
 * keyboard is up) and brings it back afterwards. The page grows and shrinks behind the dialog, and the list kept its
 * first row instead of undoing the scroll back it made while it grew. iOS leaves the page where it was
 * (`WalletDetailView.swift:117-125`).
 *
 * The shell is modelled as `MainScaffold` builds it on the QA device (mdpi here, so 1 px = 1 dp): a 48 dp navigation
 * bar inset, and under the page an 80 dp tab bar that pads itself by that inset (128 dp in all). While the bar is
 * shown the page's container consumes the navigation bar inset; when the bar goes, it stops consuming it at once (the
 * page's bottom padding grows by 48 dp) and the bar collapses over a few frames. Coming back, the inset is consumed
 * again at once and the bar expands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class WalletDetailScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private var barShown by mutableStateOf(true)
    private var barHeight by mutableStateOf(TAB_BAR)
    private var renaming by mutableStateOf(false)

    private fun showPage() {
        val state = WalletFixtures.state()
        val identity = WalletIdentity.of(state, WalletFixtures.readyId)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Column(Modifier.fillMaxSize()) {
                    val consumed = if (barShown) WindowInsets.navigationBars.only(WindowInsetsSides.Bottom) else WindowInsets(0)
                    Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(consumed)) {
                        WalletDetailContent(
                            uiState = WalletDetailUiState(walletId = WalletFixtures.readyId, identity = identity),
                            actions = WalletDetailActions(),
                            toast = rememberStatusToastState(),
                            onCopyFingerprint = {},
                            onRename = { renaming = true },
                        )
                    }
                    Spacer(Modifier.fillMaxWidth().height(barHeight))
                }
                if (renaming) RenameWalletDialog(currentName = identity?.name.orEmpty(), onSave = { renaming = false }, onCancel = { renaming = false })
            }
        }
        compose.runOnIdle {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_BAR))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        compose.waitForIdle()
    }

    private var pageShown = false

    /** [showPage] for a test that walks through several positions: the content is set once, then scrolled to the top. */
    private fun showPageOnce() {
        if (pageShown) {
            list.performScrollToIndex(0)
            compose.waitForIdle()
        } else {
            showPage()
            pageShown = true
        }
    }

    private val list get() = compose.onNode(hasScrollAction())

    private fun scrollToTheEnd() {
        // header, pair, addresses, security, rename, delete
        list.performScrollToIndex(5)
        compose.waitForIdle()
        assertTrue(isAtTheEnd(), "scrolled to the end")
    }

    /**
     * Scrolled to the end, then [distance] back up: the end of the page is [distance] below the tab bar. Measured on
     * Delete Wallet, the last row (a lazy list's scroll range is an estimate from its average row height).
     */
    private fun scrollToAboveTheEnd(distance: Dp) {
        scrollToTheEnd()
        val atTheEnd = top("detail.delete")
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -distance.value) }
        compose.waitForIdle()
        assertEquals(atTheEnd + distance.value, top("detail.delete"), "the end of the page is $distance away")
    }

    /** Where the row tagged [tag] starts (px, not clipped to the list's viewport like its bounds). */
    private fun top(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    /** At the end, the list reports its scroll position as its maximum. */
    private fun isAtTheEnd(): Boolean {
        val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return range.value() == range.maxValue()
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getBoundsInRoot()

    /**
     * The keyboard comes up ([shown] false) or goes away ([shown] true): the page's container stops or starts consuming
     * the navigation bar inset at once, then the tab bar collapses or expands in [steps] frames (`MainScaffold`'s
     * `AnimatedVisibility`), or at once.
     */
    private fun moveBar(shown: Boolean, steps: Int = 1) {
        barShown = shown
        compose.waitForIdle()
        val from = barHeight
        val to = if (shown) TAB_BAR else 0.dp
        (1..steps).forEach { step ->
            barHeight = from + (to - from) * (step.toFloat() / steps)
            compose.waitForIdle()
        }
    }

    /** Opens "Rename Wallet", raises the keyboard (the tab bar goes), cancels, and lets the keyboard go. */
    private fun renameAndCancel(steps: Int = 1) {
        compose.onNodeWithTag("detail.rename").performClick()
        compose.waitForIdle()
        moveBar(shown = false, steps = steps)
        compose.onNodeWithTag("rename.cancel").performClick()
        compose.waitForIdle()
        moveBar(shown = true, steps = steps)
    }

    @Test
    fun renamingAtTheEndOfThePageLeavesThePageWhereItWas() {
        showPage()
        scrollToTheEnd()
        val delete = bounds("detail.delete")
        val rename = bounds("detail.rename")

        renameAndCancel()

        assertEquals(rename, bounds("detail.rename"), "Rename Wallet did not move")
        assertEquals(delete, bounds("detail.delete"), "Delete Wallet is not under the tab bar")
        assertTrue(isAtTheEnd())
    }

    @Test
    fun anAnimatedTabBarLeavesThePageAtItsEndToo() {
        showPage()
        scrollToTheEnd()
        val delete = bounds("detail.delete")
        renameAndCancel(steps = 8)
        assertEquals(delete, bounds("detail.delete"))
        assertTrue(isAtTheEnd())
    }

    /**
     * Rename tapped with the end of the page a little below the tab bar (less than the tab bar's height): the page
     * also comes back exactly where it was, neither lower (the list kept its first row) nor scrolled to its end.
     */
    @Test
    fun renamingNearTheEndOfThePageLeavesThePageWhereItWas() {
        listOf(40.dp, 100.dp).forEach { distance ->
            showPageOnce()
            scrollToAboveTheEnd(distance)
            val rename = top("detail.rename")
            val delete = top("detail.delete")
            renameAndCancel(steps = 8)
            assertEquals(rename, top("detail.rename"), "Rename Wallet did not move ($distance above the end)")
            assertEquals(delete, top("detail.delete"), "Delete Wallet did not move ($distance above the end)")
        }
    }

    /** The model itself: with the bar away the page pads its end by the navigation bar, as under `MainScaffold`. */
    @Test
    fun withTheBarAwayThePageEndsANavigationBarHigher() {
        showPage()
        scrollToTheEnd()
        val delete = top("detail.delete")
        barShown = false
        compose.waitForIdle()
        assertFalse(isAtTheEnd(), "48 dp more after the last row")
        scrollToTheEnd()
        assertEquals(delete - NAV_BAR, top("detail.delete"))
    }

    /** A page that is not near its end keeps its rows where they were: nothing is scrolled for it. */
    @Test
    fun aPageAboveItsEndKeepsItsPosition() {
        showPage()
        list.performTouchInput { swipeUp(startY = centerY, endY = centerY - 120f) }
        compose.waitForIdle()
        assertFalse(isAtTheEnd(), "scrolled part of the way")
        val evm = bounds("detail.connect.evm")
        moveBar(shown = false)
        moveBar(shown = true)
        assertEquals(evm, bounds("detail.connect.evm"), "the rows stay where they were")
    }

    /** The page as it opens (not scrolled): the header stays at the top. */
    @Test
    fun anUnscrolledPageKeepsItsHeaderAtTheTop() {
        showPage()
        val fingerprint = bounds("detail.fingerprint")
        moveBar(shown = false)
        moveBar(shown = true)
        assertEquals(fingerprint, bounds("detail.fingerprint"))
    }

    /**
     * Scrolled by the person while the bar is away: the page then stays where they left it when the bar comes back
     * (it keeps its first row, the list's own rule), and nothing pulls it back to an earlier position.
     */
    @Test
    fun aScrollWhileTheBarIsAwayIsKept() {
        showPage()
        scrollToTheEnd()
        moveBar(shown = false, steps = 8)
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -300f) }
        compose.waitForIdle()
        val evm = bounds("detail.connect.evm")
        moveBar(shown = true, steps = 8)
        assertEquals(evm, bounds("detail.connect.evm"), "the rows stay where the scroll left them")
    }

    private companion object {
        const val NAV_BAR = 48
        val TAB_BAR = 80.dp + NAV_BAR.dp
    }
}
