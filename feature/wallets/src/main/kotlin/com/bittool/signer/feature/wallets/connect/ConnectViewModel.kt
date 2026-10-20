package com.bittool.signer.feature.wallets.connect

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.AccountProfiles
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.ConnectOptions
import com.bittool.signer.core.model.PairingLevel
import com.bittool.signer.core.model.QrMatrix
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.pairing.PairingPayload
import com.bittool.signer.core.model.pairing.WalletPairingPayload
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.feature.wallets.common.WalletArgs
import com.bittool.signer.feature.wallets.common.WalletIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
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
 * The Pair page (iOS `ConnectView` + `WalletLevelPairingView`, wallets-pairing.md §7). Public data only: no password,
 * nothing persisted, every chain keeps its options for the life of the page.
 */
@Immutable
data class ConnectUiState(
    val level: PairingLevel = PairingLevel.Wallet,
    val options: ConnectOptions = ConnectOptions(),
    /** The Coin Level export of the current request key only (a stale payload is never shown). */
    val coin: PairingPayload? = null,
    /** False for the first Coin Level result (it may land during the push transition): shown without the fade. */
    val animateCoin: Boolean = true,
    val wallet: WalletPairingPayload? = null,
    /** Matrices prepared by the Connect prefetch for exactly [wallet]'s frames, or null. */
    val walletMatrices: List<QrMatrix>? = null,
    /** One error shared by both levels (iOS `error`), cleared whenever a level (re)loads. */
    val error: UiText? = null,
    /** Upper-case master fingerprint (`73C5DA0A`), or null without public data. */
    val masterFingerprint: String? = null,
    val gone: Boolean = false,
) {
    /** What "Copy QR Data" copies: exactly the text of the QR on screen; null while nothing is shown (disabled). */
    val copyText: String?
        get() = when (level) {
            PairingLevel.Coin -> coin?.text
            PairingLevel.Wallet -> wallet?.text
        }
}

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val store: SignerStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val walletId: WalletId? = WalletArgs.walletId(savedStateHandle)

    /** iOS `@State` of `ConnectView.swift:83-114`. */
    private data class Model(
        val level: PairingLevel,
        val options: ConnectOptions,
        val payload: PairingPayload? = null,
        val loadedKey: String? = null,
        val animateCoin: Boolean = true,
        val walletPayload: WalletPairingPayload? = null,
        val walletLoaded: Boolean = false,
        val preparedMatrices: List<QrMatrix>? = null,
        val error: UiText? = null,
    ) {
        /** The load key: the request key on Coin Level, `wallet` on Wallet Level (iOS `.task(id:)`). */
        val loadKey: String get() = if (level == PairingLevel.Coin) options.requestKey else WALLET_KEY
    }

    private val model: MutableStateFlow<Model>

    init {
        // The prepared wallet-level QR (prefetch) is read once, at navigation time: the Wallet Level page is complete
        // on its first frame and nothing changes during the push.
        val prepared = walletId?.let(store::preparedConnect)
        model = MutableStateFlow(
            Model(
                level = WalletArgs.pairingLevel(savedStateHandle),
                options = ConnectOptions(chain = WalletArgs.chain(savedStateHandle)),
                walletPayload = prepared?.payload,
                walletLoaded = prepared != null,
                preparedMatrices = prepared?.matrices,
            ),
        )
        viewModelScope.launch {
            // Any key change cancels the previous load and starts the next one at once (no debounce); stale results
            // are dropped by key comparison (wallets-pairing §7.6).
            model.map { it.loadKey }.distinctUntilChanged().collectLatest { key ->
                if (key == WALLET_KEY) loadWallet() else loadCoin(key)
            }
        }
    }

    // The initial value is the full current state, so a prefetched Wallet Level code is on the page's first frame.
    val uiState: StateFlow<ConnectUiState> = combine(model, store.state, ::uiStateOf)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), uiStateOf(model.value, store.state.value))

    private fun uiStateOf(m: Model, state: SignerState): ConnectUiState {
        val id = walletId
        return ConnectUiState(
            level = m.level,
            options = m.options,
            coin = m.payload?.takeIf { m.loadedKey == m.options.requestKey },
            animateCoin = m.animateCoin,
            wallet = m.walletPayload?.takeIf { m.walletLoaded },
            walletMatrices = m.preparedMatrices?.takeIf { m.walletPayload != null && it.size == m.walletPayload.frames.size },
            error = m.error,
            masterFingerprint = id?.let { state.publicMetadata(it)?.displayFingerprint },
            gone = WalletIdentity.isGone(state, id),
        )
    }

    fun selectLevel(level: PairingLevel) = model.update { it.copy(level = level) }

    fun selectChain(chain: Chain) = updateOptions { it.copy(chain = chain) }

    fun selectMultisig(multisig: Boolean) = updateOptions { it.copy(multisig = multisig) }

    /** "Address type": the single-sig or the multisig preset, whichever list is shown. */
    fun selectBitcoinProfile(profile: String) = updateOptions { if (it.multisig) it.copy(btcMulti = profile) else it.copy(btcSingle = profile) }

    fun selectSolanaProfile(profile: String) = updateOptions { it.copy(solanaProfile = profile) }

    /** "Export as" of the current chain (Bitcoin single-sig, EVM, Solana; Bitcoin multisig and TRON have none). */
    fun selectOutputMode(mode: String) = updateOptions {
        when (it.chain) {
            Chain.Bitcoin -> if (it.multisig) it else it.copy(btcMode = mode)
            Chain.Evm -> it.copy(evmMode = mode)
            Chain.Solana -> it.copy(solanaMode = mode)
            Chain.Tron -> it
        }
    }

    /** The TRON receive index (stepper or field); every applied change is a new pairing request. */
    fun selectTronIndex(index: Long) = updateOptions { it.copy(tronIndex = index.coerceIn(0, AccountProfiles.MAX_TRON_INDEX)) }

    private fun updateOptions(transform: (ConnectOptions) -> ConnectOptions) = model.update { it.copy(options = transform(it.options)) }

    /** Coin Level load (`ConnectView.swift:316-336`). */
    private suspend fun loadCoin(key: String) {
        val id = walletId ?: return
        val start = model.value
        if (start.loadedKey == key && start.payload != null) return
        val first = start.loadedKey == null
        val options = start.options
        model.update { it.copy(error = null) }
        try {
            val payload = store.pairing(id, options.chain, options.profile, options.outputMode, options.addressIndex)
            if (model.value.loadKey != key) return
            model.update { it.copy(payload = payload, loadedKey = key, animateCoin = !first) }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val text = failure.toUiText() ?: return
            if (model.value.loadKey == key) model.update { it.copy(error = text) }
        }
    }

    /** Wallet Level load (`ConnectView.swift:338-347`): always asked again, even with a prefetched payload. */
    private suspend fun loadWallet() {
        val id = walletId ?: return
        model.update { it.copy(error = null) }
        try {
            val payload = store.walletPairing(id)
            if (model.value.loadKey != WALLET_KEY) return
            model.update { m ->
                // The engine output is deterministic: the prepared matrices stay valid for identical frames.
                val keepMatrices = m.walletPayload?.frames == payload.frames
                m.copy(walletPayload = payload, walletLoaded = true, preparedMatrices = m.preparedMatrices.takeIf { keepMatrices })
            }
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            val text = failure.toUiText() ?: return
            if (model.value.loadKey == WALLET_KEY) model.update { it.copy(error = text) }
        }
    }

    companion object {
        /** iOS `walletKeyValue`. */
        const val WALLET_KEY = "wallet"
    }
}
