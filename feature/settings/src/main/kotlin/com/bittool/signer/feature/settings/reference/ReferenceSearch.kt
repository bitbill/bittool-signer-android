package com.bittool.signer.feature.settings.reference

import com.bittool.signer.core.model.registry.KnownContractEntry
import com.bittool.signer.core.model.registry.TokenRegistryEntry
import com.bittool.signer.core.model.util.trimWhitespaces
import java.util.Locale

/**
 * The network page's search (iOS `NetworkReferenceView`, `TokenDirectoryView.swift:256-277`), pure:
 * - the query is trimmed of spaces and tabs (Swift `.whitespaces`, not newlines) and lower-cased locale-independently
 *   (Swift `lowercased()`; no Turkish-i surprises);
 * - a token matches when its symbol **contains** the query, or its name contains it, or its contract address **starts
 *   with** it;
 * - a contract matches when its name, project, category or address **contains** the query;
 * - an empty query matches everything, in file order.
 */
object ReferenceSearch {
    fun normalize(query: String): String = query.trimWhitespaces().lowercase(Locale.ROOT)

    fun tokens(tokens: List<TokenRegistryEntry>, normalized: String): List<TokenRegistryEntry> {
        if (normalized.isEmpty()) return tokens
        return tokens.filter {
            it.symbol.lowercase(Locale.ROOT).contains(normalized) ||
                it.name.lowercase(Locale.ROOT).contains(normalized) ||
                it.assetAddress.lowercase(Locale.ROOT).startsWith(normalized)
        }
    }

    fun contracts(contracts: List<KnownContractEntry>, normalized: String): List<KnownContractEntry> {
        if (normalized.isEmpty()) return contracts
        return contracts.filter {
            it.name.lowercase(Locale.ROOT).contains(normalized) ||
                it.project.lowercase(Locale.ROOT).contains(normalized) ||
                it.category.lowercase(Locale.ROOT).contains(normalized) ||
                it.address.lowercase(Locale.ROOT).contains(normalized)
        }
    }
}
