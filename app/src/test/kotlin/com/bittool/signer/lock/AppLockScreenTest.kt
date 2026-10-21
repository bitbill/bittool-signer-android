package com.bittool.signer.lock

import android.content.pm.ApplicationInfo
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsPropertiesAndroid
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.security.AppAccessState
import com.bittool.signer.core.security.testing.FakeAppAccessController
import com.bittool.signer.core.ui.password.PasswordKind
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The lock screen with its view model (iOS `AppLockView.authenticatePassword`, UIT:789-795): a wrong password clears
 * the field (it must be typed again), shows the vault's message as an announced warning and plays the warning haptic
 * (ARCHITECTURE §4.5); the right one opens the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp", sdk = [34])
class AppLockScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val wallet = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
    private val store = FakeSignerStore(SignerState(isLoading = false, walletLoadSucceeded = true, wallets = listOf(wallet)))
    private val access = FakeAppAccessController(
        AppAccessState(resolving = false, isEnabled = true, isLocked = true, biometricsAvailable = false),
    )

    @Test
    fun aWrongPasswordIsClearedAnnouncedAndFeltThenTheRightOneUnlocks() {
        val viewModel = AppLockViewModel(access, store)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { AppLockScreen(viewModel) }
        }
        compose.waitForIdle()
        // No biometrics: the password form, the only wallet chosen.
        val field = compose.onNodeWithTag(PasswordKind.WalletPassword.english)
        compose.onNodeWithTag(AppLockTags.UNLOCK).assertIsNotEnabled()

        access.passwordFailure = VaultError.AuthenticationFailed()
        field.performTextInput("Wrong-Password1!")
        compose.onNodeWithTag(AppLockTags.UNLOCK).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(AppLockTags.ERROR)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag(AppLockTags.UNLOCK).assertIsNotEnabled()
        assertEquals(HapticFeedbackConstants.REJECT, shadowOf(view).lastHapticFeedbackPerformed())
        assertTrue(access.state.value.isLocked)

        access.passwordFailure = null
        field.performTextInput("Public-Test-Password1!")
        compose.onNodeWithTag(AppLockTags.UNLOCK).performClick()
        compose.waitForIdle()
        assertEquals(listOf(wallet.id, wallet.id), access.passwordUnlocks)
        assertEquals(false, access.state.value.isLocked)
    }

    /** iOS menu `Picker("Wallet")`: one element that says the choice and opens the menu of names. */
    @Test
    fun theWalletMenuOffersTheNamesAndSaysTheChoice() {
        val second = VaultMetadata(WalletId.random(), "Travel Signer", 2L)
        store.setState { it.copy(wallets = listOf(wallet, second)) }
        val viewModel = AppLockViewModel(access, store)
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { AppLockScreen(viewModel) }
        }
        compose.waitForIdle()
        val picker = compose.onNodeWithTag(AppLockTags.WALLET)
        picker.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Choose a wallet"))
        picker.assertHasClickAction().performClick()
        compose.onNodeWithText("Travel Signer").performClick()
        compose.waitForIdle()
        picker.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Travel Signer"))
        assertEquals(second.id, viewModel.uiState.value.selectedWallet)
    }

    /**
     * Backlog B29 (the rest of B26): the wallet menu is the app's `SignerDropdownMenu`, a popup window that publishes
     * its items' tags as resource ids in debuggable builds (a raw `DropdownMenu` window had none, so UiAutomator could
     * not find a wallet by tag), and the current choice is the selected item, as in `ProfileMenu`.
     */
    @Test
    fun theWalletMenuPublishesItsWalletsByTagAndMarksTheChoice() {
        val second = VaultMetadata(WalletId.random(), "Travel Signer", 2L)
        store.setState { it.copy(wallets = listOf(wallet, second)) }
        val viewModel = AppLockViewModel(access, store)
        val info = RuntimeEnvironment.getApplication().applicationInfo
        val flags = info.flags
        info.flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
        try {
            compose.setContent {
                SignerTheme(darkTheme = false, applySystemBarAppearance = false) { AppLockScreen(viewModel) }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(AppLockTags.walletOption(second.id)).assertDoesNotExist()
            compose.onNodeWithTag(AppLockTags.WALLET).performClick()
            compose.waitForIdle()
            publishesResourceId(AppLockTags.walletOption(wallet.id)).assertExists()
            publishesResourceId(AppLockTags.walletOption(second.id)).assertIsNotSelected().performClick()
            compose.waitForIdle()
            assertEquals(second.id, viewModel.uiState.value.selectedWallet)

            compose.onNodeWithTag(AppLockTags.WALLET).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(AppLockTags.walletOption(second.id)).assertIsSelected()
            compose.onNodeWithTag(AppLockTags.walletOption(wallet.id)).assertIsNotSelected()
        } finally {
            info.flags = flags
        }
    }

    /**
     * iOS `Picker("Wallet")` lists `Text("Choose a wallet").tag(nil as UUID?)` before the wallets (AppLockView.swift:70-76,
     * app-shell.md §11.9). It is the checked choice while no wallet is chosen, and choosing it leaves "Unlock" waiting
     * for a wallet, also when the only wallet had been chosen for the person.
     */
    @Test
    fun theWalletMenuStartsWithChooseAWalletWhichLeavesNoWallet() {
        val viewModel = AppLockViewModel(access, store)
        compose.setContent {
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { AppLockScreen(viewModel) }
        }
        compose.waitForIdle()
        // No biometrics: the password form, the only wallet chosen.
        val picker = compose.onNodeWithTag(AppLockTags.WALLET)
        picker.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Public fixture 1"))
        compose.onNodeWithTag(PasswordKind.WalletPassword.english).performTextInput("Public-Test-Password1!")
        compose.onNodeWithTag(AppLockTags.UNLOCK).assertIsEnabled()

        picker.performClick()
        compose.waitForIdle()
        assertEquals(listOf(AppLockTags.NO_WALLET, AppLockTags.walletOption(wallet.id)), menuItemTags())
        compose.onNodeWithTag(AppLockTags.walletOption(wallet.id)).assertIsSelected()
        compose.onNodeWithTag(AppLockTags.NO_WALLET).assert(hasText("Choose a wallet")).assertIsNotSelected().performClick()
        compose.waitForIdle()
        picker.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Choose a wallet"))
        compose.onNodeWithTag(AppLockTags.UNLOCK).assertIsNotEnabled()
        assertEquals(null, viewModel.uiState.value.selectedWallet)

        picker.performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(AppLockTags.NO_WALLET).assertIsSelected()
        compose.onNodeWithTag(AppLockTags.walletOption(wallet.id)).assertIsNotSelected().performClick()
        compose.waitForIdle()
        picker.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Public fixture 1"))
        compose.onNodeWithTag(AppLockTags.UNLOCK).assertIsEnabled()
    }

    /** The test tags of the open menu's items, in order. */
    private fun menuItemTags(): List<String> =
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(isPopup()))
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.TestTag] }

    /** The node tagged [tag], if UiAutomator sees its tag as a resource id: the nearest flag on it or an ancestor is on. */
    private fun publishesResourceId(tag: String) =
        compose.onNode(hasTestTag(tag) and publishedAsResourceId, useUnmergedTree = true)

    private val publishedAsResourceId = SemanticsMatcher("publishes its test tag as a resource id") { node ->
        generateSequence(node) { it.parent }
            .firstOrNull { SemanticsPropertiesAndroid.TestTagsAsResourceId in it.config }
            ?.config?.get(SemanticsPropertiesAndroid.TestTagsAsResourceId) == true
    }
}
