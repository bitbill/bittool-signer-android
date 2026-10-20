package com.bittool.signer.core.model

/**
 * A wallet known to work with BitTool Signer exports (models-state.md §10, iOS `Models/CompatibleWallets.swift`).
 * [name] and [platforms] are verbatim data. [asset] names the bundled 180 px logo (`wallet_*` drawable in `:core:ui`,
 * mapped with a `when`). Listing a wallet is not a certification.
 */
data class CompatibleWallet(val name: String, val asset: String, val platforms: String, val support: Support) {
    enum class Support {
        /** Imports the export by QR, sends unsigned requests by QR and takes the signature back. */
        Signing,
        /** Can only watch the account or address. */
        WatchOnly,
    }

    val id: String get() = name

    /** The wallet's own site, shown for copying only: the app never opens a browser. */
    val site: String get() = CompatibleWallets.sites[name] ?: ""
}

/** The catalogue and its selection functions (models-state.md §10.1–10.2). */
object CompatibleWallets {
    val sites: Map<String, String> = mapOf(
        "Sparrow" to "sparrowwallet.com",
        "BlueWallet" to "bluewallet.io",
        "Nunchuk" to "nunchuk.io",
        "Bitcoin Keeper" to "bitcoinkeeper.app",
        "BULL Bitcoin" to "bullbitcoin.com",
        "Specter" to "specter.solutions",
        "Bitcoin Safe" to "bitcoin-safe.org",
        "ZEUS" to "zeusln.com",
        "Electrum" to "electrum.org",
        "MetaMask" to "metamask.io",
        "Rabby" to "rabby.io",
        "imToken" to "token.im",
        "TokenPocket" to "tokenpocket.pro",
        "Ambire" to "ambire.com",
        "OKX Wallet" to "web3.okx.com",
        "Trust Wallet" to "trustwallet.com",
        "Solflare" to "solflare.com",
        "Helium Wallet" to "wallet.helium.com",
        "Phantom" to "phantom.com",
        "Backpack" to "backpack.exchange",
        "Jupiter" to "jup.ag",
        "TronLink" to "tronlink.org",
        "OneKey" to "onekey.so",
        "BitTool Wallet" to "bittool.com/wallet",
    )

    private fun signing(name: String, asset: String, platforms: String) =
        CompatibleWallet(name, asset, platforms, CompatibleWallet.Support.Signing)

    private fun watching(name: String, asset: String, platforms: String) =
        CompatibleWallet(name, asset, platforms, CompatibleWallet.Support.WatchOnly)

    /** Bitcoin `crypto-account` (account xpub), then `crypto-psbt`. */
    val bitcoinAccount: List<CompatibleWallet> = listOf(
        signing("Sparrow", "wallet_sparrow", "Desktop"),
        signing("BlueWallet", "wallet_bluewallet", "iOS · Android"),
        signing("Nunchuk", "wallet_nunchuk", "iOS · Android"),
        signing("Bitcoin Keeper", "wallet_keeper", "iOS · Android"),
        signing("BULL Bitcoin", "wallet_bull", "iOS · Android"),
        signing("Specter", "wallet_specter", "Desktop"),
        signing("Bitcoin Safe", "wallet_bitcoinsafe", "Desktop"),
    )

    /** ZEUS reads single-sig accounts through LND, which has no legacy addresses. */
    val zeus: CompatibleWallet = signing("ZEUS", "wallet_zeus", "iOS · Android")

    val bitcoinCosigner: List<CompatibleWallet> = listOf(
        signing("Sparrow", "wallet_sparrow", "Desktop"),
        signing("BlueWallet", "wallet_bluewallet", "iOS · Android"),
        signing("Nunchuk", "wallet_nunchuk", "iOS · Android"),
        signing("Bitcoin Keeper", "wallet_keeper", "iOS · Android"),
        signing("Specter", "wallet_specter", "Desktop"),
        signing("Bitcoin Safe", "wallet_bitcoinsafe", "Desktop"),
    )

    val bitcoinAddress: List<CompatibleWallet> = listOf(
        watching("BlueWallet", "wallet_bluewallet", "iOS · Android"),
        watching("Electrum", "wallet_electrum", "Desktop · Android"),
    )

