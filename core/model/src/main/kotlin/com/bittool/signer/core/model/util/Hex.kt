package com.bittool.signer.core.model.util

/**
 * Hexadecimal text, always lowercase on output and either case on input. Never use `"%02x".format(...)`: it depends on
 * the default locale and allocates per byte (ownbit-build-release.md §8.7.5).
 */
object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    /** Lowercase hex of [bytes]. */
    fun encode(bytes: ByteArray): String = String(encodeToChars(bytes))

    /** Lowercase hex as a `CharArray`, so a secret (entropy, seed) can be wiped after use. */
    fun encodeToChars(bytes: ByteArray): CharArray {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[2 * i] = DIGITS[v ushr 4]
            out[2 * i + 1] = DIGITS[v and 0x0F]
        }
        return out
    }

    /** Bytes of [text] (either case), or null when it has an odd length or a non-hex character. No `0x` prefix. */
    fun decode(text: CharSequence): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = digit(text[2 * i])
            val lo = digit(text[2 * i + 1])
            if (hi < 0 || lo < 0) {
                out.wipe()
                return null
            }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** iOS `isHex(value, bytes:)`: exactly `bytes * 2` hex characters of either case (models-state.md §2.3). */
    fun isHex(value: CharSequence?, bytes: Int): Boolean =
        value != null && value.length == bytes * 2 && value.isNotEmpty() && value.all { digit(it) >= 0 }

    /** `^[0-9a-fA-F]{64}$`, the request/plan hash shape. */
    fun isHash64(value: CharSequence?): Boolean = isHex(value, 32)

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
