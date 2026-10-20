package com.bittool.signer.core.model.review

import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.json.displayText
import com.bittool.signer.core.model.json.obj
import com.bittool.signer.core.model.json.objects
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.json.strings
import kotlinx.serialization.json.JsonObject

/**
 * The decoded request under review (models-state.md §3.2, iOS `Models.swift:357-402`). Parsing never throws: every key
 * has the iOS default. The store validates the commitment (hash shape, chain, non-empty fields) separately.
 */
data class TransactionReview(
    val chain: Chain,
    /** Engine English; default "Review request" (drawn through `ReviewText.phrase`). */
    val title: String,
    val summary: String,
    val fields: List<ReviewField>,
    /** Engine English, always shown verbatim (never translated). */
    val warnings: List<String>,
    val requestHash: String,
    val asset: ReviewAsset?,
    val decodeStatus: DecodeStatus,
    val riskLevel: RiskLevel,
    val network: ReviewNetwork?,
    val display: ReviewDisplay?,
    val contract: ReviewContract?,
) {
    /**
     * Explicit per-request consent is required when the request is not fully decoded or its risk is high. The app is
     * stricter than the engine, which gates only partial/opaque decoding (engine-contract.md §9 item 21).
     */
    val requiresAcknowledgement: Boolean get() = decodeStatus != DecodeStatus.FullyDecoded || riskLevel == RiskLevel.High

    /** `network.name`, else the value of the field labelled exactly "Network", else the chain's name (verbatim). */
    val networkName: String get() = network?.name ?: fields.firstOrNull { it.label == "Network" }?.value ?: chain.displayName

    companion object {
        const val DEFAULT_TITLE = "Review request"

        fun parse(raw: JsonObject): TransactionReview {
            val chain = Chain.fromWire(raw.str("chain")) ?: Chain.Bitcoin
            val warnings = raw.strings("warnings") ?: emptyList()
            val fields = (raw.objects("fields") ?: emptyList()).mapIndexed { offset, field ->
                ReviewField(offset, field.str("label") ?: "", field["value"]?.displayText() ?: "")
            }
            val network = raw.obj("network")?.let { n ->
                n.str("name")?.let { name -> ReviewNetwork(chain, n.str("chainId"), name, n.str("symbol")) }
            }
            return TransactionReview(
                chain = chain,
                title = raw.str("title") ?: DEFAULT_TITLE,
                summary = raw.str("summary") ?: "",
                fields = fields,
                warnings = warnings,
                requestHash = raw.str("requestHash") ?: "",
                asset = ReviewAsset.parse(raw["asset"]),
                decodeStatus = DecodeStatus.fromWire(raw.str("decodeStatus")) ?: DecodeStatus.Opaque,
                riskLevel = RiskLevel.fromWire(raw.str("riskLevel")) ?: if (warnings.isEmpty()) RiskLevel.Normal else RiskLevel.Warning,
                network = network,
                display = ReviewDisplay.parse(raw["display"]),
                contract = ReviewContract.parse(raw["contract"]),
            )
        }
    }
}
