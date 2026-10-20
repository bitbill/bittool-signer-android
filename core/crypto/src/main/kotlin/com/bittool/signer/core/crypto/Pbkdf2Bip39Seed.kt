package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.internal.hasUnpairedSurrogate
import com.bittool.signer.core.crypto.kdf.Pbkdf2
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.util.Graphemes
import com.bittool.signer.core.model.util.Hex
import com.bittool.signer.core.model.util.isUnicodeWhiteSpace
import com.bittool.signer.core.model.util.utf8Bytes
import com.bittool.signer.core.model.util.wipe
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [Bip39Seed] — port of iOS `BIP39Seed.hex` (`Core/BIP39Seed.swift:21-48`):
 * `phrase = join(" ", split(lowercased(NFKD(mnemonic)), on: whitespace))`, non-empty and at most
 * [MAXIMUM_PHRASE_BYTES] UTF-8 bytes (so 24 Korean words, 815 bytes, fit: audit BTS-04); the passphrase at most 1,024
 * UTF-8 bytes as typed; then `PBKDF2-HMAC-SHA512(UTF-8(phrase), UTF-8(NFKD("mnemonic" + passphrase)), 2048, 64)`.
 * Violations are [VaultError.InvalidSecret], a platform failure [VaultError.KeyDerivationFailure].
 *
 * Secret hygiene: ASCII input (every English phrase, most passphrases) is normalised character by character straight
 * into byte arrays, so no `String` copy exists; other text needs `java.text.Normalizer`, whose `String` results cannot
 * be wiped — the same limitation iOS documents for Swift strings (vault-crypto.md §14). Every byte array is wiped.
 * Text with an unpaired UTF-16 surrogate (impossible in Swift) is refused rather than silently encoded as `?`.
 */
@Singleton
class Pbkdf2Bip39Seed @Inject constructor() : Bip39Seed {
    override fun hex(mnemonic: SecretText, passphrase: SecretText): CharArray {
        val phraseChars = mnemonic.chars()
        val passphraseChars = passphrase.chars()
        if (hasUnpairedSurrogate(phraseChars) || hasUnpairedSurrogate(passphraseChars)) throw VaultError.InvalidSecret()
        if (passphrase.utf8Size() > MAXIMUM_PASSPHRASE_BYTES) throw VaultError.InvalidSecret()
        val password = normalizedPhraseBytes(phraseChars)
        try {
            if (password.isEmpty() || password.size > MAXIMUM_PHRASE_BYTES) throw VaultError.InvalidSecret()
            val salt = saltBytes(passphraseChars)
            try {
                val seed = try {
                    Pbkdf2.hmacSha512(password, salt, ITERATIONS, SEED_BYTES)
                } catch (e: GeneralSecurityException) {
                    throw VaultError.KeyDerivationFailure(e)
                }
                try {
                    return Hex.encodeToChars(seed)
                } finally {
                    seed.wipe()
                }
            } finally {
                salt.wipe()
            }
        } finally {
            password.wipe()
        }
    }

    companion object {
        /** iOS `BIP39Seed.maximumPhraseBytes`: 24 × 33-byte Korean words + 23 spaces = 815, rounded up. */
        const val MAXIMUM_PHRASE_BYTES = 1_024
        private const val MAXIMUM_PASSPHRASE_BYTES = 1_024
        private const val ITERATIONS = 2_048
        private const val SEED_BYTES = 64
        private val SALT_PREFIX = "mnemonic".toCharArray()

        /** UTF-8 of `join(" ", split(lowercase(NFKD(text)), whitespace))`. */
        internal fun normalizedPhraseBytes(text: CharArray): ByteArray {
            if (text.all { it.code < 0x80 }) {
                // ASCII: NFKD is the identity and lowercasing is A–Z → a–z; split on the ASCII White_Space set.
                val out = ByteArray(text.size)
                var length = 0
                var pendingSpace = false
                for (c in text) {
                    if (isUnicodeWhiteSpace(c.code)) {
                        pendingSpace = length > 0
                        continue
                    }
                    if (pendingSpace) {
                        out[length++] = ' '.code.toByte()
                        pendingSpace = false
                    }
                    out[length++] = (if (c in 'A'..'Z') c + 32 else c).code.toByte()
                }
                return out.copyOf(length).also { out.wipe() }
            }
            val lowered = Normalizer.normalize(CharBuffer.wrap(text), Normalizer.Form.NFKD).lowercase()
            // Swift splits on Characters whose first scalar is White_Space, so a cluster such as " ́" is one
            // separator as a whole; runs of separators collapse into one space and none lead or trail.
            val joined = StringBuilder(lowered.length)
            var pendingSpace = false
            for (cluster in Graphemes.split(lowered)) {
                if (isUnicodeWhiteSpace(cluster.codePointAt(0))) {
                    pendingSpace = joined.isNotEmpty()
                    continue
                }
                if (pendingSpace) {
                    joined.append(' ')
                    pendingSpace = false
                }
                joined.append(cluster)
            }
            val chars = CharArray(joined.length).also { joined.toCharArray(it) }
            joined.setLength(0)
            try {
                return utf8Bytes(chars)
            } finally {
                chars.wipe()
            }
        }

        /** UTF-8 of `NFKD("mnemonic" + passphrase)`. */
        internal fun saltBytes(passphrase: CharArray): ByteArray {
            val combined = CharArray(SALT_PREFIX.size + passphrase.size)
            try {
                SALT_PREFIX.copyInto(combined)
                passphrase.copyInto(combined, SALT_PREFIX.size)
                if (combined.all { it.code < 0x80 }) return utf8Bytes(combined)
                val normalized = Normalizer.normalize(CharBuffer.wrap(combined), Normalizer.Form.NFKD).toCharArray()
                try {
                    return utf8Bytes(normalized)
                } finally {
                    normalized.wipe()
                }
            } finally {
                combined.wipe()
            }
        }
    }
}
