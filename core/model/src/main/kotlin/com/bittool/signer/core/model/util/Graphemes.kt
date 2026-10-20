package com.bittool.signer.core.model.util

import java.text.BreakIterator
import java.text.CharacterIterator
import java.util.Locale

/**
 * User-perceived characters (extended grapheme clusters), the unit Swift's `String.count`, `prefix` and `suffix` use
 * (models-state.md §0.3). Every iOS length limit written with `.count` (300 / 280 / 200 / 160 / 40 / 12 / 128 / 64 …)
 * counts these, never UTF-16 units: "👍🏽" is 1, `String.length` says 4.
 *
 * Works on any [CharSequence] without copying it into a `String`, so secrets held in a `CharArray`
 * (`CharBuffer.wrap(chars)`) can be measured without leaving an unwipeable copy. Thread-safe (a new iterator per call).
 */
object Graphemes {
    /** Number of grapheme clusters in [text] (Swift `text.count`). */
    fun count(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        if (text.isAsciiWithoutCr()) return text.length
        val it = iterator(text)
        var count = 0
        while (it.next() != BreakIterator.DONE) count++
        return count
    }

    /** True when [text] has more than [limit] grapheme clusters, stopping early (cheap on huge values). */
    fun exceeds(text: CharSequence, limit: Int): Boolean {
        if (text.length <= limit) return false
        if (text.isAsciiWithoutCr()) return text.length > limit
        val it = iterator(text)
        var count = 0
        while (it.next() != BreakIterator.DONE) {
            if (++count > limit) return true
        }
        return false
    }

    /** The first [n] grapheme clusters (Swift `String(text.prefix(n))`). */
    fun prefix(text: CharSequence, n: Int): String {
        require(n >= 0) { "n must be non-negative" }
        if (n == 0) return ""
        val end = boundaryAfter(text, n) ?: return text.toString()
        return text.subSequence(0, end).toString()
    }

    /** The last [n] grapheme clusters (Swift `String(text.suffix(n))`). */
    fun suffix(text: CharSequence, n: Int): String {
        require(n >= 0) { "n must be non-negative" }
        if (n == 0) return ""
        val total = count(text)
        if (total <= n) return text.toString()
        val start = boundaryAfter(text, total - n) ?: return ""
        return text.subSequence(start, text.length).toString()
    }

    /** The first grapheme cluster, or null for an empty text (Swift `text.first`). */
    fun first(text: CharSequence): String? = if (text.isEmpty()) null else prefix(text, 1)

    /** Splits [text] into its grapheme clusters, in order. */
    fun split(text: CharSequence): List<String> {
        if (text.isEmpty()) return emptyList()
        val it = iterator(text)
        val out = ArrayList<String>()
        var start = 0
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out += text.subSequence(start, end).toString()
            start = end
            end = it.next()
        }
        return out
    }

    /** Whether [index] (a UTF-16 offset) falls on a grapheme boundary of [text]; 0 and `length` always do. */
    fun isBoundary(text: CharSequence, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return index == 0 || index == text.length
        return iterator(text).isBoundary(index)
    }

    /** Swift `candidate.hasPrefix(prefix)`: a character-wise prefix, so "가" is not a prefix of "각" (§17.10 item 8). */
    fun hasPrefix(candidate: CharSequence, prefix: CharSequence): Boolean {
        if (prefix.isEmpty()) return true
        if (candidate.length < prefix.length) return false
        for (i in prefix.indices) if (candidate[i] != prefix[i]) return false
        return candidate.length == prefix.length || isBoundary(candidate, prefix.length)
    }

    private fun boundaryAfter(text: CharSequence, clusters: Int): Int? {
        if (text.isAsciiWithoutCr()) return if (clusters >= text.length) null else clusters
        val boundaries = iterator(text)
        var end = 0
        repeat(clusters) {
            val next = boundaries.next()
            if (next == BreakIterator.DONE) return null
            end = next
        }
        return if (end >= text.length) null else end
    }

    private fun iterator(text: CharSequence): BreakIterator =
        BreakIterator.getCharacterInstance(Locale.ROOT).apply { this.text = CharSequenceIterator(text) }

    /** ASCII except CR: every UTF-16 unit is its own cluster (CR LF is one cluster). */
    private fun CharSequence.isAsciiWithoutCr(): Boolean {
        for (i in indices) {
            val c = this[i]
            if (c.code >= 0x80 || c == '\r') return false
        }
        return true
    }
}

/** A [CharacterIterator] over any [CharSequence], so `BreakIterator` never needs a `String` copy of a secret. */
internal class CharSequenceIterator(private val text: CharSequence) : CharacterIterator {
    private var pos = 0

    override fun first(): Char { pos = 0; return current() }
    override fun last(): Char { pos = if (text.isEmpty()) 0 else text.length - 1; return current() }
    override fun current(): Char = if (pos in text.indices) text[pos] else CharacterIterator.DONE
    override fun next(): Char {
        if (pos < text.length) pos++
        return current()
    }
    override fun previous(): Char {
        if (pos == 0) return CharacterIterator.DONE
        pos--
        return current()
    }
    override fun setIndex(position: Int): Char {
        require(position in 0..text.length) { "index out of range" }
        pos = position
        return current()
    }
    override fun getBeginIndex(): Int = 0
    override fun getEndIndex(): Int = text.length
    override fun getIndex(): Int = pos
    override fun clone(): Any = CharSequenceIterator(text).also { it.pos = pos }
}
