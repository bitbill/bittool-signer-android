package com.bittool.signer.core.model.review

import com.bittool.signer.core.model.AddressFormatter
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.isBlankSwift
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The structured headline the engine builds from the parsed signing bytes (models-state.md §3.2.5, iOS
 * `Models.swift:218-343`). The generic keys are read leniently (a non-string is absent); the NFT keys are read only
 * together with a recognised [assetKind] and are size-bounded (fail closed).
 */
data class ReviewDisplay(
    val action: String,
    val amount: String? = null,
    val symbol: String? = null,
    val headline: String? = null,
    val counterpartyLabel: String? = null,
    val counterparty: String? = null,
    val fee: String? = null,
    /** `"energyBudget"` (TRON `fee_limit`): a cap on energy, not on the whole fee. Absent: a ceiling on the network fee. */
    val feeKind: String? = null,
    val note: String? = null,
    val assetKind: ReviewAssetKind? = null,
    val assetContract: String? = null,
    val assetContractLabel: String? = null,
    val tokenId: String? = null,
    val give: List<String> = emptyList(),
    val receive: List<String> = emptyList(),
    val others: List<String> = emptyList(),
) {
    val isNFT: Boolean get() = assetKind != null

    /** The engine's caption, else the verbatim default "NFT contract (not verified)" (no translation entry on iOS). */
    val assetContractCaption: String get() = assetContractLabel ?: DEFAULT_ASSET_CONTRACT_LABEL

    /** The value may be an ERC-20 amount rather than a token ID: captioned "Token ID or raw amount", else "Token ID". */
    val tokenIdIsAmbiguous: Boolean get() = assetKind == ReviewAssetKind.NftOrToken

    /** Whether the TRON energy-budget wording applies to [fee]. */
    val feeIsEnergyBudget: Boolean get() = feeKind == ENERGY_BUDGET

    /** The full token ID when neither headline nor amount already shows it as `#<id>`. */
    val summaryTokenId: String?
        get() {
            if (!isNFT) return null
            val id = tokenId ?: return null
            val shown = listOfNotNull(headline, amount).any { text(it, showsTokenId = id) }
            return if (shown) null else id
        }

    /** Whether the summary shows the contract / token-ID box. */
    val showsAssetBox: Boolean get() = isNFT && (assetContract != null || summaryTokenId != null)

    /** Non-empty order lists in reading order (give, receive, others); empty unless [isNFT]. */
    val flowSections: List<ReviewFlowSection>
        get() {
            if (!isNFT) return emptyList()
            return listOf(
                ReviewFlowSection(ReviewFlowSection.Kind.Give, give),
                ReviewFlowSection(ReviewFlowSection.Kind.Receive, receive),
                ReviewFlowSection(ReviewFlowSection.Kind.Others, others),
            ).filter { it.lines.isNotEmpty() }
        }

    companion object {
        const val MAX_LIST_LINES = 32
        const val MAX_STRING_LENGTH = 300
        const val DEFAULT_ASSET_CONTRACT_LABEL = "NFT contract (not verified)"
        const val ENERGY_BUDGET = "energyBudget"

        /** iOS `ReviewDisplay(raw)`: null unless [element] is an object with a string `action`. */
        fun parse(element: JsonElement?): ReviewDisplay? {
            val raw = element as? JsonObject ?: return null
            val action = raw.str("action") ?: return null
            val base = ReviewDisplay(
                action = action,
                amount = raw.str("amount"),
                symbol = raw.str("symbol"),
                headline = raw.str("headline"),
                counterpartyLabel = raw.str("counterpartyLabel"),
                counterparty = raw.str("counterparty"),
                fee = raw.str("fee"),
                feeKind = raw.str("feeKind"),
                note = raw.str("note"),
            )
            val kind = ReviewAssetKind.fromWire(raw.str("assetKind")) ?: return base
            return base.copy(
                assetKind = kind,
                assetContract = boundedValue(raw["assetContract"]),
                assetContractLabel = boundedValue(raw["assetContractLabel"]),
                tokenId = boundedValue(raw["tokenId"]),
                give = boundedLines(raw["give"]),
                receive = boundedLines(raw["receive"]),
                others = boundedLines(raw["others"]),
            )
        }

        /** A string that is non-blank and at most 300 characters, else null. Identifiers are shown whole or not at all. */
        fun boundedValue(value: JsonElement?): String? {
            val string = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (string.isBlankSwift() || Graphemes.exceeds(string, MAX_STRING_LENGTH)) return null
            return string
        }

        /**
         * All strings or nothing; blank lines dropped; each line bounded by [boundedLine]; more than 32 lines end with
         * `"+N more (see Details)"` (verbatim English on iOS).
         */
        fun boundedLines(value: JsonElement?): List<String> {
            val array = value as? JsonArray ?: return emptyList()
            val strings = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return emptyList() }
            val lines = strings.filter { !it.isBlankSwift() }.map(::boundedLine)
            if (lines.size <= MAX_LIST_LINES) return lines
            val shown = lines.take(MAX_LIST_LINES - 1)
            return shown + "+${lines.size - shown.size} more (see Details)"
        }

        /** A line over 300 characters keeps its first 149 and last 150 characters around an ellipsis. */
        fun boundedLine(line: String): String {
            if (!Graphemes.exceeds(line, MAX_STRING_LENGTH)) return line
            val head = (MAX_STRING_LENGTH - 1) / 2
            return Graphemes.prefix(line, head) + "…" + Graphemes.suffix(line, MAX_STRING_LENGTH - 1 - head)
        }

        /** True when [text] contains `#<id>` not followed by an ASCII digit ("#12345" does not show 1234). */
        fun text(text: String, showsTokenId: String): Boolean {
            if (showsTokenId.isEmpty()) return false
            val needle = "#$showsTokenId"
            var from = 0
            while (true) {
                val at = text.indexOf(needle, from)
                if (at < 0) return false
                val end = at + needle.length
                if (end == text.length || text[end] !in '0'..'9') return true
                from = end
            }
        }

        /** What TalkBack reads for a token ID: groups of four joined by ", " beyond 12 characters. */
        fun spokenTokenId(id: String): String =
            if (Graphemes.exceeds(id, 12)) AddressFormatter.groups(id, 4).joinToString(", ") else id
    }
}
