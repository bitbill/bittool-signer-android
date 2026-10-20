package com.bittool.signer.core.model.review

import com.bittool.signer.core.model.AddressFormatter
import com.bittool.signer.core.model.signing.SigningTask
import com.bittool.signer.core.model.util.Graphemes

/**
 * Pure presentation rules of the review page, the Activity "Signed Request" page and the task cards, ported from the
 * static helpers of iOS `Features/Sign/RequestReviewView.swift` so `:feature:sign` and `:feature:activity` (through the
 * shared `:core:ui` review components) draw identically. Labels returned here are engine/iOS English keys.
 */
object ReviewPresentation {
    /** How a Details value is drawn (iOS `ReviewDetailsCard.RowStyle`, scan-sign.md §11.9). */
    enum class DetailRowStyle {
        /** A copyable, grouped address. */
        Address,
        /** Token IDs, quantities and item lists: whole, plain monospaced and wrapping. */
        Identifier,
        /** Long text collapsed behind "Show All" (first 280 characters + "…"). */
        Collapsible,
        Text,
    }

    /** iOS `rowStyle`: label first, then value. */
    fun rowStyle(field: ReviewField): DetailRowStyle = when {
        field.rendersAsAddress -> DetailRowStyle.Address
        field.isTokenIdentifier && !Graphemes.exceeds(field.value, 4_096) -> DetailRowStyle.Identifier
        field.isLong -> DetailRowStyle.Collapsible
        else -> DetailRowStyle.Text
    }

    /** The collapsed form of a [DetailRowStyle.Collapsible] value. */
    fun collapsedValue(value: String): String = Graphemes.prefix(value, ReviewField.LONG_LIMIT) + "…"

    private val longHex = Regex("^[0-9a-fA-F]{16,}$")

    /** A [DetailRowStyle.Text] value drawn monospaced (footnote) rather than as prose (subheadline). */
    fun looksTechnical(value: String): Boolean =
        value.startsWith("0x") || value.startsWith("m/") || longHex.matches(value) || value.contains('\n')

    /** A counterparty shown in the summary: [label] is engine English drawn through `ReviewText.phrase`. */
    data class Counterparty(val label: String, val value: String)

    /**
     * iOS `ReviewSummaryCard.counterparty(of:)`: the display's counterparty when a display exists (label defaults to
     * "To"); without a display, the single Recipient/Spender/Operator field whose value looks like an address.
     */
    fun counterparty(review: TransactionReview): Counterparty? {
        val display = review.display
        if (display != null) {
            val value = display.counterparty ?: return null
            return Counterparty(display.counterpartyLabel ?: "To", value)
        }
        val candidates = review.fields.filter {
            it.shortLabel in setOf("Recipient", "Spender", "Operator") && AddressFormatter.looksLikeAddress(it.value)
        }
        val field = candidates.singleOrNull() ?: return null
        return Counterparty(if (field.shortLabel == "Recipient") "To" else field.shortLabel, field.value)
    }

    /** Whether the summary's counterparty box carries the contract notice (so it is not repeated as a card). */
    fun showsNoticeInSummary(review: TransactionReview): Boolean {
        val contract = review.contract ?: return false
        val counterparty = counterparty(review) ?: return false
        return counterparty.value.equals(contract.address, ignoreCase = true)
    }

    /** Whether the separate contract notice card is shown (a contract exists and the summary does not carry it). */
    fun showsNoticeCard(review: TransactionReview): Boolean = review.contract != null && !showsNoticeInSummary(review)

    /** The large amount line: `amount` plus `symbol` when a display has an amount. */
    fun amountText(review: TransactionReview): String? {
        val display = review.display ?: return null
        val amount = display.amount ?: return null
        return listOfNotNull(amount, display.symbol).joinToString(" ")
    }

    /**
     * The text headline: the display's headline when there is no amount (a display with an amount shows its headline
     * as the secondary line instead), or the summary when no display exists and it is non-empty.
     */
    fun textHeadline(review: TransactionReview): String? {
        val display = review.display
        if (display != null) return if (display.amount == null) display.headline else null
        return review.summary.ifEmpty { null }
    }

