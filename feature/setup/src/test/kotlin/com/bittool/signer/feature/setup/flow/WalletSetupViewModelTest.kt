package com.bittool.signer.feature.setup.flow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import com.bittool.signer.core.crypto.bip39.Bip39Candidates
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.data.testing.FakeSignerStore.Call
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.navigation.ConnectRoute
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.navigation.WalletDetailRoute
import com.bittool.signer.core.security.testing.FakeRecoverySessionFactory
import com.bittool.signer.feature.setup.Phrases
import com.bittool.signer.feature.setup.SeededRandomSource
import com.bittool.signer.feature.setup.applySnapshots
import com.bittool.signer.feature.setup.testBip39
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

/**
 * The transitions of iOS `WalletSetupModel` (setup-backup §2.2) and the flow-host hooks of `WalletSetupFlow` (§1.5),
 * against the published fake store and the real BIP39 lists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletSetupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var store: FakeSignerStore
    private val deviceKeys = InMemoryDeviceKeyStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = FakeSignerStore()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(intent: SetupIntent = SetupIntent.Create): WalletSetupViewModel =
        WalletSetupViewModel(intent, store, testBip39, SeededRandomSource(), FakeRecoverySessionFactory(), dispatcher, deviceKeys)

    private fun TestScope.started(intent: SetupIntent = SetupIntent.Create, name: String = "My Wallet"): WalletSetupViewModel {
        val model = model(intent)
        backgroundScope.launch(dispatcher) { model.uiState.collect { } }
        model.seedName(name)
        return model
    }

    private val WalletSetupViewModel.state: WalletSetupUiState get() = uiState.value

    private fun TestScope.events(model: WalletSetupViewModel): MutableList<SetupEvent> {
        val events = mutableListOf<SetupEvent>()
        backgroundScope.launch(dispatcher) { model.eventFlow.toList(events) }
        return events
    }

    private fun WalletSetupViewModel.type(field: androidx.compose.foundation.text.input.TextFieldState, text: String) {
        field.setTextAndPlaceCursorAtEnd(text)
        applySnapshots()
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun WalletSetupViewModel.setPasswords(password: String = "UiTest-Backup8!", confirmation: String = password) {
        type(fields.password, password)
        type(fields.confirmation, confirmation)
    }

    /** Create → backup (revealed) → a correct check → password step. */
    private fun WalletSetupViewModel.reachPasswordStep() {
        startCreate()
        markRevealed()
        confirmBackup()
        val verification = assertNotNull(state.verification)
        for (position in verification.positions) {
            val word = state.words.list[position]
            val chip = state.verification!!.chips.first { it.word == word && !state.verification!!.isPlaced(it) }
            select(position)
            place(chip.id)
        }
        finishVerification()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Presentation hooks

    @Test
    fun theDefaultNameIsMyWalletForTheFirstWalletAndNumberedAfterwards() = runTest(dispatcher) {
        assertEquals(UiText.Ios("My Wallet"), model().defaultName)
        store.setState { it.copy(wallets = listOf(VaultMetadata(WalletId.random(), "Existing", 1L), VaultMetadata(WalletId.random(), "Second", 2L))) }
        assertEquals(UiText.Ios("Wallet %lld", listOf(3)), model().defaultName)
    }

    @Test
    fun theNameIsSeededOnceAndNeverOverwritten() = runTest(dispatcher) {
        val model = started(name = "My Wallet")
        assertEquals("My Wallet", model.fields.name.text.toString())
        model.type(model.fields.name, "Travel Signer")
        model.seedName("Mein Wallet")
        assertEquals("Travel Signer", model.fields.name.text.toString())
    }

    @Test
    fun theSensitiveSessionBeginsWithTheFlowAndEndsWhenItIsCleared() = runTest(dispatcher) {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T = model() as T
        }
        val model = ViewModelProvider(owner, factory)[WalletSetupViewModel::class.java]
        backgroundScope.launch(dispatcher) { model.uiState.collect { } }
        assertEquals(listOf<Call>(Call.BeginSensitiveSession), store.calls)
        model.seedName("My Wallet")
        model.startCreate()
        assertFalse(model.state.words.isEmpty())
        owner.viewModelStore.clear()
        assertEquals(Call.EndSensitiveSession, store.calls.last())
        assertFalse(model.hasDraftSecrets(), "closing the flow erases the phrase")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Create

    @Test
    fun startCreateGeneratesOncePerWordCountAndKeepsThePhraseWhenComingBack() = runTest(dispatcher) {
        val model = started()
        model.startCreate()
        assertEquals(listOf(SetupStep.Backup), model.state.path)
        assertEquals(24, model.state.words.size)
        assertEquals(1, store.calls<Call.GenerateMnemonic>().size)
        model.markRevealed()

        model.pop()
        model.startCreate()
        assertEquals(1, store.calls<Call.GenerateMnemonic>().size, "the same phrase unless the word count changed")
        assertTrue(model.state.hasRevealedBackup)

        model.pop()
        model.setWordCount(12)
        model.startCreate()
        assertEquals(listOf(Call.GenerateMnemonic(24), Call.GenerateMnemonic(12)), store.calls<Call.GenerateMnemonic>())
        assertEquals(12, model.state.words.size)
        assertFalse(model.state.hasRevealedBackup, "a new phrase has not been seen yet")
    }

    @Test
    fun validateOptionsChecksTheNameItsLengthThenThePassphrase() = runTest(dispatcher) {
        val model = started(name = "   ")
        model.startCreate()
        assertEquals(UiText.Ios("Enter a wallet name."), model.state.error)

        model.type(model.fields.name, "a".repeat(41))
        model.startCreate()
        assertEquals(UiText.Ios("Use a wallet name of 40 characters or fewer."), model.state.error)

        // 40 grapheme clusters are fine, however many UTF-16 units they take.
        model.type(model.fields.name, "👍🏽".repeat(40))
        model.setUsePassphrase(true)
        model.type(model.fields.passphrase, "one")
        model.type(model.fields.passphraseConfirmation, "two")
        model.startCreate()
        assertEquals(UiText.Ios("The BIP39 passphrases must match and cannot be empty."), model.state.error)

        model.type(model.fields.passphraseConfirmation, "one")
        model.startCreate()
        assertNull(model.state.error)
        assertEquals(listOf(SetupStep.Backup), model.state.path)
        assertTrue(store.calls<Call.GenerateMnemonic>().isNotEmpty())
    }

    @Test
    fun aPhraseGeneratedAfterASuspendIsDiscarded() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        store.onGenerateMnemonic = { gate.await(); com.bittool.signer.core.model.SecretText.of(Phrases.VECTOR_24) }
        val model = started()
        model.startCreate()
        assertTrue(model.state.busy)
        store.suspendSession()
        assertFalse(model.state.busy)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(emptyList(), model.state.path)
        assertTrue(model.state.words.isEmpty())
        assertNull(model.state.error, "a cancellation is silent")
    }

    @Test
    fun theBackupCheckNeedsTheWordsToHaveBeenRevealed() = runTest(dispatcher) {
        val model = started()
        model.setWordCount(12)
        store.onGenerateMnemonic = { com.bittool.signer.core.model.SecretText.of(Phrases.VECTOR_12) }
        model.startCreate()
        model.confirmBackup()
        assertNull(model.state.verification, "revealing the QR code alone does not set hasRevealedBackup")
        assertEquals(listOf(SetupStep.Backup), model.state.path)

        model.markRevealed()
        model.confirmBackup()
        val verification = assertNotNull(model.state.verification)
        assertEquals(4, verification.positions.size)
        assertEquals(listOf(SetupStep.Backup, SetupStep.Verify), model.state.path)
        assertEquals(verification.positions.first(), verification.activePosition, "the first blank is active at the start")
    }

    @Test
    fun aWrongCheckKeepsTheBlanksAndChipOrderAndEmptiesEveryBlank() = runTest(dispatcher) {
        val model = started()
        val events = events(model)
        store.onGenerateMnemonic = { com.bittool.signer.core.model.SecretText.of(Phrases.VECTOR_24) }
        model.startCreate()
        model.markRevealed()
        model.confirmBackup()
        val before = assertNotNull(model.state.verification)
        assertEquals(5, before.positions.size, "five blanks for 18–24 words")
        // Fill in chip order: never the answer order when the blank words differ.
        before.chips.forEach { model.place(it.id) }
        assertTrue(model.state.verification!!.isComplete)
        model.finishVerification()
        val after = assertNotNull(model.state.verification)
        assertEquals(UiText.Ios("Some words don't match. Check your written backup and try again."), model.state.error)
        assertEquals(before.positions, after.positions)
        assertEquals(before.chips, after.chips)
        assertTrue(after.placements.isEmpty())
        assertEquals(listOf<SetupEvent>(SetupEvent.VerificationRejected), events)
        assertEquals(listOf(SetupStep.Backup, SetupStep.Verify), model.state.path)

        // A placement clears the error; select() does not.
        model.select(after.positions.last())
        assertNotNull(model.state.error)
        model.place(after.chips.first().id)
        assertNull(model.state.error)
    }

    @Test
    fun aCorrectCheckOpensThePasswordStepAndChecksForAnExistingWallet() = runTest(dispatcher) {
        val model = started()
        model.reachPasswordStep()
        assertEquals(listOf(SetupStep.Backup, SetupStep.Verify, SetupStep.Password), model.state.path)
        assertEquals(4, model.state.stepNumber)
        assertEquals(1, store.calls<Call.ExistingWallet>().size)
        assertEquals(FakeSignerStore.PUBLIC_FIXTURE_24, store.calls<Call.ExistingWallet>().single().mnemonic)
        assertEquals("", store.calls<Call.ExistingWallet>().single().passphrase, "the passphrase only counts while the option is on")
        assertNull(model.state.duplicateOf)
    }

    @Test
    fun showThePhraseAgainReturnsToTheSameConcealedPhrase() = runTest(dispatcher) {
        val model = started()
        model.startCreate()
        val words = model.state.words
        model.markRevealed()
        model.confirmBackup()
        model.reviewPhraseAgain()
        assertEquals(listOf(SetupStep.Backup), model.state.path)
        assertNull(model.state.verification)
        assertEquals(words, model.state.words)
        assertTrue(model.state.hasRevealedBackup, "re-concealing does not reset hasRevealedBackup")
        assertEquals(1, store.calls<Call.GenerateMnemonic>().size)
    }

    @Test
    fun suspendClearsThePasswordsAndTheAnswersButKeepsThePhrase() = runTest(dispatcher) {
        val model = started(name = "Travel Signer")
        model.setUsePassphrase(true)
        model.type(model.fields.passphrase, "extra")
        model.type(model.fields.passphraseConfirmation, "extra")
        model.startCreate()
        model.markRevealed()
        model.confirmBackup()
        val verification = model.state.verification!!
        model.place(verification.chips.first().id)
        model.setPasswords()
        val words = model.state.words

        store.suspendSession()

        assertTrue(model.fields.password.text.isEmpty())
        assertTrue(model.fields.confirmation.text.isEmpty())
        assertTrue(model.state.verification!!.placements.isEmpty())
        assertEquals(verification.positions, model.state.verification!!.positions, "the same blanks")
        assertEquals(words, model.state.words)
        assertEquals("Travel Signer", model.fields.name.text.toString())
        assertEquals("extra", model.fields.passphrase.text.toString())
        assertTrue(model.state.hasRevealedBackup)
        assertEquals(listOf(SetupStep.Backup, SetupStep.Verify), model.state.path)
        assertEquals(store.state.value.securityEpoch, model.state.securityEpoch, "the backup panel is keyed by the epoch")
    }

    @Test
    fun createWalletCommitsWithTheTrimmedNameAndEmptyPassphraseWhenTheOptionIsOff() = runTest(dispatcher) {
        val model = started(name = "  Travel Signer ")
        model.setUsePassphrase(true)
        model.type(model.fields.passphrase, "kept but unused")
        model.type(model.fields.passphraseConfirmation, "kept but unused")
        model.setUsePassphrase(false)
        model.reachPasswordStep()
        model.setPasswords("UiTest-Backup8!")
        model.createWallet()
        val call = store.calls<Call.CreateWallet>().single()
        assertEquals("Travel Signer", call.name)
        assertEquals(FakeSignerStore.PUBLIC_FIXTURE_24, call.mnemonic)
        assertEquals("", call.passphrase)
        assertEquals("UiTest-Backup8!", call.password)
        assertNotNull(model.state.created)
        assertEquals(SetupStep.Done, model.state.step)
        assertTrue(model.fields.password.text.isEmpty(), "every secret is cleared once committed")
        assertTrue(model.fields.passphrase.text.isEmpty())
        assertTrue(model.state.words.isEmpty())
        assertFalse(model.hasDraftSecrets())
        assertEquals("Travel Signer", model.state.identity?.name)
    }

    @Test
    fun aCommittedWalletIsTheSuccessBoundaryEvenIfTheAppWasBackgroundedWhileSaving() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val wallet = VaultMetadata(WalletId.random(), "My Wallet", 1L)
        store.onCreateWallet = { _, _, _, _ ->
            gate.await()
            store.setState { it.copy(wallets = it.wallets + wallet) }
            wallet
        }
        val model = started()
        model.reachPasswordStep()
        model.setPasswords()
        model.createWallet()
        assertTrue(model.state.busy)
        store.suspendSession()
        assertFalse(model.state.busy)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(wallet, model.state.created)
        assertEquals(SetupStep.Done, model.state.step)
    }

    @Test
    fun aFailedCommitShowsItsErrorUnlessTheSessionChanged() = runTest(dispatcher) {
        store.onCreateWallet = { _, _, _, _ -> throw AppIssue("This recovery phrase and passphrase already belong to an existing wallet.") }
        val model = started()
        model.reachPasswordStep()
        model.setPasswords()
        model.createWallet()
        assertEquals(UiText.Ios("This recovery phrase and passphrase already belong to an existing wallet."), model.state.error)
        assertFalse(model.state.busy)
        assertEquals(SetupStep.Password, model.state.step)

        val gate = CompletableDeferred<Unit>()
        store.onCreateWallet = { _, _, _, _ ->
            gate.await()
            throw AppIssue("The encrypted wallet could not be created.")
        }
        model.setPasswords()
        model.createWallet()
        store.suspendSession()
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(model.state.error, "a late failure after a suspend is not shown")
    }

    @Test
    fun anInvalidPasswordCannotCreate() = runTest(dispatcher) {
        val model = started()
        model.reachPasswordStep()
        model.setPasswords("UiTest-Backup8!", "Mismatch-Password1!")
        model.createWallet()
        model.setPasswords("short", "short")
        model.createWallet()
        assertTrue(store.calls<Call.CreateWallet>().isEmpty())
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Import

    @Test
    fun aSingleReadingIsAcceptedAndOpensThePasswordStep() = runTest(dispatcher) {
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, "  ${FakeSignerStore.PUBLIC_FIXTURE_12}\n")
        model.startImport()
        assertEquals(listOf(SetupStep.Password), model.state.path)
        assertEquals(2, model.state.stepNumber)
        assertEquals(Bip39Language.English, model.importedLanguage)
        assertEquals(FakeSignerStore.PUBLIC_FIXTURE_12, store.calls<Call.ExistingWallet>().single().mnemonic)
        model.setPasswords("Public-Test-Password1!")
        model.createWallet()
        assertEquals(FakeSignerStore.PUBLIC_FIXTURE_12, store.calls<Call.CreateWallet>().single().mnemonic, "the canonical words, not the typed text")
    }

    @Test
    fun wordsTypedWithoutAccentsBecomeTheCanonicalSpanishWords() = runTest(dispatcher) {
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, Phrases.SPANISH)
        assertEquals(12, model.state.phrase.wordCount)
        assertEquals(Bip39Language.Spanish, model.state.phrase.language)
        model.startImport()
        assertEquals(Bip39Language.Spanish, model.importedLanguage)
        model.setPasswords()
        model.createWallet()
        assertTrue(store.calls<Call.CreateWallet>().single().mnemonic.endsWith("dragón optar"), "accents restored")
    }

    @Test
    fun anAmbiguousImportAsksForTheLanguageInReadingOrder() = runTest(dispatcher) {
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, Phrases.ITALIAN_OR_SPANISH)
        model.startImport()
        assertEquals(listOf(Bip39Language.Italian, Bip39Language.Spanish), model.state.languageChoices.map { it.language }, "exact spellings first")
        assertEquals(emptyList(), model.state.path)
        model.dismissLanguageChoices()
        assertTrue(model.state.languageChoices.isEmpty())

        model.startImport()
        model.acceptImport(model.state.languageChoices.first { it.language == Bip39Language.Spanish })
        assertEquals(Bip39Language.Spanish, model.importedLanguage)
        assertEquals(listOf(SetupStep.Password), model.state.path)
        assertTrue(model.state.languageChoices.isEmpty())
    }

    @Test
    fun anUneditedScanIsTakenWithItsExactSpellingAndOneEditAsksAgain() = runTest(dispatcher) {
        val model = started(SetupIntent.Restore)
        model.openScanner()
        model.onRecoveryQrScanned("  ${Phrases.ITALIAN_OR_SPANISH}\n")
        assertFalse(model.state.scanning)
        assertEquals(Phrases.ITALIAN_OR_SPANISH, model.fields.phrase.text.toString(), "trimmed")
        model.startImport()
        assertEquals(Bip39Language.Italian, model.importedLanguage, "no language question for an unedited scan")
        assertEquals(listOf(SetupStep.Password), model.state.path)

        model.pop()
        model.type(model.fields.phrase, Phrases.ITALIAN_OR_SPANISH + " ")
        model.startImport()
        assertEquals(2, model.state.languageChoices.size, "an edit (even a space) takes the forgiving path")
    }

    @Test
    fun aStandardSeedQrFillsTheEnglishWordsAndOtherCodesAreRefused() = runTest(dispatcher) {
        val model = started(SetupIntent.Restore)
        model.openScanner()
        model.onRecoveryQrScanned("not a phrase")
        assertEquals(UiText.Resource(com.bittool.signer.core.l10n.R.string.setup_qr_not_recovery_phrase), model.state.scanError)
        assertTrue(model.state.scanning)
        model.resetScanError()
        assertNull(model.state.scanError)
        // Standard SeedQR: 4 digits per word index ("abandon" = 0000, "about" = 0003).
        model.onRecoveryQrScanned("0000".repeat(11) + "0003")
        assertFalse(model.state.scanning)
        assertEquals(FakeSignerStore.PUBLIC_FIXTURE_12, model.fields.phrase.text.toString())
    }

    @Test
    fun importErrorsReachTheFooter() = runTest(dispatcher) {
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, "abandon abandon abandon")
        model.startImport()
        assertEquals(
            UiText.Ios("Invalid recovery phrase: it has %lld words. Recovery phrases have 12, 15, 18, 21 or 24 words.", listOf(3)),
            model.state.error,
        )
        model.type(model.fields.phrase, List(12) { "abandon" }.joinToString(" "))
        model.startImport()
        assertEquals(UiText.Ios("Invalid recovery phrase. Check the words and their order."), model.state.error)
        assertEquals(emptyList(), model.state.path)
    }

    @Test
    fun theStatusLineCountsWordsDetectsTheLanguageAndNamesUnknownWords() = runTest(dispatcher) {
        val model = started(SetupIntent.Restore)
        assertEquals(0, model.state.phrase.wordCount)
        model.type(model.fields.phrase, "abandon abandon abandon")
        assertEquals(3, model.state.phrase.wordCount)
        assertEquals(Bip39Language.English, model.state.phrase.language)
        assertFalse(model.state.phrase.looksComplete)
        model.type(model.fields.phrase, "abandon qwerty zxcvb asdfg")
        assertEquals(listOf("qwerty", "zxcvb"), model.state.phrase.unknownWords, "the first two unknown words")
        model.type(model.fields.phrase, FakeSignerStore.PUBLIC_FIXTURE_12)
        assertTrue(model.state.phrase.looksComplete)
    }

    @Test
    fun aSuggestionReplacesTheWordBeingTyped() = runTest(dispatcher) {
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, "abandon aba")
        val candidates = assertIs<Bip39Candidates.Words>(model.state.phrase.candidates)
        assertEquals("abandon", candidates.words.first())
        model.insertCandidate("abandon")
        assertEquals("abandon abandon ", model.fields.phrase.text.toString())
        assertEquals(model.fields.phrase.text.length, model.fields.phrase.selection.end, "the cursor moves to the end")
        // Nothing to replace after a separator.
        model.insertCandidate("about")
        assertEquals("abandon abandon ", model.fields.phrase.text.toString())
    }

    @Test
    fun numberingIsNotATokenSoASuggestionReplacesTheTrailingNumber() = runTest(dispatcher) {
        // Known iOS quirk kept for parity (setup-backup §11.8): "aban 1" suggests for "aban" and replaces the "1".
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, "aban 1")
        val candidates = assertIs<Bip39Candidates.Words>(model.state.phrase.candidates)
        assertEquals("abandon", candidates.words.first())
        model.insertCandidate("abandon")
        assertEquals("aban abandon ", model.fields.phrase.text.toString())
        model.type(model.fields.phrase, "abandon 1")
        assertEquals(Bip39Candidates.Hidden, model.state.phrase.candidates, "the only suggestion is the word already typed")
    }

    @Test
    fun theDuplicateCheckTurnsThePasswordStepIntoAResetOfThatWallet() = runTest(dispatcher) {
        val existing = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
        store.setState { it.copy(wallets = listOf(existing)) }
        store.onExistingWallet = { _, _ -> existing.id }
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, FakeSignerStore.PUBLIC_FIXTURE_12)
        model.startImport()
        assertEquals(existing.id, model.state.duplicateOf)
        assertEquals("Public fixture 1", model.state.identity?.name)
        model.setPasswords("Recovered-Test-Password3!")
        model.resetPassword()
        val call = store.calls<Call.ResetPassword>().single()
        assertEquals(existing.id, call.walletId)
        assertEquals("Recovered-Test-Password3!", call.newPassword)
        assertTrue(model.state.didResetPassword)
        assertEquals(existing, model.state.created)
        assertEquals(SetupStep.Done, model.state.step)
        assertTrue(store.calls<Call.CreateWallet>().isEmpty())
    }

    @Test
    fun aLateDuplicateAnswerAfterASuspendIsDropped() = runTest(dispatcher) {
        // The question asked before the suspension gets a (stale) wallet; the one asked again after it gets none.
        val stale = CompletableDeferred<WalletId?>()
        val none = CompletableDeferred<WalletId?>().apply { complete(null) }
        val answers = ArrayDeque(listOf(stale, none))
        store.onExistingWallet = { _, _ -> answers.removeFirst().await() }
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, FakeSignerStore.PUBLIC_FIXTURE_12)
        model.startImport()
        store.suspendSession()
        stale.complete(WalletId.random())
        advanceUntilIdle()
        assertNull(model.state.duplicateOf)
        assertFalse(model.state.checkingExisting)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Navigation

    @Test
    fun leavingThePasswordStepClearsThePasswordsAndForgetsTheDuplicateAnswer() = runTest(dispatcher) {
        val existing = WalletId.random()
        store.onExistingWallet = { _, _ -> existing }
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, FakeSignerStore.PUBLIC_FIXTURE_12)
        model.startImport()
        model.setPasswords()
        assertEquals(existing, model.state.duplicateOf)
        model.pop()
        assertEquals(emptyList(), model.state.path)
        assertTrue(model.fields.password.text.isEmpty())
        assertTrue(model.fields.confirmation.text.isEmpty())
        assertNull(model.state.duplicateOf)
        assertEquals(FakeSignerStore.PUBLIC_FIXTURE_12, model.fields.phrase.text.toString(), "the phrase and the name stay")
        assertEquals("My Wallet", model.fields.name.text.toString())
    }

    @Test
    fun cancelAsksBeforeDiscardingDraftSecrets() = runTest(dispatcher) {
        val empty = started(SetupIntent.Restore)
        val closed = events(empty)
        // The confirmation fields do not count as draft secrets.
        empty.type(empty.fields.passphraseConfirmation, "x")
        empty.type(empty.fields.confirmation, "x")
        assertFalse(empty.hasDraftSecrets())
        empty.cancel()
        assertEquals(listOf<SetupEvent>(SetupEvent.Close), closed)

        val model = started(SetupIntent.Restore)
        val events = events(model)
        model.type(model.fields.phrase, "abandon")
        model.cancel()
        assertTrue(model.state.confirmDiscard)
        model.keepEditing()
        assertFalse(model.state.confirmDiscard)
        assertEquals("abandon", model.fields.phrase.text.toString())
        assertTrue(events.isEmpty())
        model.cancel()
        model.discard()
        assertTrue(model.fields.phrase.text.isEmpty())
        assertEquals(listOf<SetupEvent>(SetupEvent.Close), events)
    }

    /**
     * The overlay stays on screen, and touchable, while it animates away: a Back, a second tap or "Discard" in that
     * moment must not send a second outcome, which would replace "Pair a Wallet"'s route (`AppRouter.finishSetup`
     * keeps the last one) or turn a finished setup into a cancelled one.
     */
    @Test
    fun aPresentationHasExactlyOneOutcome() = runTest(dispatcher) {
        val model = started()
        val events = events(model)
        model.reachPasswordStep()
        model.setPasswords()
        model.createWallet()
        val created = assertNotNull(model.state.created)
        model.finish(pairWallet = true)
        model.back()
        model.finish(pairWallet = false)
        model.cancel()
        model.discard()
        assertEquals(
            listOf<SetupEvent>(SetupEvent.Finish(ConnectRoute.walletLevel(created.id))),
            events.filter { it is SetupEvent.Finish || it == SetupEvent.Close },
            "Connect still opens after the overlay is gone",
        )

        val discarded = started(SetupIntent.Restore)
        val outcomes = events(discarded)
        discarded.type(discarded.fields.phrase, "abandon")
        discarded.cancel()
        discarded.discard()
        discarded.back()
        discarded.cancel()
        discarded.discard()
        assertEquals(listOf<SetupEvent>(SetupEvent.Close), outcomes)
        assertFalse(discarded.state.confirmDiscard, "no second confirmation opens while the overlay leaves")
    }

    /** The success haptic (iOS `.sensoryFeedback(.success)` on `created`) is asked for once, when the commit lands. */
    @Test
    fun theSuccessHapticIsAskedForOnceWhenTheWalletIsSaved() = runTest(dispatcher) {
        val model = started()
        val events = events(model)
        model.reachPasswordStep()
        model.setPasswords()
        assertTrue(events.none { it == SetupEvent.Saved })
        model.createWallet()
        assertEquals(1, events.count { it == SetupEvent.Saved })

        val existing = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
        store.setState { it.copy(wallets = listOf(existing)) }
        store.onExistingWallet = { _, _ -> existing.id }
        val reset = started(SetupIntent.Restore)
        val resetEvents = events(reset)
        reset.type(reset.fields.phrase, FakeSignerStore.PUBLIC_FIXTURE_12)
        reset.startImport()
        reset.setPasswords("Recovered-Test-Password3!")
        reset.resetPassword()
        assertTrue(reset.state.didResetPassword)
        assertEquals(1, resetEvents.count { it == SetupEvent.Saved })
    }

    /**
     * `TextFieldState.clearText()` records the deletion as an undoable edit: Ctrl+Z (a hardware keyboard) would bring a
     * cleared secret back into its field. Every clearing of a secret also forgets its undo history.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun clearedSecretsCannotBeBroughtBackWithUndo() = runTest(dispatcher) {
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, FakeSignerStore.PUBLIC_FIXTURE_12)
        model.setUsePassphrase(true)
        model.type(model.fields.passphrase, "extra")
        model.type(model.fields.passphraseConfirmation, "extra")
        model.startImport()
        model.setPasswords()

        store.suspendSession()
        for (field in listOf(model.fields.password, model.fields.confirmation)) {
            assertFalse(field.undoState.canUndo, "backgrounding cleared the password for good")
            field.undoState.undo()
            assertTrue(field.text.isEmpty())
        }

        model.clearSecrets()
        for (field in listOf(model.fields.phrase, model.fields.passphrase, model.fields.passphraseConfirmation)) {
            assertFalse(field.undoState.canUndo, "a discarded draft is gone for good")
            field.undoState.undo()
            assertTrue(field.text.isEmpty())
        }
    }

    @Test
    fun backFollowsTheSetupBackTable() = runTest(dispatcher) {
        val model = started()
        val events = events(model)
        val gate = CompletableDeferred<com.bittool.signer.core.model.SecretText>()
        store.onGenerateMnemonic = { gate.await() }
        model.startCreate()
        model.back()
        assertTrue(model.state.busy)
        assertTrue(events.isEmpty() && !model.state.confirmDiscard, "consumed while busy")
        gate.complete(com.bittool.signer.core.model.SecretText.of(Phrases.VECTOR_24))
        advanceUntilIdle()
        assertEquals(SetupStep.Backup, model.state.step)

        model.back()
        assertEquals(emptyList(), model.state.path, "a pushed step pops")
        model.back()
        assertTrue(model.state.confirmDiscard, "the form with a generated phrase asks before discarding")
    }

    /** Where the back gesture previews leaving setup (§15.4): only where Back really ends the presentation. */
    @Test
    fun backPreviewsLeavingOnlyWhereItEndsThePresentation() = runTest(dispatcher) {
        val model = started()
        assertTrue(model.backEndsPresentation(), "an empty form closes (the name is no secret)")
        val gate = CompletableDeferred<com.bittool.signer.core.model.SecretText>()
        store.onGenerateMnemonic = { gate.await() }
        model.startCreate()
        assertFalse(model.backEndsPresentation(), "busy: consumed")
        gate.complete(com.bittool.signer.core.model.SecretText.of(Phrases.VECTOR_24))
        advanceUntilIdle()
        assertFalse(model.backEndsPresentation(), "a pushed step pops")
        model.pop()
        assertFalse(model.backEndsPresentation(), "a generated phrase asks before discarding")

        val done = started()
        done.reachPasswordStep()
        assertFalse(done.backEndsPresentation())
        done.setPasswords()
        done.createWallet()
        assertTrue(done.backEndsPresentation(), "Back on Done is Done")
        done.back()
        assertTrue(done.hasEnded)
        assertFalse(done.backEndsPresentation(), "nothing more once the presentation ended")
    }

    @Test
    fun doneOpensConnectOnTheWalletLevelQrOrNothing() = runTest(dispatcher) {
        val model = started()
        val events = events(model)
        model.reachPasswordStep()
        model.setPasswords()
        model.createWallet()
        val created = assertNotNull(model.state.created)
        model.finish(pairWallet = true)
        assertEquals(listOf<SetupEvent>(SetupEvent.Finish(ConnectRoute.walletLevel(created.id))), events.filterIsInstance<SetupEvent.Finish>())
        assertFalse(model.canPop(), "there is no way back from Done")

        // Back on Done is "Done".
        val other = started()
        val otherEvents = events(other)
        other.reachPasswordStep()
        other.setPasswords()
        other.createWallet()
        other.back()
        assertEquals(listOf<SetupEvent>(SetupEvent.Finish(null)), otherEvents.filterIsInstance<SetupEvent.Finish>())
    }

    @Test
    fun aWalletDeletedElsewhereClearsTheDraftUnlessAWalletWasCreated() = runTest(dispatcher) {
        val model = started()
        model.startCreate()
        store.setState { it.copy(navigationResetEpoch = it.navigationResetEpoch + 1) }
        assertTrue(model.state.words.isEmpty())
        assertFalse(model.hasDraftSecrets())
        assertEquals(listOf(SetupStep.Backup), model.state.path, "clearSecrets keeps the path")
    }

    @Test
    fun theStateNeverPrintsRecoveryWords() = runTest(dispatcher) {
        val model = started()
        model.startCreate()
        val printed = model.state.toString() + model.fields.toString() + model.state.words.toString()
        assertFalse(printed.contains("abandon"), printed)
        val state: SignerState = store.state.value
        assertNotNull(state)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Android additions (QA round 1)

    /** QA wallets-04: without a screen lock nothing starts, before any secret is typed; a lock set meanwhile counts. */
    @Test
    fun withoutAScreenLockTheFirstStepDoesNotStart() = runTest(dispatcher) {
        deviceKeys.deviceSecure = false
        val create = started()
        assertFalse(create.state.deviceSecure)
        create.startCreate()
        assertTrue(store.calls<Call.GenerateMnemonic>().isEmpty())
        assertEquals(emptyList(), create.state.path)
        assertNull(create.state.error, "the notice says it, not an error")

        val restore = started(SetupIntent.Restore)
        restore.type(restore.fields.phrase, FakeSignerStore.PUBLIC_FIXTURE_12)
        restore.startImport()
        advanceUntilIdle()
        assertEquals(emptyList(), restore.state.path)

        // Back from Settings with a screen lock: the flow resumes and refreshes.
        deviceKeys.deviceSecure = true
        assertTrue(create.refreshDeviceSecurity())
        assertTrue(create.state.deviceSecure)
        create.startCreate()
        assertEquals(listOf(SetupStep.Backup), create.state.path)
        restore.startImport()
        advanceUntilIdle()
        assertEquals(listOf(SetupStep.Password), restore.state.path, "startImport reads the lock screen itself")
    }

    /** QA setup-08: the password step's action waits for the duplicate check, so a quick tap cannot reach createWallet. */
    @Test
    fun thePasswordStepWaitsForTheDuplicateCheck() = runTest(dispatcher) {
        val existing = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
        store.setState { it.copy(wallets = listOf(existing)) }
        val gate = CompletableDeferred<WalletId?>()
        store.onExistingWallet = { _, _ -> gate.await() }
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, FakeSignerStore.PUBLIC_FIXTURE_12)
        model.startImport()
        assertEquals(SetupStep.Password, model.state.step)
        assertTrue(model.state.checkingExisting)
        model.setPasswords()
        model.createWallet()
        assertTrue(store.calls<Call.CreateWallet>().isEmpty(), "no commit before the answer")

        gate.complete(existing.id)
        advanceUntilIdle()
        assertFalse(model.state.checkingExisting)
        assertEquals(existing.id, model.state.duplicateOf)
        model.createWallet()
        assertTrue(store.calls<Call.CreateWallet>().isEmpty(), "a duplicate is reset, never created")
    }

    @Test
    fun aNewPhraseIsReadyOnceTheCheckFindsNoWallet() = runTest(dispatcher) {
        val gate = CompletableDeferred<WalletId?>()
        store.onExistingWallet = { _, _ -> gate.await() }
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, FakeSignerStore.PUBLIC_FIXTURE_12)
        model.startImport()
        assertTrue(model.state.checkingExisting)
        // Leaving the step stops the check; it is asked again when the step reappears.
        model.pop()
        assertFalse(model.state.checkingExisting)
        model.startImport()
        assertTrue(model.state.checkingExisting)
        gate.complete(null)
        advanceUntilIdle()
        assertFalse(model.state.checkingExisting)
        assertNull(model.state.duplicateOf)
        model.setPasswords()
        model.createWallet()
        assertEquals(1, store.calls<Call.CreateWallet>().size)
    }

    /**
     * QA setup-08: a suspension (background, App Lock, screen capture) while the duplicate check runs drops its answer;
     * the question is asked again at once, so the action stays held instead of leading to the duplicate refusal.
     */
    @Test
    fun aSuspensionDuringTheDuplicateCheckAsksItAgain() = runTest(dispatcher) {
        val existing = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
        store.setState { it.copy(wallets = listOf(existing)) }
        val answers = ArrayDeque(listOf(CompletableDeferred<WalletId?>(), CompletableDeferred()))
        val pending = answers.toList()
        store.onExistingWallet = { _, _ -> answers.removeFirst().await() }
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, FakeSignerStore.PUBLIC_FIXTURE_12)
        model.startImport()
        assertTrue(model.state.checkingExisting)

        store.suspendSession()
        assertTrue(model.state.checkingExisting, "the question is asked again, the action still held")
        assertEquals(2, store.calls<Call.ExistingWallet>().size)
        pending[0].complete(existing.id)
        model.setPasswords()
        model.createWallet()
        assertTrue(store.calls<Call.CreateWallet>().isEmpty(), "no commit before the new answer")

        pending[1].complete(existing.id)
        advanceUntilIdle()
        assertFalse(model.state.checkingExisting)
        assertEquals(existing.id, model.state.duplicateOf)

        // Once answered, a later suspension asks nothing again.
        store.suspendSession()
        assertEquals(2, store.calls<Call.ExistingWallet>().size)
        assertEquals(existing.id, model.state.duplicateOf)
    }

    /**
     * QA backlog B21: the phrase of a wallet whose device key is provably gone is not offered a password reset (it could
     * only fail with "device key unavailable"); "Open Wallet" erases the draft and opens that wallet's page, where
     * Delete Wallet offers "Remove This Wallet" (B9).
     */
    @Test
    fun aWalletWithALostDeviceKeyLeadsToItsRemovalInsteadOfAReset() = runTest(dispatcher) {
        val existing = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
        store.setState { it.copy(wallets = listOf(existing)) }
        store.onExistingWallet = { _, _ -> existing.id }
        store.lostDeviceKeys = setOf(existing.id)
        val model = started(SetupIntent.Restore)
        val events = events(model)
        model.type(model.fields.phrase, FakeSignerStore.PUBLIC_FIXTURE_12)
        model.startImport()
        assertEquals(existing.id, model.state.duplicateOf)
        assertTrue(model.state.duplicateKeyLost)
        assertEquals("Public fixture 1", model.state.identity?.name)
        assertEquals(listOf(Call.IsDeviceKeyLost(existing.id)), store.calls<Call.IsDeviceKeyLost>())

        model.setPasswords()
        model.resetPassword()
        model.createWallet()
        assertTrue(store.calls<Call.ResetPassword>().isEmpty())
        assertTrue(store.calls<Call.CreateWallet>().isEmpty())

        model.openLostKeyWallet()
        assertEquals(listOf<SetupEvent>(SetupEvent.Finish(WalletDetailRoute(existing.id))), events.filterIsInstance<SetupEvent.Finish>())
        assertFalse(model.hasDraftSecrets(), "the phrase and the passwords are erased")
        assertTrue(model.hasEnded)
    }

    @Test
    fun aWalletWhoseKeyIsNotProvablyLostIsResetAsBefore() = runTest(dispatcher) {
        val existing = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
        store.setState { it.copy(wallets = listOf(existing)) }
        store.onExistingWallet = { _, _ -> existing.id }
        val model = started(SetupIntent.Restore)
        model.type(model.fields.phrase, FakeSignerStore.PUBLIC_FIXTURE_12)
        model.startImport()
        assertEquals(existing.id, model.state.duplicateOf)
        assertFalse(model.state.duplicateKeyLost)
        model.openLostKeyWallet()
        assertFalse(model.hasEnded, "only a lost key leads away")
    }
}
