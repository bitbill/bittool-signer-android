package com.bittool.signer.feature.wallets.list

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerPreferences
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.navigation.Tab
import com.bittool.signer.feature.wallets.common.WalletIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The Wallets tab (iOS `WalletsTab`, wallets-pairing.md §4): one card per wallet in vault order. */
@Immutable
data class WalletsUiState(
    val wallets: List<WalletIdentity> = emptyList(),
    /** The list has loaded at least once (an empty list before that is not "no wallets"). */
    val loaded: Boolean = false,
)

@HiltViewModel
class WalletsViewModel @Inject constructor(
    private val store: SignerStore,
    private val preferences: SignerPreferences,
    private val router: AppRouter,
) : ViewModel() {

    val uiState: StateFlow<WalletsUiState> = store.state
        .map(::uiStateOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), uiStateOf(store.state.value))

    /** The person tapped Wallets while it was already shown: the list scrolls back to the top. */
    val reselections: Flow<Tab> = router.reselections.filter { it == Tab.Wallets }

    /** Toolbar "Scan" (`wallets.scan`): Scan tab, and the camera opens once (iOS `router.startScanning()`). */
    fun scan() = router.startScanning()

    /**
     * "Create New Wallet" / "Import Recovery Phrase" (`WalletsTab.swift:62-64`): the setup flow, with the introduction only
     * for the first wallet until it was completed once.
     */
    fun addWallet(intent: SetupIntent) {
        val introductionCompleted = preferences.values.value?.introductionCompleted ?: false
        router.startSetup(intent, firstWallet = store.state.value.wallets.isEmpty(), introductionCompleted = introductionCompleted)
    }

    private fun uiStateOf(state: SignerState) = WalletsUiState(
        wallets = state.wallets.mapNotNull { WalletIdentity.of(state, it.id) },
        loaded = !state.isLoading,
    )
}
