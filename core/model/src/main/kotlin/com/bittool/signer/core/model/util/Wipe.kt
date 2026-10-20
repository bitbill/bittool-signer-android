package com.bittool.signer.core.model.util

/**
 * Secret hygiene (DECISIONS.md §6): secrets live in `ByteArray`/`CharArray` and are zeroed as soon as they are no longer
 * needed. Strings cannot be wiped; keep them out of secret paths wherever an API allows.
 */
fun ByteArray.wipe() = fill(0)

/** Zeroes every char. */
fun CharArray.wipe() = fill('\u0000')

/** Runs [block] with this array and wipes it afterwards, also when [block] throws. */
inline fun <R> ByteArray.useAndWipe(block: (ByteArray) -> R): R = try {
    block(this)
} finally {
    wipe()
}

/** Runs [block] with this array and wipes it afterwards, also when [block] throws. */
inline fun <R> CharArray.useAndWipe(block: (CharArray) -> R): R = try {
    block(this)
} finally {
    wipe()
}

/** Constant-time comparison of two byte arrays (length difference returns early: lengths are not secret). */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

/** Constant-time comparison of two char arrays (length difference returns early). */
fun constantTimeEquals(a: CharArray, b: CharArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}

/**
 * UTF-8 bytes of [chars] without an intermediate `String`, for KDF input (passwords, phrases). The caller wipes the
 * result. Unpaired surrogates encode as `?`, matching `String.encodeToByteArray()`.
 */
fun utf8Bytes(chars: CharArray): ByteArray {
    val out = ByteArray(java.nio.CharBuffer.wrap(chars).utf8Size())
    var o = 0
    var i = 0
    while (i < chars.size) {
        val c = chars[i]
        val cp: Int = when {
            c.isHighSurrogate() && i + 1 < chars.size && chars[i + 1].isLowSurrogate() -> {
                val v = Character.toCodePoint(c, chars[i + 1]); i++; v
            }
            c.isSurrogate() -> '?'.code
            else -> c.code
        }
        when {
            cp < 0x80 -> out[o++] = cp.toByte()
            cp < 0x800 -> {
                out[o++] = (0xC0 or (cp shr 6)).toByte()
                out[o++] = (0x80 or (cp and 0x3F)).toByte()
            }
            cp < 0x10000 -> {
                out[o++] = (0xE0 or (cp shr 12)).toByte()
                out[o++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                out[o++] = (0x80 or (cp and 0x3F)).toByte()
            }
            else -> {
                out[o++] = (0xF0 or (cp shr 18)).toByte()
                out[o++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                out[o++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                out[o++] = (0x80 or (cp and 0x3F)).toByte()
            }
        }
        i++
    }
    return out
}
