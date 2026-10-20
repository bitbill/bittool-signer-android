package com.bittool.signer.core.model.review

import com.bittool.signer.core.model.AddressFormatter
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.NetworkBrand
import com.bittool.signer.core.model.json.bool
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.trimWhitespaces
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** How completely the engine decoded the request (models-state.md §3.2). Unknown wire values read as [Opaque]. */
enum class DecodeStatus(val wire: String) {
    FullyDecoded("fullyDecoded"),
    PartiallyDecoded("partiallyDecoded"),
    Opaque("opaque");

    companion object {
        fun fromWire(wire: String?): DecodeStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** The engine's risk verdict (models-state.md §3.2). Stored raw in receipts ([wire]). */
enum class RiskLevel(val wire: String) {
    Normal("normal"),
    Warning("warning"),
    High("high");

    companion object {
        fun fromWire(wire: String?): RiskLevel? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * One Details row (models-state.md §3.2.1, iOS `Models.swift:51-94`). [id] is the index in `fields`. Labels are engine
 * English, drawn through `ReviewText.label`; values are shown verbatim.
 */
data class ReviewField(val id: Int, val label: String, val value: String) {
    /** The text before the first `" · "` ("Instruction 2" of "Instruction 2 · Recipient"), else null. */
    val group: String? get() = label.indexOf(" · ").takeIf { it >= 0 }?.let { label.substring(0, it) }

    /** The text after the first `" · "`, else the whole label. */
    val shortLabel: String get() = label.indexOf(" · ").takeIf { it >= 0 }?.let { label.substring(it + 3) } ?: label

    /** More than 280 characters or more than 6 line breaks (CR LF counts as one, like Swift's `Character`). */
    val isLong: Boolean get() = Graphemes.exceeds(value, LONG_LIMIT) || newlineCount(value) > 6

    /** `shortLabel` after its last `": "`, trimmed of spaces, lowercased ("Call 2: Amount" → "amount"). */
    private val baseLabel: String
        get() {
            var base = shortLabel
            val colon = base.lastIndexOf(": ")
            if (colon >= 0) base = base.substring(colon + 2)
            return base.trimWhitespaces().lowercase()
        }

    /** Token IDs, quantities and item lists: shown whole as monospaced text, never as an address, never collapsed. */
    val isTokenIdentifier: Boolean
        get() {
            val base = baseLabel
            return base.contains("token id") || base.contains("quantity") || base == "items" || base.startsWith("items ")
        }

    /** Numeric fields, matched by label (a long digit string can pass the Bitcoin address pattern). */
    val holdsNumber: Boolean
        get() {
            val base = baseLabel
            return isTokenIdentifier || base.contains("amount") || base.contains("allowance") || base.contains("raw units") ||
                base.contains("(wei)")
        }

    /** Whether Details renders the value as a copyable address. */
    val rendersAsAddress: Boolean get() = !holdsNumber && AddressFormatter.looksLikeAddress(value)

    companion object {
        const val LONG_LIMIT = 280

        private fun newlineCount(value: String): Int {
            var count = 0
            for (i in value.indices) if (value[i] == '\n' && (i == 0 || value[i - 1] != '\r')) count++
            return count
        }
    }
}

/**
 * A token the engine recognised from its bundled registry (models-state.md §3.2.2). Decoded strictly like iOS
 * `JSONDecoder`: a missing required key, a wrong type on any present key, or a non-integer `decimals` makes the whole
 * asset null. [icon] is the 16-hex content hash used by the token icon pack.
 */
data class ReviewAsset(
    val chainFamily: String,
    val chainId: String?,
    val assetAddress: String,
    val name: String,
    val symbol: String,
    val decimals: Int,
    val tokenProgram: String?,
    val icon: String,
    val rawAmount: String?,
    val displayAmount: String?,
) {
    companion object {
        fun parse(element: JsonElement?): ReviewAsset? {
            val raw = element as? JsonObject ?: return null
            fun required(key: String): String? = (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun optional(key: String): Result<String?> = when (val v = raw[key]) {
                null, JsonNull -> Result.success(null)
                is JsonPrimitive -> if (v.isString) Result.success(v.content) else Result.failure(IllegalArgumentException(key))
                else -> Result.failure(IllegalArgumentException(key))
            }
            val decimals = (raw["decimals"] as? JsonPrimitive)
                ?.takeIf { !it.isString && it !is JsonNull && it.content.none { c -> c == '.' || c == 'e' || c == 'E' } }
                ?.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: return null
            return ReviewAsset(
                chainFamily = required("chainFamily") ?: return null,
                chainId = optional("chainId").getOrElse { return null },
                assetAddress = required("assetAddress") ?: return null,
                name = required("name") ?: return null,
                symbol = required("symbol") ?: return null,
                decimals = decimals,
                tokenProgram = optional("tokenProgram").getOrElse { return null },
                icon = required("icon") ?: return null,
                rawAmount = optional("rawAmount").getOrElse { return null },
                displayAmount = optional("displayAmount").getOrElse { return null },
            )
        }
    }
}

/** The network the engine reported for this exact request (models-state.md §3.2.3). */
data class ReviewNetwork(val family: Chain, val chainId: String?, val name: String, val symbol: String?) {
    /** The bundled logo: EVM by chain ID (null for OP, Polygon, Gnosis, Avalanche → generic logo), else the family's. */
    val brand: NetworkBrand?
        get() = if (family == Chain.Evm) NetworkBrand.evm(chainId) else family.primaryNetwork
}

/**
 * What the bundled known-contract library says about the address a request hands assets or authority to
 * (models-state.md §3.2.4). A hit never means the request is safe; it is never shown in green.
 */
data class ReviewContract(
    val address: String,
    val role: Role,
    val known: Boolean,
    val name: String? = null,
    val project: String? = null,
    val category: String? = null,
    val everyChain: Boolean = false,
    /** The registry token at a non-listed address, e.g. "Tether USD (USDT)". */
    val token: String? = null,
) {
    enum class Role(val wire: String) {
        Spender("spender"), Operator("operator"), Contract("contract");

        companion object {
            /** Unknown roles from a newer engine fall back to [Contract], the least specific wording. */
            fun fromWire(wire: String?): Role = entries.firstOrNull { it.wire == wire } ?: Contract
        }
    }

    /** `[project, category]` joined by `" · "`, or null ("Uniswap · infra"). */
    val subtitle: String? get() = listOfNotNull(project, category).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    /** Which notice the review shows (iOS `ContractNotice.Kind`). */
    val noticeKind: ContractNoticeKind
        get() = when {
            known -> ContractNoticeKind.Known
            token != null -> ContractNoticeKind.Token
            role == Role.Contract -> ContractNoticeKind.Unlisted
            else -> ContractNoticeKind.UnlistedSpender
        }

    /** The identification line: `name — subtitle` when known, the token text for a registry token, else null. */
    val noticeDetail: String?
        get() = when (noticeKind) {
            ContractNoticeKind.Known -> listOfNotNull(name, subtitle).joinToString(" — ")
            ContractNoticeKind.Token -> token
            else -> null
        }

    companion object {
        /** iOS `ReviewContract(raw)`: null without a bounded address; "known" without a name is not a hit. */
        fun parse(element: JsonElement?): ReviewContract? {
            val raw = element as? JsonObject ?: return null
            val address = ReviewDisplay.boundedValue(raw["address"]) ?: return null
            val role = Role.fromWire(raw.str("role"))
            val name = ReviewDisplay.boundedValue(raw["name"])
            val known = (raw.bool("known") ?: false) && name != null
            if (!known) {
                return ReviewContract(address, role, known = false, token = ReviewDisplay.boundedValue(raw["token"]))
            }
            return ReviewContract(
                address = address,
                role = role,
                known = true,
                name = name,
                project = ReviewDisplay.boundedValue(raw["protocol"]),
                category = ReviewDisplay.boundedValue(raw["category"]),
                everyChain = raw.bool("everyChain") ?: false,
            )
        }
    }
}

/**
 * Known-contract notice kinds (scan-sign.md §11.5): Known → warning colour, `verified`; Token → accent, `info`;
 * UnlistedSpender → danger, `warning`; Unlisted → secondary, `help`. Titles by role are in `ReviewStrings`.
 */
enum class ContractNoticeKind { Known, Token, UnlistedSpender, Unlisted }

/** What an NFT-aware review is about (models-state.md §3.2.5). Unknown values are ignored together with every NFT key. */
enum class ReviewAssetKind(val wire: String) {
    /** The method only exists for NFTs, or the NFT status is proven. */
    Nft("nft"),
    /** A selector shared by ERC-20 and ERC-721, or a 0-decimal SPL transfer of 1. */
    NftOrToken("nftOrToken"),
    /** ERC-1155: IDs may be fungible, never presented as an NFT. */
    MultiToken("multiToken"),
    /** A marketplace order (Seaport, LooksRare, Blur). */
    NftOrder("nftOrder");

    companion object {
        fun fromWire(wire: String?): ReviewAssetKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** One labelled marketplace-order list (models-state.md §3.2.5). [id] is the kind's wire value. */
data class ReviewFlowSection(val kind: Kind, val lines: List<String>) {
    enum class Kind(val wire: String) { Give("give"), Receive("receive"), Others("others") }

    val id: String get() = kind.wire
}
