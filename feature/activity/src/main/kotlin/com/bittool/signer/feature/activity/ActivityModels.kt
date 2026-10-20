package com.bittool.signer.feature.activity

import androidx.compose.runtime.Immutable
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.NetworkBrand
import com.bittool.signer.core.model.signing.SigningReceipt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Who signed a receipt, as the Activity row and detail name it (iOS `AppStore.walletLabel(for:)`,
 * `ActivityTab.swift:139`): the wallet's current name, or "Deleted wallet" once it is gone. Records survive wallet
 * deletion (Privacy Policy §2). The row uses "Deleted wallet" too instead of iOS's bare "Wallet" (activity-settings.md
 * §12.2 recommendation), so the list and the detail agree.
 */
@Immutable
sealed interface WalletLabel {
    data class Named(val name: String) : WalletLabel
    data object Deleted : WalletLabel

    companion object {
        /** The label of [receipt]'s wallet in [state], or null for a record written without a wallet. */
        fun of(receipt: SigningReceipt, state: SignerState): WalletLabel? {
            if (receipt.walletId == null) return null
            val name = receipt.walletIdOrNull?.let(state::displayName)
            return if (name != null) Named(name) else Deleted
        }
    }
}

/** One row of the Activity list (iOS `ActivityRow`). Strings stay unresolved until drawn (DECISIONS §4). */
@Immutable
data class ActivityEntry(
    val id: String,
    /** Engine English (drawn through `ReviewText`, falling back to the English). */
    val title: String,
    /** Engine English, verbatim; empty when the record has none. */
    val summary: String,
    val wallet: WalletLabel?,
    /** Epoch milliseconds. */
    val dateMillis: Long,
    val chain: Chain,
    val chainId: String?,
)

/** One day section (iOS `ActivityTab.sections`): the device-zone day, newest receipt first. */
@Immutable
data class ActivityDay(val date: LocalDate, val entries: List<ActivityEntry>) {
    /** Stable lazy-list key. */
    val key: Long get() = date.toEpochDay()
}

/**
 * The Activity list's grouping (`ActivityTab.swift:8-12`): items grouped by the start of their day in [zone]
 * (iOS `Calendar.current`), days newest first, items within a day newest first. Pure and stable (equal times keep the
 * journal order). [millis] is the item's epoch-millisecond time.
 */
object ActivityGrouping {
    fun <T> days(items: List<T>, zone: ZoneId, millis: (T) -> Long): List<Pair<LocalDate, List<T>>> =
        items
            .groupBy { Instant.ofEpochMilli(millis(it)).atZone(zone).toLocalDate() }
            .entries
            .sortedByDescending { it.key }
            .map { (day, list) -> day to list.sortedByDescending(millis) }

    /** The list's day sections for [entries]. */
    fun sections(entries: List<ActivityEntry>, zone: ZoneId): List<ActivityDay> =
        days(entries, zone) { it.dateMillis }.map { (day, list) -> ActivityDay(day, list) }

    /** The day sections of raw receipts (tests and previews). */
    fun receiptDays(receipts: List<SigningReceipt>, zone: ZoneId): List<Pair<LocalDate, List<SigningReceipt>>> =
        days(receipts, zone) { it.date }
}

/**
 * The logo of a receipt (iOS `ReceiptLogo`, `ActivityTab.swift:90-104`): an EVM receipt shows its network by chain ID
 * (Ethereum, BNB, Arbitrum, Base; any other chain ID → null, the generic logo), other chains their primary network.
 */
fun receiptBrand(chain: Chain, chainId: String?): NetworkBrand? =
    if (chain == Chain.Evm) NetworkBrand.evm(chainId) else chain.primaryNetwork
