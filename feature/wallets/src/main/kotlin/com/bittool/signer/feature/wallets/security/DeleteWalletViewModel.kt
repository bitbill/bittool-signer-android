package com.bittool.signer.feature.wallets.security

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.security.AppAccessController
import com.bittool.signer.feature.wallets.common.WalletArgs
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.common.WalletOperation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Delete Wallet page (iOS `DeleteWalletSheet`, wallets-pairing.md §8.4). Holds no password. */
@Immutable
data class DeleteWalletUiState(
    val identity: WalletIdentity? = null,
    /** "I confirm I want to delete this wallet" (`delete.backupConfirmed`). */
    val confirmed: Boolean = false,
    /** The biometric check before the password is running. */
    val confirming: Boolean = false,
    /** The "Wallet Password" dialog is up. */
    val askingPassword: Boolean = false,
    val busy: Boolean = false,
    val error: UiText? = null,
    /** App Lock is on with biometrics available: the footer says biometrics come first. */
    val asksForBiometrics: Boolean = false,
    val gone: Boolean = false,
) {
    val canDelete: Boolean get() = confirmed && !busy && !confirming
}

@HiltViewModel
class DeleteWalletViewModel @Inject constructor(
    private val store: SignerStore,
    private val access: AppAccessController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val walletId: WalletId? = WalletArgs.walletId(savedStateHandle)
    private val operation = WalletOperation(viewModelScope) { store.state.value.securityEpoch }
    private val events = Channel<SecurityPageEvent>(Channel.BUFFERED)

    private data class Local(val confirmed: Boolean = false, val confirming: Boolean = false, val askingPassword: Boolean = false)

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<DeleteWalletUiState> = combine(store.state, access.state, operation.state, local) { state, accessState, op, l ->
        DeleteWalletUiState(
            identity = walletId?.let { WalletIdentity.of(state, it) },
            confirmed = l.confirmed,
            confirming = l.confirming,
            askingPassword = l.askingPassword,
            busy = op.busy,
            error = op.error,
            asksForBiometrics = accessState.isEnabled && accessState.biometricsAvailable,
            gone = WalletIdentity.isGone(state, walletId),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeleteWalletUiState())

    val pageEvents: Flow<SecurityPageEvent> = events.receiveAsFlow()

    init {
        // Suspension (background, App Lock, capture) closes the password dialog unless the deletion is running; the
        // dialog's field leaves with it (`WalletSecuritySheets.swift:262`).
        viewModelScope.launch {
            store.state.map { it.securityEpoch }.distinctUntilChanged().drop(1).collect {
                if (!operation.busy) local.update { it.copy(askingPassword = false) }
            }
        }
    }

    fun setConfirmed(confirmed: Boolean) = local.update { it.copy(confirmed = confirmed) }

    /**
     * "Delete Wallet" (`vaultManagement.submit`, `WalletSecuritySheets.swift:272-284`): biometrics first for someone who
     * turned App Lock on (dismissed → stop; unavailable or failed → the password still decides), then the password
     * dialog. "The password is what actually authorizes the deletion."
     */
    fun confirm() {
        val current = local.value
        if (!current.confirmed || operation.busy || current.confirming) return
        operation.setError(null)
        local.update { it.copy(confirming = true) }
        viewModelScope.launch {
            try {
                access.confirmSensitiveAction(UiText.Ios(CONFIRM_REASON))
            } catch (dismissed: CancellationException) {
                // The person dismissed the prompt: nothing else happens. A cancellation of this ViewModel propagates.
                local.update { it.copy(confirming = false) }
                currentCoroutineContext().ensureActive()
                return@launch
            } catch (unavailable: Exception) {
                // Biometrics unavailable or failed: the password still decides.
            }
            local.update { it.copy(confirming = false, askingPassword = true) }
        }
    }

    /** The dialog's "Cancel" (and Back): the dialog closes; its field is cleared by the page. */
    fun cancelPassword() = local.update { it.copy(askingPassword = false) }

    /**
     * The dialog's "Delete" (always enabled, `WalletSecuritySheets.swift:286-295`): an empty field shows "Enter this
     * wallet’s password."; otherwise the vault verifies the password before removing anything (a wrong password changes
     * nothing and its error shows on the page). Takes ownership of [password] and wipes it.
     */
    fun submit(password: SecretText) {
        val id = walletId
        local.update { it.copy(askingPassword = false) }
        if (id == null || !local.value.confirmed || operation.busy) {
            password.wipe()
            return
        }
        if (password.isEmpty()) {
            password.wipe()
            operation.setError(UiText.Ios(ENTER_PASSWORD))
            return
        }
        operation.run {
            try {
                store.deleteWallet(id, password)
            } finally {
                password.wipe()
            }
            clear()
            events.send(SecurityPageEvent.Done)
        }
    }

    /** Cancel / Back (iOS `clear()`): the run is forgotten, the confirmation and the dialog reset. */
    fun cancel() {
        operation.cancel()
        clear()
    }

    private fun clear() = local.update { Local() }

    companion object {
        const val CONFIRM_REASON = "Confirm before deleting this wallet."
        const val ENTER_PASSWORD = "Enter this wallet’s password."
    }
}
