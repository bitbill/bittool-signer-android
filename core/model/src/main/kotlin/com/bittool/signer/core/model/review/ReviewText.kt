package com.bittool.signer.core.model.review

import com.bittool.signer.core.model.util.trimWhitespaces

/**
 * The translation of one engine phrase in the current interface language, or null when the catalogue has no entry
 * (iOS detects a missing entry with a sentinel default). `:core:l10n` provides the Android implementation backed by
 * `IosStrings.lookup` + `Resources.getString`.
 */
fun interface EngineTextLookup {
    fun translation(english: String): String?

    companion object {
        /** No translations: everything stays the engine's English (JVM tests, English UI). */
        val None: EngineTextLookup = EngineTextLookup { null }
    }
}

/**
 * Draw-time translation of engine English (models-state.md §4, iOS `Models/ReviewText.swift`). The engine's labels,
 * actions and headlines are covered by the request fingerprint, so they are never translated inside the engine; the
 * UI translates when drawing and falls back to the English whenever a phrase has no entry. Warnings, summaries,
 * matching messages and engine errors are never passed through here.
 */
class ReviewText(private val lookup: EngineTextLookup) {
    /** A whole phrase (a Details label, a display action, a counterparty label). `""` stays `""`. */
    fun phrase(english: String): String {
        if (english.isEmpty()) return english
        return lookup.translation(english) ?: english
    }

    /**
     * A headline that may end in an engine-supplied value ("Method 0x3593564c"): the whole string if translated, else
     * the translated prefix key (`Method`, `Safe:`, `Batch:`, `New`) + " " + the value reproduced exactly.
     */
    fun headline(english: String): String {
        translationOf(english)?.let { return it }
        for (prefix in PREFIXES) {
            if (!english.startsWith(prefix)) continue
            val value = english.substring(prefix.length)
            if (value.isEmpty()) break
            return phrase(prefix.dropLast(1)) + " " + value
        }
        return english
    }

    /**
     * A label the engine built by appending a unit ("Amount (USDC)"): the whole label if translated, else the
     * translated stem before the last "(" + " " + the bracketed unit unchanged; else the English.
     */
    fun label(english: String): String {
        translationOf(english)?.let { return it }
        if (!english.endsWith(")")) return english
        val open = english.lastIndexOf('(')
        if (open < 0) return english
        val stem = english.substring(0, open).trimWhitespaces()
        val unit = english.substring(open)
        val translated = translationOf(stem) ?: return english
        return "$translated $unit"
    }

    private fun translationOf(english: String): String? = if (english.isEmpty()) null else lookup.translation(english)

    companion object {
        private val PREFIXES = listOf("Method ", "Safe: ", "Batch: ", "New ")

        /**
         * Shortens a decimal amount to at most [places] fraction digits, rounding **away from zero** so a fee ceiling is
         * never shown below what the request allows ("0.999999999 ETH" → "1.00000000 ETH"). Works on digit strings, so a
         * whole part beyond `Long` never overflows; anything it cannot round is returned unchanged
         * (models-state.md §4, ReviewTextTests).
         */
        fun shortDecimal(text: String, places: Int = 8): String {
            val space = text.indexOf(' ')
            val number = if (space >= 0) text.substring(0, space) else text
            val unit = if (space >= 0) " " + text.substring(space + 1) else ""
            val dot = number.indexOf('.')
            if (dot < 0 || !number.drop(1).all { it in '0'..'9' || it == '.' }) return text
            val whole = number.substring(0, dot)
            val fraction = number.substring(dot + 1)
            if (fraction.length <= places || !fraction.all { it in '0'..'9' }) return text
            val kept = fraction.substring(0, places)
            val dropped = fraction.substring(places)
            if (dropped.all { it == '0' }) return "$whole.$kept$unit"
            val carriedFraction = increment(kept) ?: return text
            if (!carriedFraction.second) return "$whole.${carriedFraction.first}$unit"
            val carriedWhole = increment(whole) ?: return text
            val head = if (carriedWhole.second) "1" else ""
            return head + carriedWhole.first + "." + carriedFraction.first + unit
        }

        /** Adds one to a run of decimal digits: the new digits and whether it carried past the front; null on a non-digit. */
        private fun increment(digits: String): Pair<String, Boolean>? {
            val chars = digits.toCharArray()
            var carry = true
            var index = chars.size - 1
            while (carry && index >= 0) {
                val c = chars[index]
                if (c !in '0'..'9') return null
                if (c == '9') chars[index] = '0' else { chars[index] = c + 1; carry = false }
                index--
            }
            return String(chars) to carry
        }
    }
}
