package com.bittool.signer.core.model.util

/**
 * UTF-8 byte length without allocating (Swift `text.utf8.count`). Every iOS limit written with `.utf8.count` or
 * measured on `Data` uses this: request 1,048,576, text payload 262,144, QR frame 16,384, payload 32,768, passphrase
 * 1,024 (models-state.md §0.3). An unpaired surrogate counts 1 byte, like the `?` that `encodeToByteArray` writes.
 */
fun CharSequence.utf8Size(): Int {
    var bytes = 0
    var i = 0
    val n = length
    while (i < n) {
        val c = this[i]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i + 1 < n && this[i + 1].isLowSurrogate() -> { i++; 4 }
            c.isSurrogate() -> 1
            else -> 3
        }
        i++
    }
    return bytes
}

/**
 * Swift `Character.isWhitespace` (Unicode White_Space): U+0009–000D, 0020, 0085, 00A0, 1680, 2000–200A, 2028, 2029,
 * 202F, 205F, 3000. Kotlin's `Char.isWhitespace()` differs (adds U+001C–001F, misses U+0085), so BIP39 tokenising and
 * name trimming use this (vault-crypto.md §17.10 item 4).
 */
fun isUnicodeWhiteSpace(codePoint: Int): Boolean = when (codePoint) {
    in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
    else -> false
}

/** Foundation `CharacterSet.whitespaces`: general category Zs plus U+0009. */
fun isSwiftWhitespaceOnly(codePoint: Int): Boolean =
    codePoint == 0x09 || Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt()

/** Foundation `CharacterSet.whitespacesAndNewlines`: Z* (Zs, Zl, Zp), U+0009–000D and U+0085. */
fun isSwiftWhitespaceOrNewline(codePoint: Int): Boolean {
    if (codePoint in 0x09..0x0D || codePoint == 0x85) return true
    return when (Character.getType(codePoint)) {
        Character.SPACE_SEPARATOR.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
        else -> false
    }
}

/** Swift `trimmingCharacters(in: .whitespacesAndNewlines)`. */
fun CharSequence.trimWhitespacesAndNewlines(): String = trimCodePoints(::isSwiftWhitespaceOrNewline)

/** Swift `trimmingCharacters(in: .whitespaces)` (spaces and tabs, not newlines). */
fun CharSequence.trimWhitespaces(): String = trimCodePoints(::isSwiftWhitespaceOnly)

/** Swift `trimmingCharacters(in:)` for an arbitrary scalar predicate. */
fun CharSequence.trimCodePoints(predicate: (Int) -> Boolean): String {
    var start = 0
    var end = length
    while (start < end) {
        val cp = Character.codePointAt(this, start)
        if (!predicate(cp)) break
        start += Character.charCount(cp)
    }
    while (end > start) {
        val cp = Character.codePointBefore(this, end)
        if (!predicate(cp)) break
        end -= Character.charCount(cp)
    }
    return subSequence(start, end).toString()
}

/** Trimmed-blank test with the `whitespacesAndNewlines` set (iOS `trimmingCharacters(...).isEmpty`). */
fun CharSequence.isBlankSwift(): Boolean {
    var i = 0
    while (i < length) {
        val cp = Character.codePointAt(this, i)
        if (!isSwiftWhitespaceOrNewline(cp)) return false
        i += Character.charCount(cp)
    }
    return true
}
