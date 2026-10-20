package com.bittool.signer.core.model

import java.util.Locale
import java.util.UUID

/**
 * A local wallet's identity: the vault UUID (models-state.md §0.7, engine-contract.md §9.4). It is the only thing routes
 * and the engine's `localWalletId` carry. One spelling is used everywhere on Android: lowercase canonical
 * ([toString]); parsing is case-insensitive. Only the avatar fallback seed uses [uppercase], to match iOS colours.
 */
@JvmInline
value class WalletId(val uuid: UUID) : Comparable<WalletId> {
    /** Lowercase canonical form `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx` (file names, routes, engine `localWalletId`). */
    override fun toString(): String = uuid.toString()

    /** Uppercase form (iOS `uuidString`), used only as the avatar identity before setup (models-state.md §1.7). */
    val uppercase: String get() = uuid.toString().uppercase(Locale.ROOT)

    override fun compareTo(other: WalletId): Int = toString().compareTo(other.toString())

    companion object {
        private val CANONICAL = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** Strict parse (Swift `UUID(uuidString:)`: the 36-character form only, either case), else null. */
        fun parse(text: String?): WalletId? =
            if (text != null && CANONICAL.matches(text)) WalletId(UUID.fromString(text)) else null

        /** A new random identity (`UUID.randomUUID()` is backed by `SecureRandom`). */
        fun random(): WalletId = WalletId(UUID.randomUUID())
    }
}
