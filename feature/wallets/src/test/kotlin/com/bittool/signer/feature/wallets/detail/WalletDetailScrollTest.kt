package com.bittool.signer.feature.wallets.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.feature.wallets.WalletFixtures
import com.bittool.signer.feature.wallets.common.WalletIdentity
import kotlin.test.assertEquals
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
 * first row instead of its end. iOS leaves the page where it was (`WalletDetailView.swift:117-125`).
 *
 * The tab bar is modelled as a 128 dp block under the page (80 dp bar + 48 dp navigation bar on the QA device).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp-mdpi")
class WalletDetailScrollTest {
    @get:Rule
    val compose = createComposeRule()

    private var barHeight by mutableStateOf(TAB_BAR)
    private var renaming by mutableStateOf(false)

    private fun showPage() {
        val state = WalletFixtures.state()
        val identity = WalletIdentity.of(state, WalletFixtures.readyId)
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
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
    }

    private val list get() = compose.onNode(hasScrollAction())

    private fun scrollToTheEnd() {
        // header, pair, addresses, security, rename, delete
        list.performScrollToIndex(5)
        compose.waitForIdle()
        assertTrue(isAtTheEnd(), "scrolled to the end")
    }

    private fun isAtTheEnd(): Boolean {
        val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return range.value() == range.maxValue()
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getBoundsInRoot()

    /** The tab bar slides away and back in [steps] frames (`MainScaffold`'s animated bar), or at once. */
    private fun moveBar(to: Dp, steps: Int = 1) {
        val from = barHeight
        (1..steps).forEach { step ->
            barHeight = from + (to - from) * (step.toFloat() / steps)
            compose.waitForIdle()
        }
    }

    @Test
    fun renamingAtTheEndOfThePageLeavesThePageWhereItWas() {
        showPage()
        scrollToTheEnd()
        val delete = bounds("detail.delete")
        val rename = bounds("detail.rename")

        compose.onNodeWithTag("detail.rename").performClick()
        compose.waitForIdle()
        moveBar(to = 0.dp) // the dialog's keyboard is up
        compose.onNodeWithTag("rename.cancel").performClick()
        compose.waitForIdle()
        moveBar(to = TAB_BAR) // the keyboard went away with the dialog

        assertEquals(rename, bounds("detail.rename"), "Rename Wallet did not move")
        assertEquals(delete, bounds("detail.delete"), "Delete Wallet is not under the tab bar")
        assertTrue(isAtTheEnd())
    }

    @Test
    fun anAnimatedTabBarLeavesThePageAtItsEndToo() {
        showPage()
        scrollToTheEnd()
        val delete = bounds("detail.delete")
        moveBar(to = 0.dp, steps = 8)
        moveBar(to = TAB_BAR, steps = 8)
        assertEquals(delete, bounds("detail.delete"))
        assertTrue(isAtTheEnd())
    }

    /** A page that is not at its end keeps its first visible row, as before: nothing is scrolled for it. */
    @Test
    fun aPageAboveItsEndKeepsItsPosition() {
        showPage()
        list.performTouchInput { swipeUp(startY = centerY, endY = centerY - 120f) }
        compose.waitForIdle()
        assertTrue(!isAtTheEnd(), "scrolled part of the way")
        val evm = bounds("detail.connect.evm")
        moveBar(to = 0.dp)
        moveBar(to = TAB_BAR)
        assertEquals(evm, bounds("detail.connect.evm"), "the rows stay where they were")
    }

    /** The page as it opens (not scrolled): the header stays at the top. */
    @Test
    fun anUnscrolledPageKeepsItsHeaderAtTheTop() {
        showPage()
        val fingerprint = bounds("detail.fingerprint")
        moveBar(to = 0.dp)
        moveBar(to = TAB_BAR)
        assertEquals(fingerprint, bounds("detail.fingerprint"))
    }

    private companion object {
        val TAB_BAR = 128.dp
    }
}