    /** EVM `crypto-hdkey` (EIP-4527), then `eth-sign-request` / `eth-signature`. */
    val evmAccount: List<CompatibleWallet> = listOf(
        signing("MetaMask", "wallet_metamask", "Extension · iOS · Android"),
        signing("Rabby", "wallet_rabby", "Extension · iOS · Android"),
        signing("imToken", "wallet_imtoken", "iOS · Android"),
        signing("TokenPocket", "wallet_tokenpocket", "iOS · Android"),
        signing("Ambire", "wallet_ambire", "Extension"),
    )

    val evmAddress: List<CompatibleWallet> = listOf(
        watching("Rabby", "wallet_rabby", "Extension · iOS · Android"),
        watching("TokenPocket", "wallet_tokenpocket", "iOS · Android"),
        watching("OKX Wallet", "wallet_okx", "iOS · Android · Extension"),
        watching("Trust Wallet", "wallet_trust", "iOS · Android"),
    )

    /** Solana `crypto-multi-accounts`, then `sol-sign-request` / `sol-signature`. */
    val solanaAccount: List<CompatibleWallet> = listOf(
        signing("Solflare", "wallet_solflare", "Extension · iOS · Android"),
        signing("Helium Wallet", "wallet_helium", "iOS · Android"),
    )

    val solanaAddress: List<CompatibleWallet> = listOf(
        watching("Phantom", "wallet_phantom", "Extension · iOS · Android"),
        watching("Backpack", "wallet_backpack", "Extension"),
        watching("Jupiter", "wallet_jupiter", "Extension · iOS · Android"),
        watching("OKX Wallet", "wallet_okx", "iOS · Android · Extension"),
        watching("TokenPocket", "wallet_tokenpocket", "iOS · Android"),
    )

    /** TRON: address only (nobody sends `tron-sign-request` to a QR signer yet). */
    val tronAddress: List<CompatibleWallet> = listOf(
        watching("TokenPocket", "wallet_tokenpocket", "iOS · Android · Extension"),
        watching("TronLink", "wallet_tronlink", "iOS · Android"),
        watching("OKX Wallet", "wallet_okx", "iOS · Android"),
        watching("OneKey", "wallet_onekey", "iOS · Android"),
    )

    /** Wallet level (`bittool-signer-wallet` v1). Product question: "iOS" vs "iOS · Android" (models-state.md §10.3). */
    val walletLevel: List<CompatibleWallet> = listOf(signing("BitTool Wallet", "wallet_bittool", "iOS"))

    /** Every bundled logo asset name (each must have a drawable in `:core:ui`). */
    val allAssets: Set<String>
        get() = (walletLevel + bitcoinAccount + zeus + bitcoinCosigner + bitcoinAddress + evmAccount + evmAddress +
            solanaAccount + solanaAddress + tronAddress).map { it.asset }.toSet()

    /** Wallets for one Coin Level export on the Connect page. */
    fun wallets(chain: Chain, profile: String, mode: String): List<CompatibleWallet> = when (chain) {
        Chain.Bitcoin -> when {
            profile.startsWith("multisig-") -> bitcoinCosigner
            mode == "address" -> bitcoinAddress
            profile == "legacy" -> bitcoinAccount
            else -> bitcoinAccount + zeus
        }
        Chain.Evm -> if (mode == "address") evmAddress else evmAccount
        Chain.Solana -> if (mode == "address") solanaAddress else solanaAccount
        Chain.Tron -> tronAddress
    }

    /**
     * One alphabetical directory of every listed wallet (Settings › Compatibility › Wallets): per name the first
     * signing record wins, otherwise the last watch-only record; case-insensitive order (the names are ASCII).
     */
    val directory: List<CompatibleWallet>
        get() {
            val lists = walletLevel + bitcoinAccount + zeus + bitcoinCosigner + bitcoinAddress + evmAccount + evmAddress +
                solanaAccount + solanaAddress + tronAddress
            val best = LinkedHashMap<String, CompatibleWallet>()
            for (wallet in lists) {
                val existing = best[wallet.name]
                if (existing != null && existing.support == CompatibleWallet.Support.Signing) continue
                best[wallet.name] = wallet
            }
            return best.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }

    /** Every wallet for [chain], de-duplicated by name (first occurrence wins), signing lists first. */
    fun all(chain: Chain): List<CompatibleWallet> {
        val lists = when (chain) {
            Chain.Bitcoin -> bitcoinAccount + zeus + bitcoinCosigner + bitcoinAddress
            Chain.Evm -> evmAccount + evmAddress
            Chain.Solana -> solanaAccount + solanaAddress
            Chain.Tron -> tronAddress
        }
        val seen = HashSet<String>()
        return lists.filter { seen.add(it.name) }
    }
}
