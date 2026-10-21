package com.bittool.signer.feature.wallets.security

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.feature.wallets.WalletFixtures
import com.bittool.signer.feature.wallets.WindowSignals
import com.bittool.signer.feature.wallets.common.WalletIdentity
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Submitting a wallet security page with a hardware keyboard (keyboard mode) ends the editing with nothing focused, as
 * iOS does (`focus = nil`): a plain `clearFocus()` gave the focus to the page's first control, the Cancel cross, and
 * left a focus ring on it while the page worked (QA regression-02/03 class; `rememberClearFocus`).
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [Build.VERSION_CODES.S_V2, Build.VERSION_CODES.BAKLAVA], qualifiers = "w400dp-h2400dp-xhdpi")
class SecurityPageFocusTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: View
    private val identity = WalletFixtures.state().let { WalletIdentity.of(it, WalletFixtures.readyId) }

    private fun page(body: @Composable () -> Unit) {
        compose.setContent {
            view = LocalView.current
            SignerTheme(darkTheme = false, applySystemBarAppearance = false) { body() }
        }
        compose.waitForIdle()
        WindowSignals.setTouchMode(view, inTouchMode = false)
    }

    private fun assertNothingFocused() {
        compose.onNodeWithTag("nav.close").assertIsNotFocused()
        compose.runOnIdle { assertNull(view.rootView.findFocus(), "no focus ring on the page") }
    }

    @Test
    fun repairConfirmedWithEnterLeavesNothingFocused() {
        var submitted = 0
        page {
            RepairWalletContent(
                uiState = SecurityPageUiState(identity = identity),
                fields = remember { PasswordFields(1) },
                toast = rememberStatusToastState(),
                onSubmit = { submitted++ },
                onCancel = {},
            )
        }
        val field = compose.onNodeWithTag(PasswordKind.WalletPassword.english)
        field.requestFocus()
        field.performTextInput("correct horse")
        field.assertIsFocused()
        field.performImeAction()
        compose.waitForIdle()

        assertEquals(1, submitted)
        assertNothingFocused()
    }

    @Test
    fun changePasswordConfirmedFromTheKeyboardLeavesNothingFocused() {
        var submitted = 0
        page {
            val fields = remember { PasswordFields(3) }
            ChangePasswordContent(
                uiState = SecurityPageUiState(identity = identity),
                fields = fields,
                toast = rememberStatusToastState(),
                onSubmit = { submitted++ },
                onCancel = {},
            )
        }
        compose.onNodeWithTag(PasswordKind.CurrentWalletPassword.english).performTextInput("old password 1")
        compose.onNodeWithTag(PasswordKind.NewPassword.english).performTextInput("Public-Test-Password1!")
        val confirmation = compose.onNodeWithTag(PasswordKind.ConfirmNewPassword.english)
        confirmation.performTextInput("Public-Test-Password1!")
        // Tab to "Change Password" and Enter: the button had the focus when the page cleared it.
        val button = compose.onNodeWithTag("vaultManagement.submit")
        button.requestFocus()
        compose.waitForIdle()
        button.assertIsFocused()
        button.performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()

        assertEquals(1, submitted)
        assertNothingFocused()
    }
}
