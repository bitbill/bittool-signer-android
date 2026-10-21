package com.bittool.signer.feature.setup.reveal

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultSecret
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.core.navigation.RecoveryPhraseRoute
import com.bittool.signer.core.security.RecoverySessionFactory
import com.bittool.signer.core.ui.password.wipe
import com.bittool.signer.feature.setup.flow.RecoveryWords
import com.bittool.signer.feature.setup.flow.WalletIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The decrypted phrase as the backup panel shows it. Recovery material: [toString] is redacted. */
@Immutable
class RevealedPhrase(val words: RecoveryWords, val passphrase: String) {
    val hasPassphrase: Boolean get() = passphrase.isNotEmpty()
    override fun equals(other: Any?): Boolean = other is RevealedPhrase && other.words == words && other.passphrase == passphrase
    override fun hashCode(): Int = words.hashCode() * 31 + passphrase.hashCode()
    override fun toString(): String = "RevealedPhrase(<redacted>)"
}

@Immutable
data class RecoveryPhraseUiState(
    val identity: WalletIdentity? = null,
    val busy: Boolean = false,
    val error: UiText? = null,
    /** Non-null once the password unlocked the wallet; the page then shows the backup panel. */
    val revealed: RevealedPhrase? = null,
    /** `SignerState.securityEpoch`: the panel is rebuilt (and the phrase discarded) when it changes. */
    val securityEpoch: Long = 0,
    /**
     * Cancel / Done closed the page: everything is wiped and the page is leaving. It shows neither the phrase nor the
     * password form again on its way out (no form flash, no keyboard raised by the form's delayed focus).
     */
    val closing: Boolean = false,
) {
    override fun toString(): String = "RecoveryPhraseUiState(busy=$busy, revealed=${revealed != null}, closing=$closing)"
}

/** One-off results of the page. */
sealed interface RecoveryPhraseEvent {
    /** Cancel / Done / Back, or the wallet no longer exists: pop the page. */
    data object Close : RecoveryPhraseEvent
}

/**
 * The saved wallet's "Recovery Phrase" page (port of iOS `RecoveryPhraseSheet`,
 * `Features/Wallets/WalletSecuritySheets.swift:36-121`, with its `WalletOperation`; setup-backup §10): the wallet
 * password unlocks the vault once, then the same backup panel as setup shows the words (and a BIP39 passphrase).
 *
 * - The password lives in [password] (a `TextFieldState` created here, never saved) and is cleared the moment it is
 *   submitted. The decrypted [VaultSecret] stays in this ViewModel only, is wiped on [clear], and never reaches saved
 *   state.
 * - Any `securityEpoch` change (background, App Lock, capture) discards the decrypted secret: the password must be
 *   entered again. A result that arrives after that, or after the page closed, is dropped (the store also wipes it).
 * - Begins a sensitive session when created and ends it in [onCleared].
 */
@HiltViewModel
class RecoveryPhraseViewModel internal constructor(
    walletIdText: String,
    private val store: SignerStore,
    /** Display permission for the concealed words, QR code and cards. */
    val recoverySessions: RecoverySessionFactory,
) : ViewModel() {

    @Inject
    constructor(savedStateHandle: SavedStateHandle, store: SignerStore, recoverySessions: RecoverySessionFactory) :
        this(savedStateHandle.toRoute<RecoveryPhraseRoute>().walletId, store, recoverySessions)

    val walletId: WalletId? = WalletId.parse(walletIdText)

    /** The wallet password being typed. */
    val password = TextFieldState()

    private val _state = MutableStateFlow(RecoveryPhraseUiState())
    private val events = Channel<RecoveryPhraseEvent>(Channel.BUFFERED)
    val eventFlow: Flow<RecoveryPhraseEvent> = events.receiveAsFlow()

    private var secret: VaultSecret? = null
    private var token = 0L
    private var job: Job? = null

    val uiState: StateFlow<RecoveryPhraseUiState> = combine(_state, store.state, ::present)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), present(_state.value, store.state.value))

    private fun present(state: RecoveryPhraseUiState, signer: SignerState): RecoveryPhraseUiState = state.copy(
        securityEpoch = signer.securityEpoch,
        identity = walletId?.let { id -> WalletIdentity(signer.displayName(id).orEmpty(), signer.fingerprint(id), signer.avatarIdentity(id)) },
    )

    init {
        store.beginSensitiveSession()
        var epoch = store.state.value.securityEpoch
        var closedForMissingWallet = false
        viewModelScope.launch {
            store.state.collect { signer ->
                if (signer.securityEpoch != epoch) {
                    epoch = signer.securityEpoch
                    // "Backgrounding discards the decrypted phrase; the password is re-entered."
                    clear()
                }
                // A restored back stack can outlive its wallet (ARCHITECTURE §2.4): the page closes itself.
                val gone = walletId == null || (!signer.isLoading && signer.walletLoadSucceeded && signer.wallet(walletId) == null)
                if (gone && !closedForMissingWallet) {
                    closedForMissingWallet = true
                    close()
                }
            }
        }
    }

    /** "Continue" (or the keyboard action): unlocks with the typed password, which is cleared at once. */
    fun unlock() {
        val id = walletId ?: return
        if (password.text.isEmpty() || _state.value.busy || _state.value.closing) return
        val entered = SecretText.of(password.text)
        password.wipe()
        val operation = ++token
        val epoch = store.state.value.securityEpoch
        _state.update { it.copy(busy = true, error = null) }
        job = viewModelScope.launch {
            try {
                val revealed = store.reveal(id, entered)
                if (operation != token || epoch != store.state.value.securityEpoch) {
                    revealed.close()
                    return@launch
                }
                secret?.close()
                secret = revealed
                // The field stays editable while unlocking (QA wallets-05): whatever was typed meanwhile goes too.
                password.wipe()
                val words = RecoveryWords(revealed.mnemonic.view().split(' ').filter { it.isNotEmpty() })
                _state.update { it.copy(revealed = RevealedPhrase(words, revealed.passphrase.view().toString())) }
            } catch (_: CancellationException) {
                // Silent: a late result, a suspended session or a closed page.
            } catch (e: Exception) {
                val text = e.toUiText()
                if (operation == token && epoch == store.state.value.securityEpoch && text != null) _state.update { it.copy(error = text) }
            } finally {
                entered.wipe()
                if (operation == token) _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Cancel / Done: everything is cleared, then the page closes (iOS `clear(); dismiss()`). Once only. */
    fun close() {
        if (_state.value.closing) return
        // Closing first: a state without the phrase that is not closing would bring the password form back for the
        // exit animation, and its delayed focus would open the keyboard over the page underneath.
        _state.update { it.copy(closing = true) }
        clear()
        events.trySend(RecoveryPhraseEvent.Close)
    }

    /** System Back: consumed while unlocking (iOS disables interactive dismissal while busy); otherwise [close]. */
    fun back() {
        if (_state.value.busy) return
        close()
    }

    /**
     * iOS `clear()`: cancels the operation, empties the password (and its undo history: Ctrl+Z must not bring back the
     * password that unlocked a phrase backgrounding discarded) and drops the decrypted phrase.
     */
    fun clear() {
        token++
        job?.cancel()
        job = null
        password.wipe()
        secret?.close()
        secret = null
        _state.update { RecoveryPhraseUiState(closing = it.closing) }
    }

    override fun onCleared() {
        clear()
        store.endSensitiveSession()
    }
}
