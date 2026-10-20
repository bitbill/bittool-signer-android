package com.bittool.signer.core.model

import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.trimWhitespacesAndNewlines

/**
 * Presentation of public identifiers (models-state.md §1.6, iOS `Design/AddressText.swift:6-39`). Grouping and
 * shortening only change how a value is drawn; copying always uses the original string.
 */
object AddressFormatter {
    private val evm = Regex("^0x[0-9a-fA-F]{40}$")
    private val tron = Regex("^T[1-9A-HJ-NP-Za-km-z]{33}$")
    private val bitcoin = Regex("^(bc1|[13])[0-9A-Za-z]{20,87}$")
    private val base58 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    /**
     * Groups of [size] characters; a `0x`/`0X` prefix is glued to the first group (`0x9858 EfFD …`). An empty value or
     * a non-positive size returns `[value]`. The last group may be shorter.
     */
    fun groups(value: String, size: Int = 4): List<String> {
        if (size <= 0 || value.isEmpty()) return listOf(value)
        val chars = Graphemes.split(value)
        val result = ArrayList<String>()
        var i = 0
        if (value.startsWith("0x") || value.startsWith("0X")) {
            val end = minOf(chars.size, 2 + size)
            result += chars.subList(0, end).joinToString("")
            i = end
        }
        while (i < chars.size) {
            val end = minOf(chars.size, i + size)
            result += chars.subList(i, end).joinToString("")
            i = end
        }
        return result
    }

    /**
     * Middle ellipsis for summaries only: `prefix(leading + hexPrefix) + "…" + suffix(trailing)` where `hexPrefix` is 2
     * only for a lowercase `0x`. Unchanged when the value has at most `leading + trailing + hexPrefix + 1` characters.
     */
    fun short(value: String, leading: Int = 6, trailing: Int = 4): String {
        val hexPrefix = if (value.startsWith("0x")) 2 else 0
        if (Graphemes.count(value) <= leading + trailing + hexPrefix + 1) return value
        return Graphemes.prefix(value, leading + hexPrefix) + "…" + Graphemes.suffix(value, trailing)
    }

    /**
     * Address heuristics deciding `ReviewField.rendersAsAddress` and the summary counterparty fallback. Trimmed first;
     * rejects spaces, newlines and lengths outside 25…90, then accepts EVM, TRON, Bitcoin or base58 (Solana) shapes.
     */
    fun looksLikeAddress(value: String): Boolean {
        val trimmed = value.trimWhitespacesAndNewlines()
        if (trimmed.contains(' ') || trimmed.contains('\n')) return false
        if (Graphemes.count(trimmed) !in 25..90) return false
        return evm.matches(trimmed) || tron.matches(trimmed) || bitcoin.matches(trimmed) || base58.matches(trimmed)
    }
}
