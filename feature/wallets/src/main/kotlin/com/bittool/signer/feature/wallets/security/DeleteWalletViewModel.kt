package com.bittool.signer.feature.wallets.security

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.common.coroutines.runCatchingCancellable
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.security.AppAccessController
import com.bittool.signer.core.security.AppAccessState
import com.bittool.signer.feature.wallets.common.WalletArgs
import com.bittool.signer.feature.wallets.common.WalletIdentity
import com.bittool.signer.feature.wallets.common.WalletOperation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
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
    /**
     * Android-only (QA backlog B9): the wallet's device key is provably gone, so no password can unlock it. The page
     * then explains that and offers "Remove This Wallet" without the password (after the same confirmation switch, the
     * biometric check when App Lock is on, and the "Remove This Wallet?" dialog) instead of the iOS password path.
     */
    val deviceKeyLost: Boolean = false,
    /** The "Remove This Wallet?" dialog is up (only while [deviceKeyLost]). */
    val askingRemoval: Boolean = false,
    /**
     * Android-only (QA backlog B22): the first look at the device key has not answered yet (about a second on a device),
     * so the page cannot tell yet whether it deletes with the password or removes a wallet that can't be unlocked. It
     * shows a neutral loading state instead of either statement; later looks keep the last answer on screen.
     */
    val checkingDeviceKey: Boolean = false,
) {
    val canDelete: Boolean get() = confirmed && !busy && !confirming && !checkingDeviceKey
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

    private data class Local(
        val confirmed: Boolean = false,
        val confirming: Boolean = false,
        val askingPassword: Boolean = false,
        val askingRemoval: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    /**
     * What the store last proved about the device key (a fact about the wallet: [clear] keeps it); null until the first
     * look answers (QA backlog B22). A page without a wallet has nothing to look at.
     */
    private val keyLost = MutableStateFlow<Boolean?>(if (walletId == null) false else null)
    private var keyLossCheck: Job? = null

    val uiState: StateFlow<DeleteWalletUiState> = combine(store.state, access.state, operation.state, local, keyLost, ::uiStateOf)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            uiStateOf(store.state.value, access.state.value, operation.state.value, local.value, keyLost.value),
        )

    private fun uiStateOf(state: SignerState, accessState: AppAccessState, op: WalletOperation.State, l: Local, lost: Boolean?) =
        DeleteWalletUiState(
            identity = walletId?.let { WalletIdentity.of(state, it) },
            confirmed = l.confirmed,
            confirming = l.confirming,
            askingPassword = l.askingPassword,
            busy = op.busy,
            error = op.error,
            asksForBiometrics = accessState.isEnabled && accessState.biometricsAvailable,
            gone = WalletIdentity.isGone(state, walletId),
            deviceKeyLost = lost == true,
            askingRemoval = l.askingRemoval && lost == true,
            checkingDeviceKey = lost == null,
        )

    val pageEvents: Flow<SecurityPageEvent> = events.receiveAsFlow()

    init {
        // Suspension (background, App Lock, capture) closes the password dialog unless the deletion is running; the
        // dialog's field leaves with it (`WalletSecuritySheets.swift:262`). The removal dialog closes the same way, and
        // the device key is looked at again: the person may have come back from setting a screen lock.
        viewModelScope.launch {
            store.state.map { it.securityEpoch }.distinctUntilChanged().drop(1).collect {
                if (!operation.busy) local.update { it.copy(askingPassword = false, askingRemoval = false) }
                checkDeviceKey()
            }
        }
        checkDeviceKey()
    }

    fun setConfirmed(confirmed: Boolean) = local.update { it.copy(confirmed = confirmed) }

    /**
     * "Delete Wallet" (`vaultManagement.submit`, `WalletSecuritySheets.swift:272-284`): biometrics first for someone who
     * turned App Lock on (dismissed → stop; unavailable or failed → the password still decides), then the password
     * dialog. "The password is what actually authorizes the deletion." With a provably lost device key ("Remove This
     * Wallet") the same biometric step leads to the "Remove This Wallet?" dialog instead: no password can help there.
     */
    fun confirm() {
        val current = local.value
        if (!current.confirmed || operation.busy || current.confirming || keyLost.value == null) return
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
                // Biometrics unavailable or failed: the password (or the removal dialog) still decides.
            }
            // A device-key answer that arrived during the prompt changed the page's statement and turned the switch off
            // (checkDeviceKey): nothing was confirmed for the page as it is now, so no dialog opens.
            val lost = keyLost.value == true
            local.update {
                if (it.confirmed) it.copy(confirming = false, askingPassword = !lost, askingRemoval = lost) else it.copy(confirming = false)
            }
        }
    }

    /** The dialog's "Cancel" (and Back): the dialog closes; its field is cleared by the page. */
    fun cancelPassword() = local.update { it.copy(askingPassword = false) }

    /**
     * The dialog's "Delete" (always enabled, `WalletSecuritySheets.swift:286-295`): an empty field shows "Enter this
     * wallet’s password."; otherwise the vault verifies the password before removing anything (a wrong password changes
     * nothing and its error shows on the page). Takes ownership of [password] and wipes it. A failure has the device
     * key looked at again, so a key found missing ("This wallet’s device key is unavailable…") offers the removal.
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
            } catch (failure: Throwable) {
                if (failure !is CancellationException) checkDeviceKey()
                throw failure
            } finally {
                password.wipe()
            }
            clear()
            events.send(SecurityPageEvent.Done)
        }
    }

    /** "Cancel" (and Back) of the "Remove This Wallet?" dialog. */
    fun cancelRemoval() = local.update { it.copy(askingRemoval = false) }

    /**
     * "Remove" in the "Remove This Wallet?" dialog (QA backlog B9): removes the wallet without a password. The store's
     * vault proves the loss again before anything is removed; a refusal shows its message and the page looks at the
     * device key again (falling back to the password path when the key may still exist). Only from the open dialog: a
     * dialog a suspension closed confirms nothing.
     */
    fun remove() {
        val id = walletId
        val asked = local.value.askingRemoval
        local.update { it.copy(askingRemoval = false) }
        if (id == null || !asked || !local.value.confirmed || keyLost.value != true || operation.busy) return
        operation.run {
            try {
                store.removeWalletWithLostDeviceKey(id)
            } catch (failure: Throwable) {
                if (failure !is CancellationException) checkDeviceKey()
                throw failure
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

    /**
     * Asks the store whether the device key is provably gone (false when unsure; the latest answer wins). When the answer
     * changes the page's statement, the switch is turned off again: it confirmed the other one. The first answer only
     * ends the loading state (nothing could be confirmed before it).
     */
    private fun checkDeviceKey() {
        val id = walletId ?: return
        keyLossCheck?.cancel()
        keyLossCheck = viewModelScope.launch {
            val lost = runCatchingCancellable { store.isDeviceKeyLost(id) }.getOrDefault(false)
            val previous = keyLost.value
            if (lost != previous) {
                if (previous != null) local.update { it.copy(confirmed = false, askingPassword = false, askingRemoval = false) }
                keyLost.value = lost
            }
        }
    }

    companion object {
        const val CONFIRM_REASON = "Confirm before deleting this wallet."
        const val ENTER_PASSWORD = "Enter this wallet’s password."
    }
}
