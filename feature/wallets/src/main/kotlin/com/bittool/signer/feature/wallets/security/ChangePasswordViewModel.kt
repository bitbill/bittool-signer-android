package com.bittool.signer.feature.wallets.security

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.PasswordPolicy
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.feature.wallets.common.WalletArgs
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.common.WalletOperation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

/** The Change Password page (iOS `ChangePasswordSheet`, wallets-pairing.md §8.3). Holds no password. */
@Immutable
data class SecurityPageUiState(
    val identity: WalletIdentity? = null,
    val busy: Boolean = false,
    val error: UiText? = null,
    /** Renewed by every suspension (background, App Lock, capture): typed passwords are cleared unless busy. */
    val securityEpoch: Long = 0,
    val gone: Boolean = false,
) {
    internal companion object {
        /** The page of [walletId] in [state] with the operation's [op] state (also the first frame's value). */
        fun of(state: SignerState, op: WalletOperation.State, walletId: WalletId?) = SecurityPageUiState(
            identity = walletId?.let { WalletIdentity.of(state, it) },
            busy = op.busy,
            error = op.error,
            securityEpoch = state.securityEpoch,
            gone = WalletIdentity.isGone(state, walletId),
        )
    }
}

/** One-off results of the wallet security pages. */
sealed interface SecurityPageEvent {
    /** The operation succeeded: clear the fields and close the page. */
    data object Done : SecurityPageEvent
}

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val store: SignerStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val walletId: WalletId? = WalletArgs.walletId(savedStateHandle)
    private val operation = WalletOperation(viewModelScope) { store.state.value.securityEpoch }
    private val events = Channel<SecurityPageEvent>(Channel.BUFFERED)

    val uiState: StateFlow<SecurityPageUiState> = combine(store.state, operation.state) { state, op -> SecurityPageUiState.of(state, op, walletId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecurityPageUiState.of(store.state.value, operation.state.value, walletId))

    val pageEvents: Flow<SecurityPageEvent> = events.receiveAsFlow()

    /**
     * "Change Password" (`vaultManagement.submit`, `WalletSecuritySheets.swift:177-185`): enabled only when the current
     * password is non-empty, the new one meets the policy and the confirmation matches. The vault re-encrypts after
     * verifying [current]; nothing else changes. Takes ownership of the three secrets and wipes them. On failure the
     * page keeps the typed values (unlike the other security pages), so the person can correct one field.
     */
    fun submit(current: SecretText, new: SecretText, confirmation: SecretText) {
        val id = walletId
        val valid = isValid(current, new, confirmation)
        confirmation.wipe()
        if (id == null || !valid || operation.busy) {
            current.wipe()
            new.wipe()
            return
        }
        operation.run {
            try {
                store.changePassword(id, current, new)
            } finally {
                current.wipe()
                new.wipe()
            }
            events.send(SecurityPageEvent.Done)
        }
    }

    /** Cancel / Back: the run is forgotten (its late result never shows). */
    fun cancel() = operation.cancel()

    companion object {
        /** iOS `valid`: `!current.isEmpty && PasswordPolicy.evaluate(new).isValid && new == confirmation` (constant time). */
        fun isValid(current: SecretText, new: SecretText, confirmation: SecretText): Boolean =
            current.isNotEmpty() && PasswordPolicy.evaluate(new.view()).isValid && PasswordPolicy.matches(new.view(), confirmation.view())

        /** The same rule on the live field texts, for the button's enabled state (no copy of the secrets). */
        fun isValid(current: CharSequence, new: CharSequence, confirmation: CharSequence): Boolean =
            current.isNotEmpty() && PasswordPolicy.evaluate(new).isValid && PasswordPolicy.matches(new, confirmation)
    }
}
