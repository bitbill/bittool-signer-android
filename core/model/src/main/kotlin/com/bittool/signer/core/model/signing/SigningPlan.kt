package com.bittool.signer.core.model.signing

import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.PublicMetadataStatus
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.WalletPublicMetadata
import com.bittool.signer.core.model.json.canonicalJson
import com.bittool.signer.core.model.json.int
import com.bittool.signer.core.model.json.ints
import com.bittool.signer.core.model.json.objects
import com.bittool.signer.core.model.json.str
import com.bittool.signer.core.model.json.strings
import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.Hex
import kotlinx.serialization.json.JsonObject

/**
 * One wallet's part of a signing plan (models-state.md §3.3, iOS `Models.swift:652-682`). Tasks are signed strictly in
 * plan order, each with its own wallet password.
 */
data class SigningTask(
    val id: String,
    val walletId: WalletId,
    /** Must equal the local wallet's `walletKeyId` before and after signing. */
    val walletKeyId: String,
    val chain: Chain,
    val paths: List<String>,
    val publicKeys: List<String>,
    val addresses: List<String>,
    /** Bitcoin input indexes (zero-based); empty for other chains. */
    val inputs: List<Int>,
    /** Solana required-signer index of the first target, or null. */
    val signerSlot: Int?,
) {
    /** Non-empty addresses, de-duplicated in first-seen order. */
    val displayAddresses: List<String> get() = distinct(addresses)

    /** Non-empty paths, de-duplicated in first-seen order. */
    val displayPaths: List<String> get() = distinct(paths)

    private fun distinct(values: List<String>): List<String> = values.filter { it.isNotEmpty() }.distinct()

    companion object {
        /** iOS `SigningTask(raw)`; throws [AppIssue] "Invalid signing plan." on any structural miss. */
        fun parse(raw: JsonObject): SigningTask {
            fun invalid(): Nothing = throw AppIssue("Invalid signing plan.")
            val id = raw.str("id")?.takeIf { it.isNotEmpty() } ?: invalid()
            val walletId = WalletId.parse(raw.str("localWalletId")) ?: invalid()
            val key = raw.str("walletKeyId")?.takeIf { Graphemes.count(it) == 64 } ?: invalid()
            val chain = Chain.fromWire(raw.str("chain")) ?: invalid()
            val paths = raw.strings("paths")?.takeIf { it.isNotEmpty() } ?: invalid()
            val publicKeys = raw.strings("publicKeys")?.takeIf { it.size == paths.size } ?: invalid()
            return SigningTask(
                id = id,
                walletId = walletId,
                walletKeyId = key,
                chain = chain,
                paths = paths,
                publicKeys = publicKeys,
                addresses = raw.strings("addresses") ?: emptyList(),
                inputs = raw.ints("inputs") ?: emptyList(),
                signerSlot = raw.objects("targets")?.firstOrNull()?.int("signerSlot"),
            )
        }
    }
}

/**
 * The fixed signing plan for a reviewed request (models-state.md §3.3, iOS `Models.swift:684-702`). [raw] is the whole
 * resolved object exactly as the engine returned it (including `review`, `version`, `planHash`) and goes back to
 * `signPlanTask` unchanged; the engine re-hashes it.
 */
class SigningPlan private constructor(
    val requestHash: String,
    val planHash: String,
    val tasks: List<SigningTask>,
    val raw: JsonObject,
) {
    /** Sorted-keys JSON of [raw] (snapshot comparisons, debugging sizes). */
    val canonicalJson: String by lazy { raw.canonicalJson() }

    override fun equals(other: Any?): Boolean = other is SigningPlan && other.planHash == planHash && other.raw == raw
    override fun hashCode(): Int = planHash.hashCode()
    override fun toString(): String = "SigningPlan(planHash=$planHash, tasks=${tasks.size})"

    companion object {
        const val MAX_TASKS = 64

        /**
         * Validates [raw] against [expectedRequestHash] (exact, case-sensitive): `status == "ready"`, 64-hex `planHash`,
         * 1…64 task objects, each a valid [SigningTask], unique task IDs. Throws [AppIssue] "Invalid signing plan." or
         * "Duplicate signing task.".
         */
        fun parse(raw: JsonObject, expectedRequestHash: String): SigningPlan {
            val planHash = raw.str("planHash")
            val tasks = raw.objects("tasks")
            if (raw.str("status") != "ready" || raw.str("requestHash") != expectedRequestHash || !Hex.isHash64(planHash) ||
                tasks == null || tasks.isEmpty() || tasks.size > MAX_TASKS
            ) {
                throw AppIssue("Invalid signing plan.")
            }
            val parsed = tasks.map(SigningTask::parse)
            if (parsed.map { it.id }.toSet().size != parsed.size) throw AppIssue("Duplicate signing task.")
            return SigningPlan(expectedRequestHash, planHash!!, parsed, raw)
        }
    }
}

/**
 * One local wallet offered to `resolveSigningPlan` (models-state.md §5.4 step 7): every vault wallet in display order,
 * with its authenticated public index when ready, else its status.
 */
data class PlanCandidate(val walletId: WalletId, val index: WalletPublicMetadata?, val status: PublicMetadataStatus)

/**
 * Why a reviewed request has no plan (models-state.md §5.6). iOS keeps English identifiers and switches on them; the
 * Android UI maps each case to a resource. [english] is the iOS identifier (for parity tests and the explanation).
 */
sealed interface MatchingIssue {
    val english: String

    /** Retry ("Match Again") is offered when the iOS message contains "interrupted" or "changed". */
    val canRetry: Boolean get() = english.contains("interrupted") || english.contains("changed")

    /** Which explanation sentence the review shows under the message. */
    val explanation: MatchingExplanation get() = MatchingExplanation.Default

    /** `canSign` was not true. */
    data object Unsupported : MatchingIssue {
        override val english = "Unsupported signing protocol"
        override val explanation = MatchingExplanation.Unsupported
    }

    /** `suspend()` ran while matching (background, App Lock, capture). Verbatim English on iOS. */
    data object Interrupted : MatchingIssue {
        override val english = "Matching interrupted. Try again."
    }

    /** The wallet collection changed during matching, or a reload invalidated the plan (an iOS catalogue key). */
    data object WalletsChanged : MatchingIssue {
        override val english = "Wallet information changed. Match this request again."
    }

    /**
     * `resolveSigningPlan` returned `status != "ready"`: its `message`, engine English that the UI draws through
     * `ReviewText.phrase` (translated when the iOS catalogue has it, as the resolver's five messages are; else verbatim).
     */
    data class Engine(val message: String) : MatchingIssue {
        override val english: String get() = message
        override val explanation: MatchingExplanation
            get() = when (message) {
                "No matching wallet" -> MatchingExplanation.NoMatch
                "Missing signer information" -> MatchingExplanation.MissingSigner
                "Ambiguous wallet match" -> MatchingExplanation.Ambiguous
                "Wallet setup required" -> MatchingExplanation.SetupRequired
                else -> MatchingExplanation.Default
            }
    }

    /** An error thrown while matching (plan validation, engine/vault failure); [english] is its English text. */
    data class Failure(val text: UiText) : MatchingIssue {
        override val english: String
            get() = when (text) {
                is UiText.Ios -> text.english
                is UiText.Verbatim -> text.text
                is UiText.Resource -> ""
            }
    }

    companion object {
        const val NO_MATCHING_WALLET = "No matching wallet"
    }
}

/** Explanation shown under a matching issue (scan-sign.md §11.7 table). */
enum class MatchingExplanation { NoMatch, MissingSigner, Ambiguous, SetupRequired, Unsupported, Default }
