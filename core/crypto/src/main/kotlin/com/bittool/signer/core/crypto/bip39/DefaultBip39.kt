package com.bittool.signer.core.crypto.bip39

import com.bittool.signer.core.crypto.internal.wipe
import com.bittool.signer.core.model.util.trimWhitespacesAndNewlines

/**
 * [Bip39] — port of iOS `BIP39`, `BIP39Mnemonic` and `RecoveryPhraseDraft` (`Models/BIP39Wordlist.swift:150-390`).
 * Pure Kotlin (JVM-testable); lists are loaded lazily per language from [source] and cached (thread-safe), so text
 * that reads as English never loads the other nine. Thread-safe; call it off the main thread (setup computes the
 * draft on `Dispatchers.Default`, ARCHITECTURE.md §4.1).
 *
 * Detection order is [Bip39Language] declaration order (English, Chinese Simplified, Chinese Traditional, Japanese,
 * Korean, Spanish, French, Italian, Czech, Portuguese). Bound as a `@Singleton` by `CryptoModule`.
 */
class DefaultBip39(private val source: Bip39WordlistSource) : Bip39 {
    private val lists = arrayOfNulls<Bip39Wordlist>(Bip39Language.entries.size)

    override fun wordlist(language: Bip39Language): Bip39Wordlist {
        lists[language.ordinal]?.let { return it }
        synchronized(lists) {
            lists[language.ordinal]?.let { return it }
            return LoadedBip39Wordlist.load(language, source.read(language)).also { lists[language.ordinal] = it }
        }
    }

    override fun tokens(text: CharSequence): List<String> = Bip39Text.tokens(text)

    /** iOS `interpretations(_:)` (`:261-278`). */
    override fun interpretations(text: CharSequence): List<Bip39Canonical> {
        val tokens = tokens(text)
        if (tokens.isEmpty()) throw Bip39Error.Empty()
        if (tokens.size !in Bip39.SUPPORTED_LENGTHS) throw Bip39Error.UnsupportedLength(tokens.size)
        val exact = readings(tokens, exact = true)
        val results = exact.valid.toMutableList()
        var loose: Readings? = null
        if (results.isEmpty() || !Bip39Text.hasTypedAccents(tokens)) {
            loose = readings(tokens, exact = false)
            for (reading in loose.valid) if (reading !in results) results += reading // equal = same phrase and language
        }
        if (results.isNotEmpty()) return results
        if (exact.anyMapped || (loose ?: readings(tokens, exact = false)).anyMapped) throw Bip39Error.Checksum()
        // Swift `max(by:)` keeps the first maximum: the earliest language in detection order wins ties.
        var best = Bip39Language.English
        var bestCount = -1
        for (language in Bip39Language.entries) {
            val list = wordlist(language)
            val count = tokens.count { list.contains(it) }
            if (count > bestCount) {
                best = language
                bestCount = count
            }
        }
        val list = wordlist(best)
        throw Bip39Error.UnknownWords(tokens.filter { !list.contains(it) })
    }

    override fun canonicalize(text: CharSequence): Bip39Canonical {
        val options = interpretations(text)
        return options.singleOrNull() ?: throw Bip39Error.Ambiguous(options)
    }

    /**
     * iOS `validatedCanonical(_:)` (`:282-287`): exact spellings only, the first valid reading in detection order.
     * An empty phrase is [Bip39Error.Empty] as the contract states (iOS reports "it has 0 words"; never reachable,
     * the app only passes phrases it produced or accepted).
     */
    override fun validatedCanonical(phrase: CharSequence): Bip39Canonical {
        val tokens = tokens(phrase)
        if (tokens.isEmpty()) throw Bip39Error.Empty()
        if (tokens.size !in Bip39.SUPPORTED_LENGTHS) throw Bip39Error.UnsupportedLength(tokens.size)
        return readings(tokens, exact = true).valid.firstOrNull() ?: throw Bip39Error.Checksum()
    }

