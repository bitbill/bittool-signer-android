package com.bittool.signer.feature.wallets.common

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PairingLevel
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.navigation.ConnectRoute

/**
 * The arguments of the Wallets destinations as Navigation stores them in the entry's [SavedStateHandle] (the
 * `@Serializable` property names of the `:core:navigation` routes). Read directly so ViewModels stay plain JVM classes;
 * an argument Navigation left out falls back to the route's own default. Ids only: nothing secret ever travels here.
 */
internal object WalletArgs {
    const val WALLET_ID = "walletId"
    const val LEVEL = "level"
    const val CHAIN = "chain"

    /** The wallet of any Wallets route; null for a malformed id (the screen then closes itself). */
    fun walletId(handle: SavedStateHandle): WalletId? = WalletId.parse(handle.get<String>(WALLET_ID))

    /** `ConnectRoute.pairingLevel` (default Wallet Level). */
    fun pairingLevel(handle: SavedStateHandle): PairingLevel =
        if (handle.get<String>(LEVEL) == ConnectRoute.LEVEL_COIN) PairingLevel.Coin else PairingLevel.Wallet

    /** `ConnectRoute.initialChain` / `AddressesRoute.initialChain` (default Bitcoin). */
    fun chain(handle: SavedStateHandle): Chain = Chain.fromWire(handle.get<String>(CHAIN)) ?: Chain.Bitcoin
}

/**
 * Who a page is about (iOS `WalletIdentityHeader` inputs and the display helpers of `AppStore`, wallets-pairing.md
 * §1.7): the displayed name, the upper-case fingerprint (null → "Setup required") and the avatar seed. Public data only.
 */
@Immutable
data class WalletIdentity(
    val id: WalletId,
    val name: String,
    val fingerprint: String?,
    val avatarIdentity: String,
    val createdAtMillis: Long,
    val ready: Boolean,
    val duplicate: Boolean,
) {
    companion object {
        /** The identity of [id] in [state], or null when the wallet is not (or no longer) in the list. */
        fun of(state: SignerState, id: WalletId): WalletIdentity? {
            val wallet = state.wallet(id) ?: return null
            return WalletIdentity(
                id = id,
                name = wallet.name,
                fingerprint = state.fingerprint(id),
                avatarIdentity = state.avatarIdentity(id),
                createdAtMillis = wallet.createdAtMillis,
                ready = state.metadataStatus(id) == PublicMetadataStatus.Ready,
                duplicate = state.isDuplicate(id),
            )
        }

        /**
         * Whether a page about [id] must close itself: the list has loaded and the wallet is gone (deleted, or a
         * restored back stack that outlived it). Never while the first load runs or after a failed load.
         */
        fun isGone(state: SignerState, id: WalletId?): Boolean =
            id == null || (!state.isLoading && state.walletLoadSucceeded && state.wallet(id) == null)
    }
}
