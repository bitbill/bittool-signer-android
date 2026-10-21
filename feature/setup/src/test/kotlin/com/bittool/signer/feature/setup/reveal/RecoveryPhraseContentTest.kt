package com.bittool.signer.feature.setup.reveal

import android.os.Build
import android.os.Looper
import android.view.View
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.feature.setup.Phrases
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.flow.RecoveryWords
import com.bittool.signer.feature.setup.flow.WalletIdentity
import com.bittool.signer.feature.setup.isSecretTextMenu
import com.bittool.signer.feature.setup.textMenuFilters
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The saved wallet's Recovery Phrase page as drawn (setup-backup §10). */
@RunWith(RobolectricTestRunner::class)
class RecoveryPhraseContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val factory = FakeRecoverySessionFactory()
    private val identity = WalletIdentity("Public fixture 1", "73C5DA0A", WalletId.random().uppercase)

    @Test
    fun thePasswordFieldOffersNoTextActionThatSendsItElsewhere() {
        compose.setContent {
            SetupTestHost(factory) {
                RecoveryPhraseContent(RecoveryPhraseUiState(identity = identity), remember { TextFieldState() }, factory, onUnlock = {}, onClose = {})
            }
        }
        assertTrue(isSecretTextMenu(compose.onNodeWithTag(PasswordKind.WalletPassword.english).textMenuFilters()))
    }

    /** "Done" wiped the phrase: the leaving page shows the wallet only, not the form (whose delayed focus would raise the keyboard). */
    @Test
    fun aClosingPageShowsNeitherThePhraseNorThePasswordForm() {
        var state by mutableStateOf(RecoveryPhraseUiState(identity = identity, revealed = RevealedPhrase(RecoveryWords(Phrases.VECTOR_12.split(' ')), "")))
        compose.setContent {
            SetupTestHost(factory) {
                RecoveryPhraseContent(state, remember { TextFieldState() }, factory, onUnlock = {}, onClose = {})
            }
        }
        compose.onNodeWithTag("recovery.reveal").assertExists()
        state = RecoveryPhraseUiState(identity = identity, closing = true)
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("recovery.reveal").assertDoesNotExist()
        compose.onNodeWithTag(PasswordKind.WalletPassword.english).assertDoesNotExist()
        compose.onNodeWithTag("vaultManagement.submit").assertDoesNotExist()
        compose.onNodeWithText("Public fixture 1").assertExists()
    }

    /**
     * QA wallets-10: like Change Password, Delete Wallet and Repair Wallet, the page closes with the cross at the start
     * of its bar, read with the label of the iOS button it stands for (`WalletSecuritySheets.swift:70`): "Cancel" while
     * the password is asked, "Done" once the phrase is shown. The cross keeps its place, so the title does not move.
     */
    @Test
    fun theCrossReadsCancelThenDoneInTheSamePlace() {
        var state by mutableStateOf(RecoveryPhraseUiState(identity = identity))
        var closed = 0
        compose.setContent {
            SetupTestHost(factory) {
                RecoveryPhraseContent(state, remember { TextFieldState() }, factory, onUnlock = {}, onClose = { closed++ })
            }
        }
        val cross = compose.onNodeWithTag("recovery.close")
        cross.assertContentDescriptionEquals("Cancel")
        val before = cross.fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Recovery Phrase").fetchSemanticsNode().boundsInRoot
        cross.performClick()
        compose.runOnIdle { assertEquals(1, closed) }

        state = state.copy(revealed = RevealedPhrase(RecoveryWords(Phrases.VECTOR_12.split(' ')), ""))
        compose.waitForIdle()
        cross.assertContentDescriptionEquals("Done")
        assertEquals(before, cross.fetchSemanticsNode().boundsInRoot)
        assertEquals(title, compose.onNodeWithText("Recovery Phrase").fetchSemanticsNode().boundsInRoot)
        cross.performClick()
        compose.runOnIdle { assertEquals(2, closed) }
    }

    /**
     * QA wallets-05 (iOS `WalletSecuritySheets.swift:99-115`: focus leaves the field only on success): a wrong password
     * keeps the field focused, and the keyboard up, for the retry. Disabling it while unlocking moved the focus to the
     * close button.
     */
    @Test
    fun theFieldKeepsFocusWhileUnlockingAndAfterAWrongPassword() {
        var state by mutableStateOf(RecoveryPhraseUiState(identity = identity))
        val password = TextFieldState()
        compose.setContent {
            SetupTestHost(factory) {
                RecoveryPhraseContent(state, password, factory, onUnlock = {}, onClose = {})
            }
        }
        val field = compose.onNodeWithTag(PasswordKind.WalletPassword.english)
        compose.mainClock.advanceTimeBy(1_000)
        field.assertIsFocused()
        field.performTextInput("Wrong-Password1!")
        state = state.copy(busy = true)
        compose.waitForIdle()
        field.assertIsFocused().assertIsEnabled()
        state = state.copy(busy = false, error = UiText.Ios("The password is incorrect."))
        compose.waitForIdle()
        compose.onNodeWithText("The password is incorrect.").assertExists()
        field.assertIsFocused()
        compose.onNodeWithTag("recovery.close").assertIsNotFocused()
    }

    /**
     * QA regression-02 (iOS `passwordFocused = false` on success, `WalletSecuritySheets.swift:114`): the password
     * confirmed with Enter on a hardware keyboard (keyboard mode, not touch mode), the phrase is shown and nothing is
     * focused. The form leaving with the focus made Android hand it to the first control, the Cancel / Done cross, which
     * kept a focus ring. Checked on API 32 and 36 (the window's focus code differs from API 33).
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2, Build.VERSION_CODES.BAKLAVA])
    fun nothingIsFocusedOnceThePhraseIsShownAfterAKeyboardUnlock() {
        var state by mutableStateOf(RecoveryPhraseUiState(identity = identity))
        val password = TextFieldState()
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            SetupTestHost(factory) {
                RecoveryPhraseContent(state, password, factory, onUnlock = {}, onClose = {})
            }
        }
        compose.runOnIdle { leaveTouchMode(view) }
        val field = compose.onNodeWithTag(PasswordKind.WalletPassword.english)
        compose.mainClock.advanceTimeBy(1_000)
        field.assertIsFocused()
        field.performTextInput("Correct-Password1!")
        state = state.copy(busy = true)
        compose.waitForIdle()
        state = state.copy(busy = false, revealed = RevealedPhrase(RecoveryWords(Phrases.VECTOR_12.split(' ')), ""))
        compose.waitForIdle()

        compose.onNodeWithTag("recovery.reveal").assertExists()
        compose.onNodeWithTag(PasswordKind.WalletPassword.english).assertDoesNotExist()
        compose.onNodeWithTag("recovery.close").assertIsNotFocused()
        compose.onAllNodes(isFocused()).assertCountEquals(0)
        compose.runOnIdle {
            assertFalse(view.isInTouchMode, "precondition: keyboard mode")
            assertNull(view.rootView.findFocus(), "no view of the window has the focus")
        }
    }

    /** A key typed in the window takes it out of touch mode (`ViewRootImpl.ensureTouchMode(false)`, the input stage's call). */
    private fun leaveTouchMode(view: View) {
        val root = checkNotNull(view.rootView.parent) { "not attached to a window" }
        root.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(root, false)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
