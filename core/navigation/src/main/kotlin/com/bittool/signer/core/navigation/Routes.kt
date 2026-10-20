package com.bittool.signer.core.navigation

import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.PairingLevel
import com.bittool.signer.core.model.WalletId
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

/*
 * Every destination of the single NavHost in `:app` (ARCHITECTURE.md §2). Rules (DECISIONS.md §7, ownbit-ui-patterns.md
 * §6.1): `@Serializable` types with primitive arguments only — ids and wire values, never secrets or large payloads
 * (a request, a plan or a phrase is read from `SignerStore`). Each destination must handle an id that no longer exists
 * (pop itself), because restored back stacks may outlive the wallet or receipt.
 */

/** Marker of every route type. */
sealed interface SignerRoute

// ---------------------------------------------------------------------------------------------------------------
// Tab graphs (nested `navigation<…Graph>` blocks; start destinations below).

@Serializable data object WalletsGraph : SignerRoute
@Serializable data object ScanGraph : SignerRoute
@Serializable data object ActivityGraph : SignerRoute
@Serializable data object SettingsGraph : SignerRoute

// ---------------------------------------------------------------------------------------------------------------
// Wallets tab (`:feature:wallets`, plus RecoveryPhraseRoute from `:feature:setup`).

/** Routes `AppRouter.open` can put on the Wallets stack (iOS `WalletRoute`). */
sealed interface WalletRoute : SignerRoute

/** Wallets tab root: the wallet cards (iOS `WalletsTab`). */
@Serializable data object WalletsRoute : SignerRoute

/** Wallet page (iOS `WalletRoute.detail`, `WalletDetailView`). */
@Serializable
data class WalletDetailRoute(val walletId: String) : WalletRoute {
    constructor(id: WalletId) : this(id.toString())
}

/**
 * Pair page (iOS `ConnectView`): [level] `wallet` = `WalletRoute.connectWallet`, `coin` = `WalletRoute.connect`
 * with [chain] (a `Chain.wire` value, default bitcoin).
 */
@Serializable
data class ConnectRoute(val walletId: String, val level: String = LEVEL_WALLET, val chain: String? = null) : WalletRoute {
    val pairingLevel: PairingLevel get() = if (level == LEVEL_COIN) PairingLevel.Coin else PairingLevel.Wallet
    val initialChain: Chain get() = Chain.fromWire(chain) ?: Chain.Bitcoin

    companion object {
        const val LEVEL_WALLET = "wallet"
        const val LEVEL_COIN = "coin"

        /** Wallet Level ("every chain in one code"): wallet card "Pair", detail "Wallet Level", setup "Pair a Wallet". */
        fun walletLevel(id: WalletId) = ConnectRoute(id.toString(), LEVEL_WALLET, null)

        /** Coin Level for [chain]: the detail page's chain rows. */
        fun coinLevel(id: WalletId, chain: Chain) = ConnectRoute(id.toString(), LEVEL_COIN, chain.wire)
    }
}

/** Receive addresses (iOS `WalletRoute.addresses`; every caller passes bitcoin). */
@Serializable
data class AddressesRoute(val walletId: String, val chain: String = Chain.Bitcoin.wire) : WalletRoute {
    constructor(id: WalletId, chain: Chain = Chain.Bitcoin) : this(id.toString(), chain.wire)
    val initialChain: Chain get() = Chain.fromWire(chain) ?: Chain.Bitcoin
}

/** Change Password page (iOS sheet; modal: no bottom bar). */
@Serializable data class ChangePasswordRoute(val walletId: String) : SignerRoute

/** Delete Wallet page (iOS sheet; modal). The password prompt is an `AlertDialog` on it. */
@Serializable data class DeleteWalletRoute(val walletId: String) : SignerRoute

/** Repair Wallet page (iOS sheet; modal). */
@Serializable data class RepairWalletRoute(val walletId: String) : SignerRoute

/** Saved wallet's "Recovery Phrase" page (iOS sheet; `:feature:setup` owns it; modal, FLAG_SECURE). */
@Serializable data class RecoveryPhraseRoute(val walletId: String) : SignerRoute

// ---------------------------------------------------------------------------------------------------------------
// Scan tab (`:feature:sign`).

/** Scan tab root: Home, Review or Signature result, chosen from `SignerState` (iOS `ScanTab`). */
@Serializable data object ScanRoute : SignerRoute

/** Full-screen camera for a signing request (iOS `fullScreenCover`; modal). Owns the scan session. */
@Serializable data object ScannerRoute : SignerRoute

// ---------------------------------------------------------------------------------------------------------------
// Activity tab (`:feature:activity`).

@Serializable data object ActivityRoute : SignerRoute

/** One receipt (iOS `ActivityDetailView`); [receiptId] is `SigningReceipt.id`. */
@Serializable data class ActivityDetailRoute(val receiptId: String) : SignerRoute

/** "All Decoded Fields" of a receipt (iOS "Signed Request"); reuses the detail's decoded review (no second engine call). */
@Serializable data class SignedRequestRoute(val receiptId: String) : SignerRoute

// ---------------------------------------------------------------------------------------------------------------
// Settings tab (`:feature:settings`).

@Serializable data object SettingsRoute : SignerRoute

/** Interface language list (iOS picker in the Form; Android: a full page of radio rows). */
@Serializable data object LanguageRoute : SignerRoute

/** Networks, Tokens & Contracts. */
@Serializable data object NetworksRoute : SignerRoute

/** One registry network's tokens/contracts; [chain] is the grouping key (`evm:1`, `tron:`, or `evm:*` for every EVM chain). */
@Serializable data class NetworkReferenceRoute(val chain: String) : SignerRoute

@Serializable data object CompatibilityRoute : SignerRoute
@Serializable data object ContactRoute : SignerRoute
@Serializable data object OpenSourceRoute : SignerRoute
@Serializable data object ThirdPartyNoticesRoute : SignerRoute

/** Terms of Use / Privacy Policy page; [document] is `LegalDocument.name` (`Terms`, `Privacy`). */
@Serializable
data class LegalDocumentRoute(val document: String) : SignerRoute {
    constructor(document: LegalDocument) : this(document.name)
    val legalDocument: LegalDocument get() = LegalDocument.entries.firstOrNull { it.name == document } ?: LegalDocument.Terms
}

// ---------------------------------------------------------------------------------------------------------------
// Root-level shared destinations (registered outside the tab graphs; they keep the current tab selected).

/** "Submit a Wallet" (`:feature:settings` registers it; reached from Connect and from Compatibility). */
@Serializable data object SubmitWalletRoute : SignerRoute

/**
 * Destinations drawn without the bottom navigation bar (iOS sheets and full-screen covers presented over the tab bar).
 * `:app` hides the bar when the current destination's route class is in this set.
 */
val ModalRouteClasses: Set<KClass<out SignerRoute>> = setOf(
    ScannerRoute::class,
    ChangePasswordRoute::class,
    DeleteWalletRoute::class,
    RepairWalletRoute::class,
    RecoveryPhraseRoute::class,
)