    /** The secondary headline under an amount, when the display has both. */
    fun secondaryHeadline(review: TransactionReview): String? {
        val display = review.display ?: return null
        return if (display.amount != null) display.headline else null
    }

    /** Max lines of the amount: unlimited (null) for NFTs (78-digit raw prices wrap), else 2. */
    fun amountLineLimit(display: ReviewDisplay?): Int? = if (display?.isNFT == true) null else 2

    /** Max lines of the text headline: unlimited for NFTs, else 8. */
    fun headlineLineLimit(display: ReviewDisplay?): Int? = if (display?.isNFT == true) null else 8

    /** Review page (`RequestReviewView.swift:80-81`): warnings starting with HIGH RISK, UNLIMITED or HIGH FEE are danger. */
    fun isSevereWarning(warning: String): Boolean {
        val upper = warning.uppercase()
        return upper.startsWith("HIGH RISK") || upper.startsWith("UNLIMITED") || upper.startsWith("HIGH FEE")
    }

    /**
     * Activity's "Signed Request" page uses a different, older rule (`ActivityTab.swift:292`): danger when the
     * uppercased warning starts with "HIGH", so an "UNLIMITED …" approval warning is warning-toned there. Kept for parity;
     * the shared `ReviewWarnings` component takes the rule as a parameter.
     */
    fun isSevereStoredWarning(warning: String): Boolean = warning.uppercase().startsWith("HIGH")

    /** The fee line: [value] is `ReviewText.shortDecimal(fee)`; [energyBudget] picks the TRON energy wording. */
    data class FeeLine(val value: String, val energyBudget: Boolean)

    fun feeLine(display: ReviewDisplay?): FeeLine? {
        val fee = display?.fee ?: return null
        return FeeLine(ReviewText.shortDecimal(fee), display.feeIsEnergyBudget)
    }

    /** Task cards repeat the signing address only when Details has no "Requested signer" field. */
    fun taskShowsAddress(review: TransactionReview?): Boolean = review?.fields?.none { it.label == "Requested signer" } ?: true

    /** Task cards repeat the path only when Details has no "Derivation path" field starting with `m/`. */
    fun taskShowsPath(review: TransactionReview?): Boolean =
        review?.fields?.none { it.label == "Derivation path" && it.value.startsWith("m/") } ?: true

    /** "Inputs signed" value: 1-based Bitcoin input numbers ("#1, #2"), or null when the task lists none. */
    fun inputsSigned(task: SigningTask): String? = task.inputs.takeIf { it.isNotEmpty() }?.joinToString(", ") { "#${it + 1}" }

    /** "Signer position" value: the 1-based Solana signer slot, or null. */
    fun signerPosition(task: SigningTask): String? = task.signerSlot?.let { (it + 1).toString() }
}

/**
 * The few decoded facts Activity repeats for a stored request (iOS `ActivityDetailView.essentials(of:)`,
 * activity-settings.md §2.5). [label] is an iOS English key ("Action", "Amount", "Network fee", "Energy budget") or,
 * for the counterparty, the engine's own caption ("Spender", "Operator", "To") resolved through `ReviewText.phrase`.
 */
data class ActivityEssential(val id: String, val label: String, val value: String) {
    companion object {
        fun of(review: TransactionReview): List<ActivityEssential> {
            val lines = mutableListOf(ActivityEssential("action", "Action", review.display?.action ?: review.title))
            val display = review.display ?: return lines
            display.amount?.let { amount ->
                lines += ActivityEssential("amount", "Amount", listOfNotNull(amount, display.symbol).joinToString(" "))
            }
            display.counterparty?.let { counterparty ->
                lines += ActivityEssential("counterparty", display.counterpartyLabel ?: "To", AddressFormatter.short(counterparty, 6, 6))
            }
            display.fee?.let { fee ->
                val energy = display.feeIsEnergyBudget
                lines += ActivityEssential("fee", if (energy) "Energy budget" else "Network fee", if (energy) ReviewText.shortDecimal(fee) else fee)
            }
            return lines
        }
    }
}
