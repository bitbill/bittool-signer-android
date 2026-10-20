package com.bittool.signer.core.crypto.bip39

import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.Hex
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer

/**
 * One official wordlist (iOS `BIP39Wordlist.init(language:bundle:)`, `Models/BIP39Wordlist.swift:77-96`): the file's
 * SHA-256 must equal the pinned [Bip39Language.sha256], it must decode as UTF-8, split on `\n` (empty pieces dropped,
 * like Swift `split`) into exactly 2,048 words with 2,048 distinct [Bip39Text.key]s and 2,048 distinct
 * [Bip39Text.exactKey]s (the "Latin lists are unique without accents" invariant). Anything else yields an
 * unavailable list, which detection skips. [words] are NFC (display and storage form). Immutable and thread-safe.
 */
internal class LoadedBip39Wordlist private constructor(
    override val language: Bip39Language,
    override val words: List<String>,
    private val keys: List<String>,
    private val exactLookup: Map<String, Int>,
    private val lookup: Map<String, Int>,
) : Bip39Wordlist {
    override val isAvailable: Boolean get() = words.size == WORD_COUNT

    override fun index(token: String, exact: Boolean): Int? {
        if (!isAvailable) return null
        return if (exact) exactLookup[Bip39Text.exactKey(token)] else lookup[Bip39Text.key(token, language)]
    }

    override fun suggestions(prefix: String, limit: Int): List<String> {
        val key = Bip39Text.key(prefix, language)
        if (key.isEmpty() || !isAvailable || limit <= 0) return emptyList()
        val result = ArrayList<String>(minOf(limit, 8))
        for (i in keys.indices) {
            if (hasPrefix(keys[i], key)) {
                result += words[i]
                if (result.size == limit) break
            }
        }
        return result
    }

    override fun toString(): String = "Bip39Wordlist($language, available=$isAvailable)"

    companion object {
        const val WORD_COUNT = 2048

        /** Builds the list of [language] from the raw file [bytes] (null when the file could not be read). */
        fun load(language: Bip39Language, bytes: ByteArray?): LoadedBip39Wordlist {
            if (bytes == null || Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes)) != language.sha256) {
                return unavailable(language)
            }
            val text = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                return unavailable(language)
            }
            val list = text.split('\n').filter { it.isNotEmpty() }
            if (list.size != WORD_COUNT) return unavailable(language)
            val keys = list.map { Bip39Text.key(it, language) }
            val lookup = HashMap<String, Int>(WORD_COUNT * 2)
            val exactLookup = HashMap<String, Int>(WORD_COUNT * 2)
            list.forEachIndexed { i, word ->
                lookup.putIfAbsent(keys[i], i)
                exactLookup.putIfAbsent(Bip39Text.exactKey(word), i)
            }
            if (lookup.size != WORD_COUNT || exactLookup.size != WORD_COUNT) return unavailable(language)
            val words = list.map { Normalizer.normalize(it, Normalizer.Form.NFC) }
            return LoadedBip39Wordlist(language, words, keys, exactLookup, lookup)
        }

        private fun unavailable(language: Bip39Language) =
            LoadedBip39Wordlist(language, emptyList(), emptyList(), emptyMap(), emptyMap())

        /**
         * Swift `String.hasPrefix`: a `Character`-wise prefix, so a typed "か" is not a prefix of "が…" (NFKD
         * "が") and "가" is not a prefix of "각" (one L-V-T cluster) (vault-crypto.md §13.2, §17.10 item 8).
         * Both sides are NFKD, so code-unit equality of the clusters is canonical equality.
         */
        internal fun hasPrefix(candidate: String, prefix: String): Boolean {
            if (!candidate.startsWith(prefix)) return false
            if (candidate.length == prefix.length) return true
            val before = candidate[prefix.length - 1]
            val after = candidate[prefix.length]
            // Two ASCII characters other than CR LF are always separate clusters: skip the break iterator.
            if (before.code < 0x80 && after.code < 0x80 && !(before == '\r' && after == '\n')) return true
            return Graphemes.isBoundary(candidate, prefix.length)
        }
    }
}
