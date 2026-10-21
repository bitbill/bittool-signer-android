package com.bittool.signer.feature.wallets

import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.feature.wallets.addresses.AddressDetailSheet
import com.bittool.signer.feature.wallets.addresses.AddressesActions
import com.bittool.signer.feature.wallets.addresses.AddressesContent
import com.bittool.signer.feature.wallets.addresses.AddressesUiState
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.detail.RenameWalletDialog
import com.bittool.signer.feature.wallets.detail.UnableToRenameDialog
import com.bittool.signer.feature.wallets.detail.WalletDetailActions
import com.bittool.signer.feature.wallets.detail.WalletDetailContent
import com.bittool.signer.feature.wallets.detail.WalletDetailUiState
import com.bittool.signer.feature.wallets.list.WalletsActions
import com.bittool.signer.feature.wallets.list.WalletsContent
import com.bittool.signer.feature.wallets.list.WalletsUiState
import com.bittool.signer.feature.wallets.security.DeletePasswordDialog
import com.bittool.signer.feature.wallets.security.RemoveLostWalletDialog
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The Wallets dialogs and the address sheet hand the keyboard focus back cleanly (QA wallets-12): confirmed with the
 * Enter key, they no longer leave the page with a focus ring on its first control (the back arrow after "Rename
 * Wallet"), the focus Android gives a window that comes back in keyboard mode with nothing focused.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2, Build.VERSION_CODES.BAKLAVA], qualifiers = "w400dp-h2400dp-xhdpi")
class DialogFocusReturnTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val decor: ViewGroup get() = view.rootView as ViewGroup

    private fun content(body: @Composable () -> Unit) {
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { body() }
        }
        compose.waitForIdle()
        WindowSignals.setTouchMode(view, inTouchMode = true)
        WindowSignals.windowFocus(view, hasFocus = true)
    }

    @Test
    fun savingTheNewNameWithTheEnterKeyLeavesNoFocusRingOnTheBackArrow() {
        val state = WalletFixtures.state()
        val identity = WalletIdentity.of(state, WalletFixtures.readyId)
        var renaming by mutableStateOf(false)
        content {
            WalletDetailContent(
                uiState = WalletDetailUiState(walletId = WalletFixtures.readyId, identity = identity),
                actions = WalletDetailActions(),
                toast = rememberStatusToastState(),
                onCopyFingerprint = {},
                onRename = { renaming = true },
            )
            if (renaming) RenameWalletDialog(currentName = identity?.name.orEmpty(), onSave = { renaming = false }, onCancel = { renaming = false })
        }
        compose.onNodeWithTag("detail.rename").performScrollTo().performClick()
        compose.waitForIdle()
        // The dialog takes the window focus, and the person types the name on a hardware keyboard.
        WindowSignals.windowFocus(view, hasFocus = false)
        WindowSignals.leaveTouchModeIn(ShadowDialog.getLatestDialog().window!!.decorView, view)
        compose.onNodeWithTag("rename.name").performImeAction()
        compose.waitForIdle()
        WindowSignals.windowFocus(view, hasFocus = true)
        compose.waitForIdle()

        compose.onNodeWithTag("nav.back").assertIsNotFocused()
        assertNull(decor.findFocus(), "nothing on the page is focused")
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, decor.descendantFocusability, "the page takes the focus again")
    }

    /**
     * The "Add Wallet" menu is a popup window too: opened with a tap and chosen with the arrow keys and Enter, it left the
     * focus ring on "Scan" (the page's first control) under the setup flow it opened.
     */
    @Test
    fun choosingFromTheAddWalletMenuWithTheKeyboardLeavesNoFocusRingOnScan() {
        val state = WalletFixtures.state()
        var added: SetupIntent? = null
        content {
            WalletsContent(
                WalletsUiState(state.wallets.mapNotNull { WalletIdentity.of(state, it.id) }, loaded = true),
                WalletsActions(onAddWallet = { added = it }),
            )
        }
        compose.onNodeWithTag("wallets.add").performClick()
        compose.waitForIdle()
        WindowSignals.windowFocus(view, hasFocus = false)
        WindowSignals.leaveTouchModeIn(WindowSignals.topWindowRoot(), view)
        compose.onNodeWithTag("wallets.add.import").performClick()
        compose.waitForIdle()
        WindowSignals.windowFocus(view, hasFocus = true)
        compose.waitForIdle()

        assertEquals(SetupIntent.Restore, added)
        compose.onNodeWithContentDescription("Scan").assertIsNotFocused()
        assertNull(decor.findFocus(), "nothing on the page is focused")
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, decor.descendantFocusability, "the page takes the focus again")
    }

    /** The same for an address row's long-press menu ("Copy Address", "Show QR Code"). */
    @Test
    fun choosingFromAnAddressMenuWithTheKeyboardLeavesNoFocusRingOnTheBackArrow() {
        content {
            AddressesContent(
                AddressesUiState(chain = Chain.Bitcoin, accounts = WalletFixtures.accounts(Chain.Bitcoin, 0, 3), loaded = true),
                AddressesActions(),
            )
        }
        compose.onNodeWithTag("address.row.0").performTouchInput { longClick() }
        compose.waitForIdle()
        WindowSignals.windowFocus(view, hasFocus = false)
        WindowSignals.leaveTouchModeIn(WindowSignals.topWindowRoot(), view)
        compose.onNodeWithText("Copy Address").performClick()
        compose.waitForIdle()
        WindowSignals.windowFocus(view, hasFocus = true)
        compose.waitForIdle()

        compose.onNodeWithTag("nav.back").assertIsNotFocused()
        assertNull(decor.findFocus(), "nothing on the page is focused")
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, decor.descendantFocusability, "the page takes the focus again")
    }

    /**
     * What the design system's windows hold (`WindowFocusHold`): the decor's child that holds the page, so the page's
     * views take no focus while the dialog or sheet is up.
     */
    private val pageGroup: ViewGroup
        get() {
            var child: View = view
            while ((child.parent as? View) !== decor) child = child.parent as View
            return child as ViewGroup
        }

    /**
     * The design system's dialog and sheet hold the page themselves (backlog B30); the Wallets dialogs no longer add
     * a hold of their own.
     */
    @Test
    fun everyWalletsDialogAndTheAddressSheetHoldTheFocusWhileShown() {
        var shown by mutableStateOf(0)
        val dialogs: List<@Composable () -> Unit> = listOf(
            { RenameWalletDialog(currentName = "Public fixture 1", onSave = {}, onCancel = {}) },
            { UnableToRenameDialog(message = "Choose a wallet name of 1–40 characters.", onDismiss = {}) },
            { DeletePasswordDialog(onDelete = { it.wipe() }, onCancel = {}, onBlocked = {}) },
            { RemoveLostWalletDialog(onRemove = {}, onCancel = {}, onBlocked = {}) },
            { AddressDetailSheet(account = WalletFixtures.accounts(Chain.Bitcoin, 0, 1).first(), title = "Bitcoin address #0", onDismiss = {}) },
        )
        content { if (shown in 1..dialogs.size) dialogs[shown - 1]() }
        val pageFocusability = pageGroup.descendantFocusability
        val windowFocusability = decor.descendantFocusability
        dialogs.indices.forEach { index ->
            shown = index + 1
            compose.waitForIdle()
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, pageGroup.descendantFocusability, "dialog ${index + 1} holds the page's focus")
            WindowSignals.windowFocus(view, hasFocus = false)
            shown = 0
            compose.waitForIdle()
            WindowSignals.windowFocus(view, hasFocus = true)
            assertEquals(pageFocusability, pageGroup.descendantFocusability, "released after dialog ${index + 1}")
            assertEquals(windowFocusability, decor.descendantFocusability, "the window itself is never blocked")
        }
    }
}
