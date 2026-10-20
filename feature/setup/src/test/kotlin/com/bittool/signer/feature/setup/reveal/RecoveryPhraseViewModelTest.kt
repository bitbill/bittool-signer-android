package com.bittool.signer.feature.setup.reveal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.data.testing.FakeSignerStore.Call
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.VaultSecret
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.feature.setup.Phrases
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** iOS `RecoveryPhraseSheet` + `WalletOperation` (setup-backup §10) at ViewModel level; the UIT G1.14 behaviours. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryPhraseViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val wallet = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
    private lateinit var store: FakeSignerStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = FakeSignerStore()
        store.setState { it.copy(wallets = listOf(wallet)) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(id: String = wallet.id.toString()): RecoveryPhraseViewModel {
        val model = RecoveryPhraseViewModel(id, store, FakeRecoverySessionFactory())
        backgroundScope.launch(dispatcher) { model.uiState.collect { } }
        return model
    }

    @Test
    fun theRightPasswordShowsTheWordsAndThePassphraseAndClearsTheField() = runTest(dispatcher) {
        store.onReveal = { _, _ -> VaultSecret(SecretText.of(Phrases.VECTOR_12), SecretText.of("TREZOR")) }
        val model = model()
        assertEquals(listOf<Call>(Call.BeginSensitiveSession), store.calls)
        assertEquals("Public fixture 1", model.uiState.value.identity?.name)
        model.password.setTextAndPlaceCursorAtEnd("Public-Test-Password1!")
        model.unlock()
        assertEquals(Call.Reveal(wallet.id, "Public-Test-Password1!"), store.calls<Call.Reveal>().single())
        assertTrue(model.password.text.isEmpty(), "the password is cleared the moment it is submitted")
        val revealed = assertNotNull(model.uiState.value.revealed)
        assertEquals(Phrases.VECTOR_12.split(' '), revealed.words.list)
        assertEquals("TREZOR", revealed.passphrase)
        assertTrue(revealed.hasPassphrase)
        assertFalse(model.uiState.value.toString().contains("legal"))
    }

    @Test
    fun aWrongPasswordIsReportedAndThePageStays() = runTest(dispatcher) {
        store.onReveal = { _, _ -> throw VaultError.AuthenticationFailed() }
        val model = model()
        model.password.setTextAndPlaceCursorAtEnd("Wrong-Password1!")
        model.unlock()
        assertNotNull(model.uiState.value.error)
        assertNull(model.uiState.value.revealed)
        assertFalse(model.uiState.value.busy)
    }

    @Test
    fun backgroundingDiscardsTheDecryptedPhrase() = runTest(dispatcher) {
        val model = model()
        model.password.setTextAndPlaceCursorAtEnd("Public-Test-Password1!")
        model.unlock()
        assertNotNull(model.uiState.value.revealed)
        store.suspendSession()
        assertNull(model.uiState.value.revealed, "the password is entered again")
        assertEquals(store.state.value.securityEpoch, model.uiState.value.securityEpoch)
    }

    @Test
    fun aResultArrivingAfterASuspendIsDropped() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val secret = VaultSecret(SecretText.of(Phrases.VECTOR_12), SecretText.empty())
        store.onReveal = { _, _ -> gate.await(); secret }
        val model = model()
        model.password.setTextAndPlaceCursorAtEnd("Public-Test-Password1!")
        model.unlock()
        assertTrue(model.uiState.value.busy)
        model.back()
        assertTrue(model.uiState.value.busy, "Back is consumed while unlocking")
        store.suspendSession()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(model.uiState.value.revealed)
        assertFalse(model.uiState.value.busy)
    }

    @Test
    fun cancelClearsEverythingAndCloses() = runTest(dispatcher) {
        val model = model()
        val events = mutableListOf<RecoveryPhraseEvent>()
        backgroundScope.launch(dispatcher) { model.eventFlow.toList(events) }
        model.password.setTextAndPlaceCursorAtEnd("Public-Test-Password1!")
        model.unlock()
        model.close()
        assertNull(model.uiState.value.revealed)
        assertTrue(model.password.text.isEmpty())
        assertEquals(listOf<RecoveryPhraseEvent>(RecoveryPhraseEvent.Close), events)
    }

    /**
     * Backgrounding discards the decrypted phrase so that whoever picks the phone up needs the password again. The
     * submitted password must not survive in the field's undo history: Ctrl+Z on a hardware keyboard would type it
     * back in, and "Continue" would reveal the phrase without anyone knowing the password.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun undoCannotBringBackThePasswordAfterThePhraseWasDiscarded() = runTest(dispatcher) {
        val model = model()
        model.password.setTextAndPlaceCursorAtEnd("Public-Test-Password1!")
        model.unlock()
        assertNotNull(model.uiState.value.revealed)
        assertFalse(model.password.undoState.canUndo, "the submitted password leaves no undo history")
        store.suspendSession()
        assertNull(model.uiState.value.revealed)
        model.password.undoState.undo()
        assertTrue(model.password.text.isEmpty(), "undo brings nothing back")

        model.password.setTextAndPlaceCursorAtEnd("Typed-Then-Backgrounded1!")
        store.suspendSession()
        assertFalse(model.password.undoState.canUndo, "a password typed but not submitted is gone for good too")
    }

    /**
     * "Done" / "Cancel" wipe everything first (iOS `clear(); dismiss()`), but the page leaving shows neither the phrase
     * nor the password form again: no form flashing up during the slide-down, and no keyboard raised by its delayed
     * focus. A background while it leaves keeps it that way; a second tap sends nothing more.
     */
    @Test
    fun doneWipesThePhraseAndThePageLeavesWithoutTheForm() = runTest(dispatcher) {
        store.onReveal = { _, _ -> VaultSecret(SecretText.of(Phrases.VECTOR_12), SecretText.empty()) }
        val model = model()
        val events = mutableListOf<RecoveryPhraseEvent>()
        backgroundScope.launch(dispatcher) { model.eventFlow.toList(events) }
        model.password.setTextAndPlaceCursorAtEnd("Public-Test-Password1!")
        model.unlock()
        assertNotNull(model.uiState.value.revealed)
        model.close()
        assertTrue(model.uiState.value.closing)
        assertNull(model.uiState.value.revealed)
        store.suspendSession()
        assertTrue(model.uiState.value.closing, "still leaving after a background")
        model.close()
        model.password.setTextAndPlaceCursorAtEnd("Public-Test-Password1!")
        model.unlock()
        assertEquals(1, store.calls<Call.Reveal>().size, "nothing is unlocked on the way out")
        assertEquals(listOf<RecoveryPhraseEvent>(RecoveryPhraseEvent.Close), events)
    }

    @Test
    fun anEmptyPasswordDoesNothing() = runTest(dispatcher) {
        val model = model()
        model.unlock()
        assertTrue(store.calls<Call.Reveal>().isEmpty())
    }

    @Test
    fun aPageForAWalletThatNoLongerExistsClosesItself() = runTest(dispatcher) {
        val events = mutableListOf<RecoveryPhraseEvent>()
        val model = model(WalletId.random().toString())
        backgroundScope.launch(dispatcher) { model.eventFlow.toList(events) }
        assertEquals(listOf<RecoveryPhraseEvent>(RecoveryPhraseEvent.Close), events)
        val malformed = model("not-a-uuid")
        val more = mutableListOf<RecoveryPhraseEvent>()
        backgroundScope.launch(dispatcher) { malformed.eventFlow.toList(more) }
        assertEquals(listOf<RecoveryPhraseEvent>(RecoveryPhraseEvent.Close), more)
    }
}
