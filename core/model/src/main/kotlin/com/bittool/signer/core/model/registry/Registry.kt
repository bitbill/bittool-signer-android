package com.bittool.signer.core.model.registry

import com.bittool.signer.core.model.NetworkBrand
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Bundled reference data browsed in Settings › Networks, Tokens & Contracts (activity-settings.md §4). The engine uses
 * its own embedded copies; these are the app-bundle files `registry/token-registry.json` and
 * `registry/known-contracts.json`, decoded by `:core:data` (unknown keys ignored).
 */
@Serializable
data class TokenRegistryEntry(
    val chainFamily: String,
    val chainId: String? = null,
    val assetAddress: String,
    val name: String,
    val symbol: String,
    val decimals: Int,
    /** 16-hex content hash into the token icon pack. */
    val icon: String,
    val rank: Int? = null,
) {
    val id: String get() = listOf(chainFamily, chainId ?: "", assetAddress).joinToString(":")

    /** The grouping key shared with networks and contracts (`evm:1`, `tron:`). */
    val chain: String get() = chainFamily + ":" + (chainId ?: "")
}

@Serializable
data class TokenRegistryNetwork(
    val chainFamily: String,
    val chainId: String? = null,
    /** Stable slug ("ethereum", "smartchain"), used for identifiers and the network route. */
    val key: String,
    val name: String,
    val nativeSymbol: String,
    val tokens: Int,
) {
    val chain: String get() = chainFamily + ":" + (chainId ?: "")

    /** Bundled logo: EVM by chain ID, TRON, Solana; null → generic logo. */
    val brand: NetworkBrand?
        get() = when (chainFamily) {
            "evm" -> NetworkBrand.evm(chainId)
            "tron" -> NetworkBrand.Tron
            "solana" -> NetworkBrand.Solana
            else -> null
        }

    /** Page title: the brand's short name, else the registry name. */
    val shortName: String get() = brand?.shortName ?: name
}

@Serializable
data class KnownContractEntry(
    val chainFamily: String,
    /** `"*"` means the same (CREATE2) address on every EVM network. */
    val chainId: String? = null,
    val address: String,
    val name: String,
    /** The publishing project (JSON key `protocol`). */
    @SerialName("protocol") val project: String,
    val category: String,
) {
    val id: String get() = listOf(chainFamily, chainId ?: "", address).joinToString(":")
    val chain: String get() = chainFamily + ":" + (chainId ?: "")
    val everyChain: Boolean get() = chainId == "*"
    val categoryKind: ContractCategory get() = ContractCategory.fromWire(category)
}

/** Known-contract categories; each must have its own distinct icon in Settings (KnownContractTests). */
enum class ContractCategory(val wire: String) {
    Aggregator("aggregator"), Bridge("bridge"), Dex("dex"), Infra("infra"), Lending("lending"), Nft("nft"),
    Perp("perp"), Staking("staking"), Token("token"), Wallet("wallet"),
    /** `other` and any future category. */
    Other("other");

    companion object {
        fun fromWire(wire: String): ContractCategory = entries.firstOrNull { it.wire == wire } ?: Other
    }
}

/** The decoded registry files: what `ReferenceDataRepository` publishes. */
data class ReferenceData(
    val registryVersion: String,
    /** Registry networks in file order (Bitcoin is a static row, not listed). */
    val networks: List<TokenRegistryNetwork>,
    /** Tokens grouped by [TokenRegistryEntry.chain], file order kept. */
    val tokensByChain: Map<String, List<TokenRegistryEntry>>,
    /** Contracts grouped by [KnownContractEntry.chain], each group sorted by (project, name). */
    val contractsByChain: Map<String, List<KnownContractEntry>>,
    /** The known-contract file's `updated` ISO date ("2026-10-09"). */
    val contractsUpdated: String,
) {
    val contractCount: Int get() = contractsByChain.values.sumOf { it.size }
    val everyChainContracts: List<KnownContractEntry> get() = contractsByChain[EVERY_EVM_CHAIN] ?: emptyList()

    companion object {
        const val EVERY_EVM_CHAIN = "evm:*"
        val Empty = ReferenceData("", emptyList(), emptyMap(), emptyMap(), "")
    }
}
