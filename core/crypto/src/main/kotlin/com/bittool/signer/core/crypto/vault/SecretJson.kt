package com.bittool.signer.core.crypto.vault

import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.VaultSecret
import com.bittool.signer.core.model.util.wipe

/**
 * The vault cleartext `{"mnemonic":"…","passphrase":"…"}` (sorted keys, iOS `VaultSecret` through
 * `VaultCrypto.encoder()`, `Core/VaultCrypto.swift:161`) built and parsed directly between `CharArray`s and a
 * `ByteArray`, so the secret never becomes a `String` (DECISIONS.md §6). The caller wipes every array.
 *
 * [decode] is strict: one JSON object with exactly the two string members (any order, JSON white space allowed,
 * every JSON string escape), well-formed UTF-8 and nothing after it; anything else is [VaultError.AuthenticationFailed],
 * the iOS result of a cleartext that does not decode (`:189-191`).
 */
internal object SecretJson {
    private const val MNEMONIC = "mnemonic"
    private const val PASSPHRASE = "passphrase"

    /** The byte size of [encode]'s result, measured without building it (no copy of the secret is made). */
    fun size(mnemonic: CharArray, passphrase: CharArray): Int =
        2 + member(MNEMONIC, mnemonic, null, 0) + 1 + member(PASSPHRASE, passphrase, null, 0)

    fun encode(mnemonic: CharArray, passphrase: CharArray): ByteArray {
        val size = size(mnemonic, passphrase)
        val out = ByteArray(size)
        var o = 0
        out[o++] = '{'.code.toByte()
        o += member(MNEMONIC, mnemonic, out, o)
        out[o++] = ','.code.toByte()
        o += member(PASSPHRASE, passphrase, out, o)
        out[o++] = '}'.code.toByte()
        check(o == size)
        return out
    }

    /** Writes (or, with a null [out], only measures) `"key":"escaped value"`; returns the byte count. */
    private fun member(key: String, value: CharArray, out: ByteArray?, offset: Int): Int {
        var o = offset
        fun put(b: Int) {
            if (out != null) out[o] = b.toByte()
            o++
        }
        put('"'.code)
        for (c in key) put(c.code)
        put('"'.code)
        put(':'.code)
        put('"'.code)
        var i = 0
        while (i < value.size) {
            val c = value[i]
            when {
                c == '"' -> { put('\\'.code); put('"'.code) }
                c == '\\' -> { put('\\'.code); put('\\'.code) }
                c == '\n' -> { put('\\'.code); put('n'.code) }
                c == '\r' -> { put('\\'.code); put('r'.code) }
                c == '\t' -> { put('\\'.code); put('t'.code) }
                c == '\b' -> { put('\\'.code); put('b'.code) }
                c == '\u000C' -> { put('\\'.code); put('f'.code) }
                c.code < 0x20 -> {
                    put('\\'.code); put('u'.code); put('0'.code); put('0'.code)
                    put(HEX[c.code shr 4].code); put(HEX[c.code and 0xF].code)
                }
                c.code < 0x80 -> put(c.code)
                c.code < 0x800 -> { put(0xC0 or (c.code shr 6)); put(0x80 or (c.code and 0x3F)) }
                c.isHighSurrogate() && i + 1 < value.size && value[i + 1].isLowSurrogate() -> {
                    val cp = Character.toCodePoint(c, value[i + 1])
                    i++
                    put(0xF0 or (cp shr 18)); put(0x80 or ((cp shr 12) and 0x3F))
                    put(0x80 or ((cp shr 6) and 0x3F)); put(0x80 or (cp and 0x3F))
                }
                c.isSurrogate() -> throw VaultError.InvalidSecret() // refused earlier; never encode a lone surrogate
                else -> { put(0xE0 or (c.code shr 12)); put(0x80 or ((c.code shr 6) and 0x3F)); put(0x80 or (c.code and 0x3F)) }
            }
            i++
        }
        put('"'.code)
        return o - offset
    }

