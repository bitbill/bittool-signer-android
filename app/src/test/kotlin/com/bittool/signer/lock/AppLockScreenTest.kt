package com.bittool.signer.lock

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
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
}
