package com.bittool.signer.feature.setup.flow

import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.common.coroutines.DefaultDispatcher
import com.bittool.signer.core.crypto.SecureRandomSource
import com.bittool.signer.core.crypto.bip39.Bip39
import com.bittool.signer.core.crypto.bip39.Bip39Canonical
import com.bittool.signer.core.crypto.bip39.Bip39Error
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.crypto.vault.DeviceKeyStore
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.BackupVerification
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.navigation.ConnectRoute
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.navigation.WalletDetailRoute
import com.bittool.signer.core.navigation.WalletRoute
import com.bittool.signer.core.security.RecoverySessionFactory
import com.bittool.signer.core.ui.keyboard.PhraseKeyboard
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bittool.signer.core.l10n.R as L10n

/** One-off results of the flow, collected by the host (ARCHITECTURE §4.1). */
sealed interface SetupEvent {
    /** Cancel / Discard / Back on the first step: the presentation ends without a wallet. */
    data object Close : SetupEvent

    /** The Done step's buttons: [route] opens after the overlay disappeared (`ConnectRoute.walletLevel` or null). */
    data class Finish(val route: WalletRoute?) : SetupEvent

    /** The backup check was answered wrongly (reject haptic, ARCHITECTURE §4.5). */
    data object VerificationRejected : SetupEvent

    /**
     * The wallet was created, or its password reset: the success haptic (iOS `.sensoryFeedback(.success)` when
     * `created` becomes non-nil). Sent once, when the commit lands, not whenever the Done page is composed again.
     */
    data object Saved : SetupEvent
}

/**
 * The in-memory draft of one create or import presentation: the port of iOS `WalletSetupModel`
 * (`Features/Setup/WalletSetupModel.swift`, setup-backup §2) plus the host hooks of `WalletSetupFlow` (§1.5). It is the
 * only owner of secrets during setup and keeps them in memory only: the generated or accepted phrase as a wipeable
 * [SecretText], the typed text in [fields] (`TextFieldState`s created here, never saved). Nothing goes into a
 * `SavedStateHandle`; process death discards the draft as on iOS.
 *
 * Lifecycle: created when the presentation's flow appears (`SignerStore.beginSensitiveSession()`); every
 * `securityEpoch` change runs [suspendDraft] (passwords, backup answers and the error cleared, the phrase kept); every
 * `navigationResetEpoch` change clears the secrets unless a wallet was created; [onCleared] clears the secrets and ends
 * the sensitive session.
 *
 * Operations follow iOS `perform`: one at a time, an operation token renewed by every [suspendDraft] /
 * [clearSecrets] so a late result is not applied, and errors shown only while the token and the security epoch are
 * unchanged. Generation and the duplicate check are cancelled with their token. **The commits are never cancelled**:
 * `createWallet` and `resetPassword` apply `created` and push Done whenever the store returns, even after a background
 * transition ("A committed wallet is the success boundary"; the store itself finishes the commit non-cancellably).
 *
 * Main thread only, like the store. BIP39 analysis (every keystroke of the import editor, the import itself and the
 * recovery QR check) runs on [analysisDispatcher].
 *
 * Android additions: [deviceKeys] tells whether the device has a secure lock screen, which every wallet's device key
 * needs; without one the first step says so up front and does not start (QA wallets-04, [refreshDeviceSecurity]). The
 * duplicate check of the password step holds its action until it answers (QA setup-08) and recognises a wallet whose
 * device key is provably gone (QA backlog B21, [openLostKeyWallet]).
 */
