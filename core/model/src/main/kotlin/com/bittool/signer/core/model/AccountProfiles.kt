package com.bittool.signer.core.model

/**
 * A pairing / address account preset (models-state.md §1.4, iOS `ConnectView.swift:3-22`). [id] is the engine
 * `profile` wire value; [title] and [detail] are verbatim English in every language (no translation entries).
 */
data class AccountProfile(val id: String, val title: String, val detail: String)

/** The preset lists and Connect defaults (models-state.md §1.4–1.5, wallets-pairing.md §1.3, §7.1). */
object AccountProfiles {
    /** Bitcoin single-sig address types, in picker order. */
    val bitcoinSingle: List<AccountProfile> = listOf(
        AccountProfile("native", "Native SegWit", "BIP84 · bc1q…"),
        AccountProfile("taproot", "Taproot", "BIP86 · bc1p…"),
        AccountProfile("nested", "Nested SegWit", "BIP49 · 3…"),
        AccountProfile("legacy", "Legacy", "BIP44 · 1…"),
    )

    /** Bitcoin multisig cosigner exports (no receive address of their own). */
    val bitcoinMulti: List<AccountProfile> = listOf(
        AccountProfile("multisig-native", "Native SegWit", "BIP48 · P2WSH"),
        AccountProfile("multisig-nested", "Nested SegWit", "BIP48 · P2SH-P2WSH"),
    )

    /** Solana derivation presets; [AccountProfile.detail] is the path. */
    val solana: List<AccountProfile> = listOf(
        AccountProfile("standard", "Standard", "m/44'/501'/0'/0'"),
        AccountProfile("trust", "Trust", "m/44'/501'/0'"),
        AccountProfile("legacy", "Legacy", "m/44'/501'"),
    )

    /** Registered account origins (engine `public-index.js:10-14`). */
    val origins: Map<String, String> = mapOf(
        "native" to "m/84'/0'/0'",
        "taproot" to "m/86'/0'/0'",
        "nested" to "m/49'/0'/0'",
        "legacy" to "m/44'/0'/0'",
        "multisig-native" to "m/48'/0'/0'/2'",
        "multisig-nested" to "m/48'/0'/0'/1'",
    )
    const val EVM_ORIGIN = "m/44'/60'/0'"
    const val TRON_ORIGIN = "m/44'/195'/0'"
    const val TRON_PATH_PREFIX = "m/44'/195'/0'/0/"

    /** iOS `AppStore.defaultConnectProfile` / `defaultConnectMode`. */
    const val DEFAULT_CONNECT_PROFILE = "native"
    const val DEFAULT_CONNECT_MODE = "accountXpub"

    /** Largest exportable TRON receive index (`AddressIndexControl.maximum`, 2³¹ − 1). */
    const val MAX_TRON_INDEX: Long = 2_147_483_647L

    /** Receive-address paging limit for non-Solana chains (2³¹; Long, it overflows Int). */
    const val ADDRESS_LIMIT: Long = 2_147_483_648L
    const val ADDRESS_PAGE_SIZE = 20

    /** The Solana preset whose path is [path], for Addresses rows (`#index` label is replaced by the title). */
    fun solanaPreset(path: String): AccountProfile? = solana.firstOrNull { it.detail == path }

    /** The single-sig or multisig Bitcoin preset with [id], else null. */
    fun bitcoin(id: String): AccountProfile? = (bitcoinSingle + bitcoinMulti).firstOrNull { it.id == id }
}

/** Connect page levels (iOS `PairingLevel`, `ConnectView.swift:350`). Wallet Level is listed first. */
enum class PairingLevel { Wallet, Coin }

/** One "Export as" segment: engine `outputMode` wire value and its verbatim English title. */
data class ExportModeChoice(val wire: String, val title: String)

/**
 * The Coin Level selection of the Connect page (wallets-pairing.md §7.1): every chain keeps its own options for the
 * life of the screen. Derived values decide the exact `createPublicPairing` parameters (§2.2).
 */
data class ConnectOptions(
    val chain: Chain = Chain.Bitcoin,
    val multisig: Boolean = false,
    val btcSingle: String = AccountProfiles.DEFAULT_CONNECT_PROFILE,
    val btcMulti: String = "multisig-native",
    val btcMode: String = AccountProfiles.DEFAULT_CONNECT_MODE,
    val evmMode: String = "wallet",
    val solanaProfile: String = "standard",
    val solanaMode: String = "wallet",
    val tronIndex: Long = 0,
) {
    /** Engine `profile`: Bitcoin single/multi preset, the Solana preset, else `standard`. */
    val profile: String
        get() = when (chain) {
            Chain.Bitcoin -> if (multisig) btcMulti else btcSingle
            Chain.Solana -> solanaProfile
            Chain.Evm, Chain.Tron -> "standard"
        }

    /** Engine `outputMode`: multisig is always `accountXpub`, TRON always `address`. */
    val outputMode: String
        get() = when (chain) {
            Chain.Bitcoin -> if (multisig) "accountXpub" else btcMode
            Chain.Evm -> evmMode
            Chain.Solana -> solanaMode
            Chain.Tron -> "address"
        }

    /** Engine `index`: only TRON exports a chosen index; every other chain sends 0. */
    val addressIndex: Long get() = if (chain == Chain.Tron) tronIndex else 0

    /** iOS `requestKey` (`chain|profile|outputMode|index`): a payload is shown only for the current key. */
    val requestKey: String get() = "${chain.wire}|$profile|$outputMode|$addressIndex"

    /** The "Export as" segments for the current chain; empty for Bitcoin multisig and TRON (no picker). */
    val exportModes: List<ExportModeChoice>
        get() = when {
            chain == Chain.Bitcoin && !multisig -> listOf(ExportModeChoice("accountXpub", "Account XPUB"), ExportModeChoice("address", "Address"))
            chain == Chain.Evm || chain == Chain.Solana -> listOf(ExportModeChoice("wallet", "Wallet"), ExportModeChoice("address", "Address"))
            else -> emptyList()
        }

    /** The TRON receive path for [tronIndex] (`m/44'/195'/0'/0/<i>`). */
    val tronPath: String get() = AccountProfiles.TRON_PATH_PREFIX + tronIndex
}

/** Wallet-level pairing helpers (wallets-pairing.md §7.4, models-state.md §1.4). */
object WalletLevelPairing {
    /** iOS `WalletLevelPairingView.accountName`: "Bitcoin · Taproot", "Solana · Trust"; null for other codes. */
    fun accountName(code: String, profile: String): String? = when (code) {
        "btc" -> AccountProfiles.bitcoinSingle.firstOrNull { it.id == profile }?.let { "${Chain.Bitcoin.longName} · ${it.title}" }
        "sol" -> AccountProfiles.solana.firstOrNull { it.id == profile }?.let { "${Chain.Solana.longName} · ${it.title}" }
        else -> null
    }

    /** The row label: [accountName], else the chain's long name, else null (unknown codes are skipped). */
    fun rowName(code: String, profile: String): String? = accountName(code, profile) ?: Chain.fromPairingCode(code)?.longName
}