    fun decode(bytes: ByteArray): VaultSecret {
        val parser = Parser(bytes)
        var mnemonic: CharArray? = null
        var passphrase: CharArray? = null
        try {
            parser.skipSpace()
            parser.expect('{')
            parser.skipSpace()
            if (parser.peek() != '}'.code) {
                while (true) {
                    parser.skipSpace()
                    val key = parser.string()
                    try {
                        parser.skipSpace()
                        parser.expect(':')
                        parser.skipSpace()
                        val value = parser.string()
                        when {
                            key.contentEquals(MNEMONIC.toCharArray()) && mnemonic == null -> mnemonic = value
                            key.contentEquals(PASSPHRASE.toCharArray()) && passphrase == null -> passphrase = value
                            else -> {
                                value.wipe()
                                throw VaultError.AuthenticationFailed()
                            }
                        }
                    } finally {
                        key.wipe()
                    }
                    parser.skipSpace()
                    if (parser.peek() == ','.code) {
                        parser.advance()
                        continue
                    }
                    break
                }
            }
            parser.expect('}')
            parser.skipSpace()
            if (!parser.atEnd() || mnemonic == null || passphrase == null) throw VaultError.AuthenticationFailed()
            return VaultSecret(SecretText.wrap(mnemonic), SecretText.wrap(passphrase))
        } catch (e: Throwable) {
            mnemonic?.wipe()
            passphrase?.wipe()
            throw if (e is VaultError) e else VaultError.AuthenticationFailed()
        }
    }

    private class Parser(private val bytes: ByteArray) {
        private var position = 0

        fun atEnd() = position >= bytes.size
        fun peek(): Int = if (atEnd()) -1 else bytes[position].toInt() and 0xFF
        fun advance() {
            position++
        }

        fun skipSpace() {
            while (!atEnd() && peek().let { it == 0x20 || it == 0x09 || it == 0x0A || it == 0x0D }) position++
        }

        fun expect(c: Char) {
            if (peek() != c.code) throw VaultError.AuthenticationFailed()
            position++
        }

        private fun next(): Int {
            if (atEnd()) throw VaultError.AuthenticationFailed()
            return bytes[position++].toInt() and 0xFF
        }

        private fun continuation(): Int {
            val b = next()
            if (b and 0xC0 != 0x80) throw VaultError.AuthenticationFailed()
            return b and 0x3F
        }

        /** A JSON string as UTF-16 code units, decoded straight from the UTF-8 bytes into a wipeable array. */
        fun string(): CharArray {
            expect('"')
            val scratch = CharArray(bytes.size - position) // a JSON string never has more UTF-16 units than bytes
            var n = 0
            try {
                while (true) {
                    val b = next()
                    when {
                        b == '"'.code -> return scratch.copyOf(n)
                        b == '\\'.code -> when (val e = next()) {
                            '"'.code, '\\'.code, '/'.code -> scratch[n++] = e.toChar()
                            'b'.code -> scratch[n++] = '\b'
                            'f'.code -> scratch[n++] = '\u000C'
                            'n'.code -> scratch[n++] = '\n'
                            'r'.code -> scratch[n++] = '\r'
                            't'.code -> scratch[n++] = '\t'
                            'u'.code -> {
                                var v = 0
                                repeat(4) {
                                    val d = Character.digit(next(), 16)
                                    if (d < 0) throw VaultError.AuthenticationFailed()
                                    v = v shl 4 or d
                                }
                                scratch[n++] = v.toChar()
                            }
                            else -> throw VaultError.AuthenticationFailed()
                        }
                        b < 0x20 -> throw VaultError.AuthenticationFailed()
                        b < 0x80 -> scratch[n++] = b.toChar()
                        b and 0xE0 == 0xC0 -> {
                            val cp = (b and 0x1F shl 6) or continuation()
                            if (cp < 0x80) throw VaultError.AuthenticationFailed()
                            scratch[n++] = cp.toChar()
                        }
                        b and 0xF0 == 0xE0 -> {
                            val cp = (b and 0x0F shl 12) or (continuation() shl 6) or continuation()
                            if (cp < 0x800 || cp in 0xD800..0xDFFF) throw VaultError.AuthenticationFailed()
                            scratch[n++] = cp.toChar()
                        }
                        b and 0xF8 == 0xF0 -> {
                            val cp = (b and 0x07 shl 18) or (continuation() shl 12) or (continuation() shl 6) or continuation()
                            if (cp !in 0x10000..0x10FFFF) throw VaultError.AuthenticationFailed()
                            scratch[n++] = Character.highSurrogate(cp)
                            scratch[n++] = Character.lowSurrogate(cp)
                        }
                        else -> throw VaultError.AuthenticationFailed()
                    }
                }
            } finally {
                scratch.wipe()
            }
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