@HiltViewModel(assistedFactory = WalletSetupViewModel.Factory::class)
class WalletSetupViewModel @AssistedInject constructor(
    @Assisted val intent: SetupIntent,
    private val store: SignerStore,
    private val bip39: Bip39,
    private val random: SecureRandomSource,
    /** Display permission for the concealed backup material (the screens create one session per display). */
    val recoverySessions: RecoverySessionFactory,
    @DefaultDispatcher private val analysisDispatcher: CoroutineDispatcher,
    /** Only [DeviceKeyStore.isDeviceSecure] is used (a stateless lock-screen query, safe outside the vault's lock). */
    private val deviceKeys: DeviceKeyStore,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(intent: SetupIntent): WalletSetupViewModel
    }

    /** The typed text of this presentation (name, import editor, passphrase, wallet password). */
    val fields = SetupFields()

    /**
     * Which keyboard types into the import editor (Android-only, setup-backup §15.8): BitTool's own keyboard, which no
     * input method can see, unless the person chose the system keyboard after the warning (a Chinese, Japanese or
     * Korean phrase). Every presentation starts with the in-app keyboard; the choice then holds for its steps. Not a
     * secret. Snapshot state rather than [uiState], so the editor and the keyboard switch in the same frame.
     */
    var phraseKeyboard: PhraseKeyboard by mutableStateOf(PhraseKeyboard.InApp)
        private set

    fun usePhraseKeyboard(keyboard: PhraseKeyboard) {
        phraseKeyboard = keyboard
    }

    /** iOS `existingWallets`, captured when the presentation starts. */
    val existingWallets: Int = store.state.value.wallets.size

    /** iOS `name` default, resolved by the UI in the current language and handed back with [seedName]. */
    val defaultName: UiText = if (existingWallets == 0) UiText.Ios("My Wallet") else UiText.Ios("Wallet %lld", listOf(existingWallets + 1))

    private val _state = MutableStateFlow(WalletSetupUiState(intent = intent, deviceSecure = deviceKeys.isDeviceSecure()))

    private val events = Channel<SetupEvent>(Channel.BUFFERED)

    /** One-off results for the host. */
    val eventFlow: Flow<SetupEvent> = events.receiveAsFlow()

    /** The canonical phrase: generated, or accepted on import (iOS `mnemonic`). */
    private var mnemonic: SecretText? = null

    /** Language of an accepted import (iOS `importedLanguage`); generated phrases are English. */
    var importedLanguage: Bip39Language? = null
        private set

    /** Exact text read from a QR code, taken with its spelling while the editor still holds exactly it. */
    private var scannedPhrase: String? = null

    private var nameSeeded = false

    /**
     * Set once [SetupEvent.Close] or [SetupEvent.Finish] went out: a presentation has exactly one outcome. The overlay
     * stays on screen (and touchable) while it animates away, so without this a Back or a second tap during that
     * animation would send another outcome, e.g. replace "Pair a Wallet"'s Connect route by `null`.
     */
    private var ended = false
    private var operationCounter = 0L
    private var operation = 0L
    private var checkCounter = 0L
    private var generationJob: Job? = null
    private var checkJob: Job? = null
    private var importJob: Job? = null
    private var scanJob: Job? = null

    /** Import status and suggestions, recomputed off the main thread on every text change (setup-backup §11.8). */
    private val analysis: Flow<PhraseAnalysis> = if (intent == SetupIntent.Restore) {
        snapshotFlow { fields.phrase.text.toString() }
            .distinctUntilChanged()
            .mapLatest { text -> withContext(analysisDispatcher) { analyze(text) } }
    } else {
        flowOf(PhraseAnalysis.Empty)
    }

    val uiState: StateFlow<WalletSetupUiState> = combine(_state, analysis, store.state, ::present)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), present(_state.value, PhraseAnalysis.Empty, store.state.value))

    init {
        // iOS WalletSetupFlow `onAppear`: a phrase generated after the flow closed or re-opened is discarded.
        store.beginSensitiveSession()
        var securityEpoch = store.state.value.securityEpoch
        var navigationReset = store.state.value.navigationResetEpoch
        viewModelScope.launch {
            store.state.collect { signer ->
                if (signer.securityEpoch != securityEpoch) {
                    securityEpoch = signer.securityEpoch
                    suspendDraft()
                }
                if (signer.navigationResetEpoch != navigationReset) {
                    navigationReset = signer.navigationResetEpoch
                    if (_state.value.created == null) clearSecrets()
                }
            }
        }
        if (intent == SetupIntent.Restore) {
            // The first Continue checks every list (exact readings): load them now, off the main thread.
            viewModelScope.launch(analysisDispatcher) { Bip39Language.entries.forEach { bip39.wordlist(it) } }
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Derived values (iOS computed properties)

    /** iOS `hasDraftSecrets`: the confirmations do not count. */
    fun hasDraftSecrets(): Boolean = _state.value.created == null &&
        (mnemonic?.isNotEmpty() == true || fields.phrase.text.isNotEmpty() || fields.passphrase.text.isNotEmpty() ||
            fields.password.text.isNotEmpty())

    /** Whether system Back / the bar's back arrow pops a step (with the predictive animation). */
    fun canPop(): Boolean = _state.value.let { !it.busy && it.path.isNotEmpty() && it.step != SetupStep.Done }

    /**
     * Whether system Back now ends the presentation (setup-backup §15.4): the form without draft secrets (Cancel) or
     * the Done step ("Done"). The gesture then previews leaving; with a draft it asks first, while busy it is consumed.
     */
    fun backEndsPresentation(): Boolean = _state.value.let {
        !ended && !it.busy && (it.step == SetupStep.Done || (it.path.isEmpty() && !hasDraftSecrets()))
    }

    /** The presentation sent its outcome ([SetupEvent.Close] / [SetupEvent.Finish]); the overlay is on its way out. */
    val hasEnded: Boolean get() = ended

    // -------------------------------------------------------------------------------------------------------------
    // Form

    /** Puts the default name into the empty field once (iOS localises it when the model is created). */
    fun seedName(localized: String) {
        if (nameSeeded) return
        nameSeeded = true
        if (fields.name.text.isEmpty()) fields.name.setTextAndPlaceCursorAtEnd(localized)
    }

    fun setWordCount(count: Int) {
        if (count in Bip39.SUPPORTED_LENGTHS) _state.update { it.copy(wordCount = count) }
    }

    /** The passphrase fields hide but keep their text when switched off (it still counts as a draft secret). */
    fun setUsePassphrase(use: Boolean) = _state.update { it.copy(usePassphrase = use) }

    /**
     * Reads the lock screen again (QA wallets-04): when the flow resumes, e.g. back from the system page "Open
     * Settings" opened. Returns whether the device is secure now.
     */
    fun refreshDeviceSecurity(): Boolean {
        val secure = deviceKeys.isDeviceSecure()
        if (secure != _state.value.deviceSecure) _state.update { it.copy(deviceSecure = secure) }
        return secure
    }

    /** iOS `startCreate` (setup-backup §2.2): the same phrase is kept unless the word count changed. */
    fun startCreate() {
        if (_state.value.busy || !refreshDeviceSecurity() || !validateOptions()) return
        generationJob = perform { token ->
            val count = _state.value.wordCount
            val current = mnemonic
            if (current == null || current.isEmpty() || wordsOf(current).size != count) {
                val generated = store.generateMnemonic(count)
                if (operation != token) {
                    generated.wipe()
                    return@perform
                }
                mnemonic?.wipe()
                mnemonic = generated
                _state.update { it.copy(words = RecoveryWords(wordsOf(generated)), hasRevealedBackup = false, verification = null) }
            }
            setPath(listOf(SetupStep.Backup))
        }
    }

    /**
     * iOS `startImport` (§2.2): an unedited scan is taken with its exact spelling; otherwise the forgiving
     * `interpretations` decide — one reading is accepted, several open the language choice, a failure is the error.
     * Analysed off the main thread (the first call loads every wordlist); a second tap while it runs is ignored.
     */
    fun startImport() {
        if (_state.value.busy || importJob?.isActive == true || !refreshDeviceSecurity() || !validateOptions()) return
        val text = fields.phrase.text.toString()
        val scanned = scannedPhrase
        val token = operation
        importJob = viewModelScope.launch {
            val outcome: Result<List<Bip39Canonical>> = withContext(analysisDispatcher) {
                if (scanned != null && scanned == text) {
                    runCatching { bip39.validatedCanonical(scanned) }.getOrNull()?.let { return@withContext Result.success(listOf(it)) }
                }
                try {
                    Result.success(bip39.interpretations(text))
                } catch (e: Bip39Error) {
                    Result.failure(e)
                }
            }
            if (operation != token) return@launch
            outcome.fold(
                onSuccess = { readings ->
                    if (readings.size == 1) acceptImport(readings.single()) else _state.update { it.copy(languageChoices = readings) }
                },
                onFailure = { error -> _state.update { it.copy(error = (error as SignerException).text) } },
            )
        }
    }

    /** iOS `acceptImport`: a different phrase is a different question, so the password step asks again. */
    fun acceptImport(reading: Bip39Canonical) {
        mnemonic?.wipe()
        mnemonic = SecretText.of(reading.phrase)
        importedLanguage = reading.language
        _state.update { it.copy(languageChoices = emptyList(), error = null, duplicateOf = null, duplicateKeyLost = false) }
        setPath(listOf(SetupStep.Password))
    }

    /** Closing "Which language is your backup in?" without a choice. */
    fun dismissLanguageChoices() = _state.update { it.copy(languageChoices = emptyList()) }

    /** iOS `applyScanned`: fills the editor with a scanned phrase (words or SeedQR). */
    fun applyScanned(phrase: String) {
        scannedPhrase = phrase
        fields.phrase.setTextAndPlaceCursorAtEnd(phrase)
        _state.update { it.copy(error = null) }
    }

    /**
     * The suggestion bar's chip (iOS `RecoveryPhraseAssistant.complete(with:)`): the trailing run matching
     * `[^\s,，、]+$` becomes [word] and a space; nothing happens when the text does not end inside a word. Known quirk kept
     * for parity (setup-backup §11.8): numbering is not a token, so after "aban 1" the chip for "aban" replaces the "1".
     */
    fun insertCandidate(word: String) {
        val text = fields.phrase.text.toString()
        val completed = Bip39.insertingCandidate(text, word)
        if (completed == text) return
        fields.phrase.edit {
            replace(0, length, completed)
            placeCursorAtEnd()
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Recovery QR scanner (import)

    fun openScanner() = _state.update { it.copy(scanning = true, scanError = null) }

    fun closeScanner() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = false, scanError = null) }
    }

    fun resetScanError() = _state.update { it.copy(scanError = null) }

    /**
     * A text the scanner accepted (iOS `WalletSetupFlow.swift:218-228`): a Standard SeedQR becomes its English words, a
     * text with at least one valid reading in any language is taken trimmed (an ambiguous one too: Continue asks), and
     * anything else shows "This QR code does not contain a recovery phrase." The SeedQR checksum is checked on Continue.
     */
    fun onRecoveryQrScanned(text: String) {
        if (!_state.value.scanning) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val phrase = withContext(analysisDispatcher) { bip39.recoveryPhraseFromQr(text) }
            if (!_state.value.scanning) return@launch
            if (phrase != null) {
                applyScanned(phrase)
                _state.update { it.copy(scanning = false, scanError = null) }
            } else {
                _state.update { it.copy(scanError = UiText.Resource(L10n.string.setup_qr_not_recovery_phrase)) }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------------------
    // Backup and check (create)

    /** The words grid or the recovery cards were shown (the QR code alone does not count, setup-backup §6.2). */
    fun markRevealed() = _state.update { it.copy(hasRevealedBackup = true) }

    /** iOS `confirmBackup`: "I’ve Written Them Down in Order" starts the fill-in check. */
    fun confirmBackup() {
        val state = _state.value
        if (!state.hasRevealedBackup || state.words.isEmpty()) return
        try {
            val verification = BackupVerification.create(state.words.list, random.asKotlinRandom())
            _state.update { it.copy(verification = verification, error = null) }
            setPath(state.path + SetupStep.Verify)
        } catch (e: SignerException) {
            _state.update { it.copy(error = e.text) }
        }
    }

    fun place(chipId: Int) = _state.update { s -> s.copy(verification = s.verification?.let { it.place(chipId) ?: it }, error = null) }

    fun clear(position: Int) = _state.update { s -> s.copy(verification = s.verification?.clear(position), error = null) }

    /** Does not clear the error (iOS). */
    fun select(position: Int) = _state.update { s -> s.copy(verification = s.verification?.select(position)) }

    /** iOS `finishVerification`: a wrong answer keeps the blanks and the chip order and empties every blank. */
    fun finishVerification() {
        val state = _state.value
        val verification = state.verification
        if (verification?.isCorrect != true) {
            _state.update {
                it.copy(
                    error = UiText.Ios("Some words don't match. Check your written backup and try again."),
                    verification = it.verification?.resetPlacements(),
                )
            }
            events.trySend(SetupEvent.VerificationRejected)
            return
        }
        _state.update { it.copy(error = null) }
        setPath(state.path + SetupStep.Password)
    }

    /** iOS `reviewPhraseAgain`: back to the same, concealed phrase. */
    fun reviewPhraseAgain() {
        _state.update { it.copy(verification = null, error = null, duplicateOf = null, duplicateKeyLost = false) }
        setPath(listOf(SetupStep.Backup))
    }

    /** A screenshot of the check (iOS `recoveryScreenshotWarning` callback): the placements and the error are cleared. */
    fun onVerificationScreenshot() = _state.update { it.copy(verification = it.verification?.resetPlacements(), error = null) }

    // -------------------------------------------------------------------------------------------------------------
    // Password

    /**
     * iOS `createWallet`: the commit is the success boundary (no token check after it). Waits for the duplicate check
     * (QA setup-08) and is refused for a phrase that already has a wallet here.
     */
    fun createWallet() {
        val phrase = mnemonic
        if (!fields.passwordValid() || phrase == null || phrase.isEmpty()) return
        _state.value.let { if (it.busy || it.checkingExisting || it.duplicateOf != null) return }
        val name = fields.trimmedName()
        val mnemonicCopy = phrase.copy()
        val passphrase = currentPassphrase()
        val password = SecretText.of(fields.password.text)
        perform(onSkipped = { wipeAll(mnemonicCopy, passphrase, password) }) {
            try {
                val wallet = store.createWallet(name, mnemonicCopy, passphrase, password)
                _state.update { it.copy(created = wallet) }
                events.trySend(SetupEvent.Saved)
                clearSecrets()
                setPath(_state.value.path + SetupStep.Done)
            } finally {
                wipeAll(mnemonicCopy, passphrase, password)
            }
        }
    }

    /** iOS `resetPassword`: the entered phrase is the proof; the wallet keeps everything but its password. */
    fun resetPassword() {
        val walletId = _state.value.duplicateOf ?: return
        if (_state.value.duplicateKeyLost) return
        val phrase = mnemonic
        if (!fields.passwordValid() || phrase == null || phrase.isEmpty()) return
        if (_state.value.busy) return
        val mnemonicCopy = phrase.copy()
        val passphrase = currentPassphrase()
        val password = SecretText.of(fields.password.text)
        perform(onSkipped = { wipeAll(mnemonicCopy, passphrase, password) }) {
            try {
                store.resetPassword(walletId, mnemonicCopy, passphrase, password)
                val wallet = store.state.value.wallet(walletId)
                _state.update { it.copy(created = wallet, didResetPassword = true) }
                events.trySend(SetupEvent.Saved)
                clearSecrets()
                setPath(_state.value.path + SetupStep.Done)
            } finally {
                wipeAll(mnemonicCopy, passphrase, password)
            }
        }
    }

    /**
     * iOS `checkForExistingWallet`, run when the password step appears: switches it into "Reset Password" mode when the
     * phrase and passphrase already belong to a wallet here. Failures are ignored (`createWallet` refuses duplicates).
     *
     * Android: [WalletSetupUiState.checkingExisting] holds the step's action until the answer (QA setup-08), and a
     * duplicate whose device key is provably gone is reported as such in the same update (QA backlog B21): Reset
     * Password could only fail with "device key unavailable", so the step leads to that wallet's removal instead.
     */
    private fun checkForExistingWallet() {
        val state = _state.value
        val phrase = mnemonic ?: return
        if (phrase.isEmpty() || state.created != null || state.duplicateOf != null || state.busy) return
        val mnemonicCopy = phrase.copy()
        val passphrase = currentPassphrase()
        val token = operation
        val epoch = store.state.value.securityEpoch
        checkJob?.cancel()
        val check = ++checkCounter
        _state.update { it.copy(checkingExisting = true) }
        checkJob = viewModelScope.launch {
            try {
                val existing = store.existingWallet(mnemonicCopy, passphrase)
                val current = { operation == token && epoch == store.state.value.securityEpoch && _state.value.created == null }
                if (existing != null && current()) {
                    val lost = store.isDeviceKeyLost(existing)
                    if (current()) _state.update { it.copy(duplicateOf = existing, duplicateKeyLost = lost) }
                }
            } catch (_: Exception) {
                // The question stays unanswered (also when cancelled); nothing follows.
            } finally {
                wipeAll(mnemonicCopy, passphrase)
                if (check == checkCounter) _state.update { it.copy(checkingExisting = false) }
            }
        }
    }

    /**
     * "Open Wallet" of a phrase whose wallet here lost its device key (QA backlog B21): the draft is erased and setup
     * closes onto that wallet's page, where Delete Wallet offers "Remove This Wallet" (B9). The phrase can be imported
     * again afterwards.
     */
    fun openLostKeyWallet() {
        val state = _state.value
        val walletId = state.duplicateOf ?: return
        if (!state.duplicateKeyLost || state.busy || ended) return
        clearSecrets()
        end(SetupEvent.Finish(WalletDetailRoute(walletId)))
    }

    // -------------------------------------------------------------------------------------------------------------
    // Navigation

    /**
     * Cancel (first step's close button, pushed steps' "Cancel"): with draft secrets "Discard this wallet?" asks first;
     * otherwise the presentation ends. Ignored while busy.
     */
    fun cancel() {
        if (_state.value.busy || ended) return
        if (hasDraftSecrets()) _state.update { it.copy(confirmDiscard = true) } else end(SetupEvent.Close)
    }

    /** "Discard": the secrets are erased, then the presentation ends. */
    fun discard() {
        _state.update { it.copy(confirmDiscard = false) }
        clearSecrets()
        end(SetupEvent.Close)
    }

    fun keepEditing() = _state.update { it.copy(confirmDiscard = false) }

    /**
     * System Back (setup-backup §15.4): consumed while busy; pops a pushed step; on Done it is "Done"; on the first step
     * it is Cancel.
     */
    fun back() {
        val state = _state.value
        when {
            state.busy || ended -> Unit
            state.step == SetupStep.Done -> finish(pairWallet = false)
            state.path.isNotEmpty() -> pop()
            else -> cancel()
        }
    }

    /**
     * Pops one pushed step (the bar's back arrow, a committed back gesture). The phrase is kept. Leaving the password
     * step also clears both passwords and forgets the duplicate answer (setup-backup §16 items 2 and 3: the documented
     * intent; it is asked again when the step reappears), and the previous step does not inherit this step's error.
     */
    fun pop() {
        if (!canPop()) return
        val state = _state.value
        val leaving = state.step
        if (leaving == SetupStep.Password) {
            fields.clearPasswords()
            cancelExistingCheck()
        }
        _state.update {
            if (leaving == SetupStep.Password) {
                it.copy(path = it.path.dropLast(1), error = null, duplicateOf = null, duplicateKeyLost = false)
            } else {
                it.copy(path = it.path.dropLast(1), error = null)
            }
        }
    }

    /** The Done step: "Pair a Wallet" ([pairWallet]) opens Connect on the wallet-level QR, "Done" opens nothing. */
    fun finish(pairWallet: Boolean) {
        val state = _state.value
        val created = state.created
        val route = if (pairWallet && created != null && !state.didResetPassword) ConnectRoute.walletLevel(created.id) else null
        end(SetupEvent.Finish(route))
    }

    /** Sends the presentation's one outcome; later ones are dropped ([ended]). */
    private fun end(outcome: SetupEvent) {
        if (ended) return
        ended = true
        events.trySend(outcome)
    }

    // -------------------------------------------------------------------------------------------------------------
    // Lifecycle

    /**
     * iOS `suspend()` (background, App Lock, screen capture): a new token, not busy, the passwords, the error and the
     * check's placements cleared. The phrase, the editor, the name, the passphrase, `hasRevealedBackup` and the path stay.
     *
     * Android (QA setup-08): a duplicate check that was still running is asked again under the new token, so the
     * password step keeps its action held until it has an answer. iOS drops the late answer and never asks again on
     * the same step, which leaves "Import Wallet" leading to the duplicate refusal.
     */
    fun suspendDraft() {
        val unanswered = _state.value.checkingExisting
        operation = ++operationCounter
        generationJob?.cancel()
        cancelExistingCheck()
        importJob?.cancel()
        fields.clearPasswords()
        _state.update { it.copy(busy = false, error = null, verification = it.verification?.resetPlacements()) }
        if (unanswered && _state.value.step == SetupStep.Password) checkForExistingWallet()
    }

    /** iOS `clearSecrets()`: every secret goes; the path, the name, `created` and `didResetPassword` stay. */
    fun clearSecrets() {
        operation = ++operationCounter
        generationJob?.cancel()
        cancelExistingCheck()
        importJob?.cancel()
        scanJob?.cancel()
        mnemonic?.wipe()
        mnemonic = null
        importedLanguage = null
        scannedPhrase = null
        fields.clearSecrets()
        _state.update {
            it.copy(
                busy = false,
                words = RecoveryWords.Empty,
                languageChoices = emptyList(),
                verification = null,
                hasRevealedBackup = false,
                duplicateOf = null,
                duplicateKeyLost = false,
            )
        }
    }

    /**
     * Stops the duplicate check; its question is asked again when the password step reappears ([suspendDraft] asks it
     * again at once while the step stays).
     */
    private fun cancelExistingCheck() {
        checkJob?.cancel()
        checkJob = null
        checkCounter++
        if (_state.value.checkingExisting) _state.update { it.copy(checkingExisting = false) }
    }

    override fun onCleared() {
        // iOS WalletSetupFlow `onDisappear`.
        clearSecrets()
        store.endSensitiveSession()
    }

    // -------------------------------------------------------------------------------------------------------------

    private fun setPath(path: List<SetupStep>) {
        _state.update { it.copy(path = path) }
        if (path.lastOrNull() == SetupStep.Password) checkForExistingWallet()
    }

    /** iOS `validateOptions()`: name, its length (graphemes), then the passphrase. */
    private fun validateOptions(): Boolean {
        val name = fields.trimmedName()
        val error = when {
            name.isEmpty() -> UiText.Ios("Enter a wallet name.")
            Graphemes.exceeds(name, NAME_LIMIT) -> UiText.Ios("Use a wallet name of 40 characters or fewer.")
            !fields.passphraseValid(_state.value.usePassphrase) -> UiText.Ios("The BIP39 passphrases must match and cannot be empty.")
            else -> null
        }
        _state.update { it.copy(error = error) }
        return error == null
    }

    /** iOS `perform(store:_:)`. [onSkipped] runs when the call is refused because an operation is in flight. */
    private fun perform(onSkipped: () -> Unit = {}, work: suspend (token: Long) -> Unit): Job? {
        if (_state.value.busy) {
            onSkipped()
            return null
        }
        val id = ++operationCounter
        operation = id
        val epoch = store.state.value.securityEpoch
        _state.update { it.copy(busy = true, error = null) }
        return viewModelScope.launch {
            try {
                if (epoch != store.state.value.securityEpoch) throw CancellationException("The session was suspended.")
                work(id)
            } catch (_: CancellationException) {
                // Silent everywhere (models-state §5.2); nothing suspends after this.
            } catch (e: Exception) {
                val text = e.toUiText()
                if (operation == id && epoch == store.state.value.securityEpoch && text != null) _state.update { it.copy(error = text) }
            }
            if (operation == id) _state.update { it.copy(busy = false) }
        }
    }

    /** The published state: the draft plus what the store knows (the epoch keying the backup panel, wallet identities). */
    private fun present(state: WalletSetupUiState, phrase: PhraseAnalysis, signer: SignerState): WalletSetupUiState {
        val identityId = state.duplicateOf ?: state.created?.id
        return state.copy(
            phrase = phrase,
            securityEpoch = signer.securityEpoch,
            identity = identityId?.let { id ->
                WalletIdentity(
                    name = signer.displayName(id) ?: state.created?.name.orEmpty(),
                    fingerprint = signer.fingerprint(id),
                    avatarIdentity = signer.avatarIdentity(id),
                )
            },
        )
    }

    private fun currentPassphrase(): SecretText =
        if (_state.value.usePassphrase) SecretText.of(fields.passphrase.text) else SecretText.empty()

    private fun analyze(text: String): PhraseAnalysis {
        val draft = bip39.draft(text)
        return PhraseAnalysis(
            wordCount = draft.words.size,
            unknownWords = draft.unknownWords.take(STATUS_UNKNOWN_WORDS),
            language = draft.language,
            looksComplete = draft.looksComplete,
            candidates = bip39.candidates(draft),
        )
    }

    internal companion object {
        /** iOS `trimmedName.count <= 40`. */
        const val NAME_LIMIT = 40

        /** The status line names the first two unknown tokens. */
        const val STATUS_UNKNOWN_WORDS = 2

        /** iOS `mnemonic.split(separator: " ")` (empty pieces dropped). */
        fun wordsOf(secret: SecretText): List<String> = secret.view().split(' ').filter { it.isNotEmpty() }

        fun wipeAll(vararg secrets: SecretText) = secrets.forEach(SecretText::wipe)
    }
}
