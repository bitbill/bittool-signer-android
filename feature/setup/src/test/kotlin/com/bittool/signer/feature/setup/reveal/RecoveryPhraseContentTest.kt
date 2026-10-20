package com.bittool.signer.feature.setup.reveal

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.core.ui.password.PasswordKind
import com.bittool.signer.feature.setup.Phrases
import com.bittool.signer.feature.setup.SetupTestHost
import com.bittool.signer.feature.setup.flow.RecoveryWords
import com.bittool.signer.feature.setup.flow.WalletIdentity
import com.bittool.signer.feature.setup.isSecretTextMenu
import com.bittool.signer.feature.setup.textMenuFilters
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
