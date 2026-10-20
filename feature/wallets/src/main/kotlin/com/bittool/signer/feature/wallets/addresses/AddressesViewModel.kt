package com.bittool.signer.feature.wallets.addresses

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.AccountProfiles
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicAccount
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.feature.wallets.common.WalletArgs
import com.bittool.signer.feature.wallets.common.WalletIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Receive addresses (iOS `AddressesView`, wallets-pairing.md §6). "Receive addresses derived locally from cached public
 * keys. No balances, history or usage are looked up, and browsing never selects a signer." Nothing is remembered
 * between visits.
 */
@Immutable
data class AddressesUiState(
    val chain: Chain = Chain.Bitcoin,
    /** The Bitcoin single-sig preset (`native` on every visit). */
    val btcProfile: String = AccountProfiles.DEFAULT_CONNECT_PROFILE,
    /** Rows of the current [listKey] only (old rows are hidden while a new key loads, never cross-faded). */
    val accounts: List<PublicAccount> = emptyList(),
    val loaded: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiText? = null,
    /** The address sheet, when open. */
    val selected: PublicAccount? = null,
    val gone: Boolean = false,
) {
    /** iOS `listKey`: `chain|btcProfile`. */
    val listKey: String get() = listKey(chain, btcProfile)

    companion object {
        fun listKey(chain: Chain, btcProfile: String) = chain.wire + "|" + btcProfile
    }
}

@HiltViewModel
class AddressesViewModel @Inject constructor(
    private val store: SignerStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val walletId: WalletId? = WalletArgs.walletId(savedStateHandle)

    /** The paging state of `AddressesView.swift:9-17`; [loadedKey] decides what the list shows. */
    private data class Paging(
        val chain: Chain,
        val btcProfile: String = AccountProfiles.DEFAULT_CONNECT_PROFILE,
        val accounts: List<PublicAccount> = emptyList(),
        val loadedKey: String? = null,
        val loadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val error: UiText? = null,
        val selected: PublicAccount? = null,
    ) {
        val listKey: String get() = AddressesUiState.listKey(chain, btcProfile)
    }

    private val paging = MutableStateFlow(Paging(chain = WalletArgs.chain(savedStateHandle)))
    private var loadMoreJob: Job? = null

    val uiState: StateFlow<AddressesUiState> = combine(paging, store.state) { p, state -> uiStateOf(p, WalletIdentity.isGone(state, walletId)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), uiStateOf(paging.value, WalletIdentity.isGone(store.state.value, walletId)))

    init {
        // iOS `.task(id: listKey)`: every key change cancels the previous load and reloads from index 0.
        viewModelScope.launch {
            paging.map { it.listKey }.distinctUntilChanged().collectLatest { key -> reload(key) }
        }
    }

    fun selectChain(chain: Chain) = paging.update { it.copy(chain = chain) }

    fun selectBitcoinProfile(profile: String) = paging.update { it.copy(btcProfile = profile) }

    fun showAddress(account: PublicAccount) = paging.update { it.copy(selected = account) }

    fun dismissAddress() = paging.update { it.copy(selected = null) }

    /**
     * The last row appeared (`AddressesView.swift:122-140`): the next page, guarded exactly as iOS — more exists, not
     * already loading, the rows are the current key's and the chain pages (Solana has three fixed rows).
     */
    fun loadMore() {
        val id = walletId ?: return
        val p = paging.value
        if (!p.hasMore || p.loadingMore || p.loadedKey != p.listKey || p.chain == Chain.Solana) return
        val key = p.listKey
        val start = p.accounts.size.toLong()
        val limit = limit(p.chain)
        if (start >= limit) {
            paging.update { it.copy(hasMore = false) }
            return
        }
        paging.update { it.copy(loadingMore = true) }
        loadMoreJob = viewModelScope.launch {
            try {
                val page = fetch(id, p.chain, p.btcProfile, start)
                val now = paging.value
                if (now.listKey != key || now.accounts.size.toLong() != start) return@launch
                val accounts = now.accounts + page
                paging.update {
                    it.copy(accounts = accounts, hasMore = page.size == PAGE_SIZE && accounts.size.toLong() < limit)
                }
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                // A failed next page ends paging silently (no message), as on iOS.
                if (paging.value.listKey == key) paging.update { it.copy(hasMore = false) }
            } finally {
                paging.update { it.copy(loadingMore = false) }
            }
        }
    }

    private suspend fun reload(key: String) {
        val id = walletId ?: return
        loadMoreJob?.cancel()
        paging.update { it.copy(error = null, hasMore = true, loadingMore = false) }
        val p = paging.value
        try {
            val page = fetch(id, p.chain, p.btcProfile, 0)
            if (paging.value.listKey != key) return
            paging.update {
                it.copy(
                    accounts = page,
                    loadedKey = key,
                    hasMore = page.size == PAGE_SIZE && page.size.toLong() < limit(p.chain),
                )
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val text = failure.toUiText() ?: return
            if (paging.value.listKey == key) paging.update { it.copy(error = text) }
        }
    }

    /** iOS `fetch(start:)`: Solana's three presets in one call; 20 per page elsewhere, never beyond the 2³¹ limit. */
    private suspend fun fetch(id: WalletId, chain: Chain, btcProfile: String, start: Long): List<PublicAccount> {
        val count = if (chain == Chain.Solana) SOLANA_COUNT else minOf(PAGE_SIZE.toLong(), limit(chain) - start).toInt()
        val profile = when (chain) {
            Chain.Bitcoin -> btcProfile
            Chain.Solana -> ""
            Chain.Evm, Chain.Tron -> "standard"
        }
        return store.addresses(id, chain, profile, start, count)
    }

    private fun uiStateOf(p: Paging, gone: Boolean) = AddressesUiState(
        chain = p.chain,
        btcProfile = p.btcProfile,
        accounts = if (p.loadedKey == p.listKey) p.accounts else emptyList(),
        loaded = p.loadedKey == p.listKey,
        loadingMore = p.loadingMore && p.loadedKey == p.listKey,
        error = if (p.loadedKey == p.listKey) null else p.error,
        selected = p.selected,
        gone = gone,
    )

    companion object {
        const val PAGE_SIZE = AccountProfiles.ADDRESS_PAGE_SIZE
        private const val SOLANA_COUNT = 3

        /** iOS `limit`: 3 for Solana, 2³¹ (a Long: it overflows Int) for every paging chain. */
        fun limit(chain: Chain): Long = if (chain == Chain.Solana) SOLANA_COUNT.toLong() else AccountProfiles.ADDRESS_LIMIT
    }
}
