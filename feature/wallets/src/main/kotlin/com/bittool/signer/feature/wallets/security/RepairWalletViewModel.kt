package com.bittool.signer.feature.wallets.security

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.SecretText
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

/**
 * The Repair Wallet page (iOS `RepairWalletSheet`, wallets-pairing.md §8.5): the wallet password decrypts the wallet
 * once to rebuild its public keys and addresses; the phrase and the password do not change. Holds no password.
 */
@HiltViewModel
class RepairWalletViewModel @Inject constructor(
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
     * "Repair Wallet" (`WalletSecuritySheets.swift:336-344`): vault unlock → native seed → `derivePublicIndex` → the
     * sidecar is saved with the current name → reload; success closes the page and the wallet page shows its ready
     * sections. Takes ownership of [password] and wipes it.
     */
    fun submit(password: SecretText) {
        val id = walletId
        if (id == null || password.isEmpty() || operation.busy) {
            password.wipe()
            return
        }
        operation.run {
            try {
                store.completeSetup(id, password)
            } finally {
                password.wipe()
            }
            events.send(SecurityPageEvent.Done)
        }
    }

    /** Cancel / Back / leaving the page cancels the operation (`onDisappear { operation.cancel() }`). */
    fun cancel() = operation.cancel()
}
