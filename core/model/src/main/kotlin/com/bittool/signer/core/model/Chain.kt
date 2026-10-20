package com.bittool.signer.core.model

/**
 * Chain families (models-state.md §1.1, iOS `Models.swift:3-32`). Declaration order is the picker order: Bitcoin, EVM,
 * TRON, Solana. Names are verbatim data (only "Bitcoin" has a translation entry; the others are shown untranslated).
 */
enum class Chain(
    /** Wire value sent to and read from the engine (`chain`). */
    val wire: String,
    /** iOS `name`: "Bitcoin", "EVM", "TRON", "Solana" (verbatim; "Pair %@", "%@ address #%lld"). */
    val displayName: String,
    /** iOS `longName`, where a chain family is listed on its own ("Ethereum & EVM"). */
    val longName: String,
    val ticker: String,
    /** Code of the chain in the wallet-level pairing `verify` rows (`btc`, `evm`, `tron`, `sol`). */
    val pairingCode: String,
) {
    Bitcoin("bitcoin", "Bitcoin", "Bitcoin", "BTC", "btc"),
    Evm("evm", "EVM", "Ethereum & EVM", "EVM", "evm"),
    Tron("tron", "TRON", "TRON", "TRX", "tron"),
    Solana("solana", "Solana", "Solana", "SOL", "sol");

    /** The network whose logo stands for the family (`Design/Logos.swift:72-81`). */
    val primaryNetwork: NetworkBrand
        get() = when (this) {
            Bitcoin -> NetworkBrand.Bitcoin
            Evm -> NetworkBrand.Ethereum
            Tron -> NetworkBrand.Tron
            Solana -> NetworkBrand.Solana
        }

    companion object {
        /** The chain with this exact wire value, or null (case-sensitive like Swift `Chain(rawValue:)`). */
        fun fromWire(wire: String?): Chain? = entries.firstOrNull { it.wire == wire }

        /** The chain for a wallet-level pairing code (`btc`, `evm`, `tron`, `sol`), or null. */
        fun fromPairingCode(code: String?): Chain? = entries.firstOrNull { it.pairingCode == code }
    }
}

/**
 * Networks with bundled vector logos (models-state.md §1.2). Logos never load from the network; `:core:ui` maps each
 * case to its drawable with a `when` (never `getIdentifier`).
 */
enum class NetworkBrand(
    /** Verbatim network name (logo content description). */
    val displayName: String,
    val shortName: String,
    val nativeSymbol: String,
    /** EVM chain ID as the engine reports it (`review.network.chainId`), or null for non-EVM networks. */
    val evmChainId: String?,
) {
    Bitcoin("Bitcoin", "Bitcoin", "BTC", null),
    Ethereum("Ethereum", "Ethereum", "ETH", "1"),
    Bnb("BNB Smart Chain", "BNB Chain", "BNB", "56"),
    Arbitrum("Arbitrum One", "Arbitrum", "ETH", "42161"),
    Base("Base", "Base", "ETH", "8453"),
    Tron("TRON", "TRON", "TRX", null),
    Solana("Solana", "Solana", "SOL", null);

    companion object {
        /** The networks that share one EVM account, in `ChainGlyph` order (2×2: ETH, BNB / ARB, BASE). */
        val evmNetworks: List<NetworkBrand> = listOf(Ethereum, Bnb, Arbitrum, Base)

        /** iOS `NetworkBrand.evm(chainID:)`: the first case with this chain ID, or null (e.g. 10, 137, 100, 43114). */
        fun evm(chainId: String?): NetworkBrand? = entries.firstOrNull { it.evmChainId != null && it.evmChainId == chainId }
    }
}
