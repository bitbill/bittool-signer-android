package com.bittool.signer.feature.wallets.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.common.coroutines.runCatchingCancellable
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.feature.wallets.common.WalletArgs
import com.bittool.signer.feature.wallets.common.WalletIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The wallet page (iOS `WalletDetailView`, wallets-pairing.md §5). */
@Immutable
data class WalletDetailUiState(
    val walletId: WalletId? = null,
    val identity: WalletIdentity? = null,
    /** The wallet left the list (deleted, or a restored back stack outlived it): the page closes itself. */
    val gone: Boolean = false,
    /** The "Rename Wallet" dialog is up. */
    val renaming: Boolean = false,
    /** "Unable to Rename" with this message. */
    val renameError: UiText? = null,
    /** A "Password Changed" toast is due (set by the Change Password page when it closes after a success). */
    val passwordChanged: Boolean = false,
)

@HiltViewModel
class WalletDetailViewModel @Inject constructor(
    private val store: SignerStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val walletId: WalletId? = WalletArgs.walletId(savedStateHandle)

    private data class Local(val renaming: Boolean = false, val renameError: UiText? = null)

    private val local = MutableStateFlow(Local())

    val uiState: StateFlow<WalletDetailUiState> = combine(
        store.state,
        local,
        savedStateHandle.getStateFlow(PASSWORD_CHANGED_KEY, false),
    ) { state, local, changed -> uiStateOf(state, local, changed) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            uiStateOf(store.state.value, local.value, savedStateHandle[PASSWORD_CHANGED_KEY] ?: false),
        )

    /** The upper-case master fingerprint to copy (the header button), or null without public data (button disabled). */
    fun fingerprintToCopy(): String? = walletId?.let { store.state.value.publicMetadata(it)?.displayFingerprint }

    /** "Rename Wallet" (`detail.rename`): opens the dialog, pre-filled with the current name by the screen. */
    fun startRename() = local.update { it.copy(renaming = true) }

    fun cancelRename() = local.update { it.copy(renaming = false) }

    /**
     * "Save" (`WalletDetailView.swift:162-168`): the store trims and validates the name and changes only the public
     * sidecar's display name; no password is asked. A failure shows "Unable to Rename" with the message.
     */
    fun rename(name: String) {
        val id = walletId ?: return
        local.update { it.copy(renaming = false) }
        viewModelScope.launch {
            runCatchingCancellable { store.renameWallet(id, name) }.onFailure { failure ->
                val text = failure.toUiText() ?: return@onFailure
                local.update { it.copy(renameError = text) }
            }
        }
    }

    fun dismissRenameError() = local.update { it.copy(renameError = null) }

    /** The "Password Changed" toast was shown. */
    fun passwordChangedShown() {
        savedStateHandle[PASSWORD_CHANGED_KEY] = false
    }

    private fun uiStateOf(state: SignerState, local: Local, changed: Boolean): WalletDetailUiState {
        val identity = walletId?.let { WalletIdentity.of(state, it) }
        return WalletDetailUiState(
            walletId = walletId,
            identity = identity,
            gone = WalletIdentity.isGone(state, walletId),
            renaming = local.renaming && identity != null,
            renameError = local.renameError,
            passwordChanged = changed,
        )
    }

    companion object {
        /**
         * The wallet page's entry flag the Change Password page sets on its way out after a success (iOS
         * `ChangePasswordSheet(onChanged:)` → `passwordChanged = true`). A Boolean: nothing secret.
         */
        const val PASSWORD_CHANGED_KEY = "wallets.passwordChanged"
    }
}
