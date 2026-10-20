package com.bittool.signer.core.crypto.bip39

import com.bittool.signer.core.crypto.internal.wipe
import com.bittool.signer.core.crypto.kdf.Sha256
import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.isUnicodeWhiteSpace
import com.bittool.signer.core.model.util.wipe
import java.text.Normalizer

/**
 * The text rules of iOS `Models/BIP39Wordlist.swift`, with the Swift-vs-Kotlin pitfalls of vault-crypto.md §17.10
 * handled: grapheme clusters where Swift iterates `Character`s, code points where it iterates scalars, Unicode
 * White_Space instead of `Char.isWhitespace`, `Locale.ROOT` lowercasing (Turkish "I"), and explicit normalisation
 * because Kotlin compares code units where Swift compares canonically equivalent strings.
 */
internal object Bip39Text {
    /** `,` U+002C, `，` U+FF0C, `、` U+3001: word separators besides white space (`:216`). */
    private const val COMMA = ","
    private const val FULLWIDTH_COMMA = "，"
    private const val IDEOGRAPHIC_COMMA = "、"

    /** NFKD, then lowercase; renormalised so the result compares like Swift's canonical string equality. */
    fun exactKey(text: String): String {
        val lowered = Normalizer.normalize(text, Normalizer.Form.NFKD).lowercase()
        return if (Normalizer.isNormalized(lowered, Normalizer.Form.NFKD)) lowered else Normalizer.normalize(lowered, Normalizer.Form.NFKD)
    }

    /** iOS `key(_:language:)` (`:103-109`): [exactKey], without Mn scalars for accent-insensitive lists. */
    fun key(text: String, language: Bip39Language): String {
        val exact = exactKey(text)
        if (!language.matchesWithoutAccents) return exact
        return stripNonSpacingMarks(exact)
    }

    /** Every scalar except general category Mn. */
    fun stripNonSpacingMarks(text: String): String {
        var i = 0
        var hasMark = false
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) {
                hasMark = true
                break
            }
            i += Character.charCount(cp)
        }
        if (!hasMark) return text
        val out = StringBuilder(text.length)
        i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** A Swift `Character` that separates words: its first scalar is White_Space, or it is one of the three commas. */
    fun isSeparator(cluster: String): Boolean =
        isUnicodeWhiteSpace(cluster.codePointAt(0)) || cluster == COMMA || cluster == FULLWIDTH_COMMA || cluster == IDEOGRAPHIC_COMMA

    /**
     * iOS `BIP39Mnemonic.tokens` (`:214-230`): split on separator clusters (empty pieces dropped); drop numbering
     * ("1.", "１)", "12"); split an all-Han piece of more than one character into single characters.
     */
    fun tokens(text: CharSequence): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        val piece = StringBuilder()
        for (cluster in Graphemes.split(text)) {
            if (isSeparator(cluster)) {
                flush(piece, result)
            } else {
                piece.append(cluster)
            }
        }
        flush(piece, result)
        return result
    }

    private fun flush(piece: StringBuilder, into: MutableList<String>) {
        if (piece.isEmpty()) return
        val token = piece.toString()
        piece.setLength(0)
        if (isNumbering(token)) return
        if (token.codePointCount(0, token.length) > 1 && allHan(token)) {
            var i = 0
            while (i < token.length) {
                val cp = token.codePointAt(i)
                into += String(Character.toChars(cp))
                i += Character.charCount(cp)
            }
        } else {
            into += token
        }
    }

    /**
     * "1." or "１)" numbering: the NFKD form is only ASCII digits, `.` and `)`. Only ASCII digits after width folding:
     * Chinese numerals such as 一 and 十 are real BIP39 words (`:218-221`, Revision 2.1).
     */
    fun isNumbering(token: String): Boolean =
        Normalizer.normalize(token, Normalizer.Form.NFKD).all { it in '0'..'9' || it == '.' || it == ')' }

    /** iOS `isHan` (`:232-237`). */
    fun isHan(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF || codePoint in 0xF900..0xFAFF || codePoint in 0x20000..0x2FA1F

    private fun allHan(token: String): Boolean {
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            if (!isHan(cp)) return false
            i += Character.charCount(cp)
        }
        return true
    }

    /**
     * iOS `hasTypedAccents` (`:304-309`): some token has an alphabetic scalar below U+0250 and its NFD contains a
     * nonspacing mark. "Typed accents are taken as deliberate and never matched away."
     */
    fun hasTypedAccents(tokens: List<String>): Boolean = tokens.any { token ->
        var latin = false
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            if (cp < 0x0250 && Character.isAlphabetic(cp)) {
                latin = true
                break
            }
            i += Character.charCount(cp)
        }
        latin && Normalizer.normalize(token, Normalizer.Form.NFD).let { nfd ->
            var j = 0
            var mark = false
            while (j < nfd.length) {
                val cp = nfd.codePointAt(j)
                if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) {
                    mark = true
                    break
                }
                j += Character.charCount(cp)
            }
            mark
        }
    }

    /** iOS draft `endsInWord` (`:346`): the last `Character` exists and is not white space or one of the commas. */
    fun endsInWord(text: CharSequence): Boolean {
        if (text.isEmpty()) return false
        val last = Graphemes.suffix(text, 1)
        return !isSeparator(last)
    }

    /**
     * The standard BIP39 checksum of 11-bit word indices (iOS `checksumIsValid`, `:312-329`): the first
     * `count·11 − count/3` bits are the entropy, the last `count/3` bits must equal the start of SHA-256(entropy).
     * The entropy and the bit buffer are wiped (they are the wallet's secret).
     */
    fun checksumIsValid(indices: IntArray): Boolean {
        val count = indices.size
        if (count !in Bip39.SUPPORTED_LENGTHS || indices.any { it !in 0 until 2048 }) return false
        val bits = BooleanArray(count * 11)
        for ((n, index) in indices.withIndex()) {
            for (shift in 10 downTo 0) bits[n * 11 + (10 - shift)] = (index shr shift) and 1 == 1
        }
        val checksumBits = count / 3
        val entropyBits = bits.size - checksumBits
        val entropy = ByteArray(entropyBits / 8)
        val sha = Sha256()
        val hash = IntArray(8)
        try {
            for (i in 0 until entropyBits) if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            sha.update(entropy)
            sha.finish()
            sha.state.copyInto(hash)
            for (i in 0 until checksumBits) {
                val hashBit = (hash[i / 32] ushr (31 - i % 32)) and 1 == 1
                if (hashBit != bits[entropyBits + i]) return false
            }
            return true
        } finally {
            bits.fill(false)
            entropy.wipe()
            hash.wipe()
            sha.wipe()
        }
    }
}