    /** iOS `RecoveryPhraseDraft.init(_:)` (`:343-384`). */
    override fun draft(text: CharSequence): RecoveryPhraseDraft {
        val tokens = tokens(text)
        val endsInWord = Bip39Text.endsInWord(text)
        val partial = if (endsInWord) tokens.lastOrNull() else null
        if (tokens.isEmpty()) return RecoveryPhraseDraft(tokens, null, emptyList(), partial, false)

        // A token is acceptable when it is a word, or it is the trailing word being typed and a valid prefix.
        fun acceptable(list: Bip39Wordlist, offset: Int, token: String, exact: Boolean): Boolean =
            list.contains(token, exact) || (offset == tokens.lastIndex && endsInWord && list.isPrefix(token))

        var chosen: Bip39Wordlist? = null
        var missing: List<String> = emptyList()
        // Pass 1: prefer a language whose listed spellings match exactly (the prefix test stays accent-insensitive).
        for (language in Bip39Language.entries) {
            val list = wordlist(language)
            if (!list.isAvailable) continue
            if (tokens.withIndex().all { (offset, token) -> acceptable(list, offset, token, exact = true) }) {
                chosen = list
                break
            }
        }
        // Pass 2: accent-insensitive; the first with nothing missing wins, else the one with strictly fewest missing.
        if (chosen == null) {
            for (language in Bip39Language.entries) {
                val list = wordlist(language)
                if (!list.isAvailable) continue
                val unknown = tokens.filterIndexed { offset, token -> !acceptable(list, offset, token, exact = false) }
                if (unknown.isEmpty()) {
                    chosen = list
                    missing = emptyList()
                    break
                }
                if (chosen == null || unknown.size < missing.size) {
                    chosen = list
                    missing = unknown
                }
            }
        }
        val list = chosen ?: return RecoveryPhraseDraft(tokens, null, tokens, partial, false)
        return RecoveryPhraseDraft(
            words = tokens,
            language = if (missing.size < tokens.size) list.language else null,
            unknownWords = missing,
            partialWord = partial,
            trailingWordIncomplete = partial != null && !list.contains(partial),
        )
    }

    /** iOS `words(fromSeedQR:)` (`:134-147`): Standard SeedQR, English list only, no checksum check here. */
    override fun wordsFromSeedQr(text: String): List<String>? {
        val digits = text.trimWhitespacesAndNewlines()
        val list = wordlist(Bip39Language.English)
        if (!list.isAvailable || digits.length !in SEED_QR_LENGTHS || digits.any { it !in '0'..'9' }) return null
        val words = ArrayList<String>(digits.length / 4)
        for (start in digits.indices step 4) {
            val value = digits.substring(start, start + 4).toInt()
            if (value >= LoadedBip39Wordlist.WORD_COUNT) return null
            words += list.words[value]
        }
        return words
    }

    private class Readings(val valid: List<Bip39Canonical>, val anyMapped: Boolean)

    /** iOS `readings(of:exact:)` (`:289-302`). */
    private fun readings(tokens: List<String>, exact: Boolean): Readings {
        val valid = ArrayList<Bip39Canonical>(2)
        var anyMapped = false
        val indices = IntArray(tokens.size)
        try {
            for (language in Bip39Language.entries) {
                if (!exact && !language.matchesWithoutAccents) continue
                val list = wordlist(language)
                if (!list.isAvailable) continue
                var resolved = true
                for ((i, token) in tokens.withIndex()) {
                    val index = list.index(token, exact)
                    if (index == null) {
                        resolved = false
                        break
                    }
                    indices[i] = index
                }
                if (!resolved) continue
                anyMapped = true
                if (!Bip39Text.checksumIsValid(indices)) continue
                val phrase = indices.joinToString(" ") { list.words[it] }
                if (valid.none { it.phrase == phrase }) valid += Bip39Canonical(phrase, language)
            }
        } finally {
            indices.wipe()
        }
        return Readings(valid, anyMapped)
    }

    override fun toString(): String = "DefaultBip39"

    private companion object {
        /** 12, 15, 18, 21 or 24 four-digit indices. */
        val SEED_QR_LENGTHS = setOf(48, 60, 72, 84, 96)
    }
}
