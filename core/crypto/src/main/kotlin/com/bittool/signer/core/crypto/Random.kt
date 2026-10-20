package com.bittool.signer.core.crypto

import com.bittool.signer.core.model.SecretText

/**
 * The one CSPRNG of the app (vault-crypto.md §3): salts, nonces, entropy and backup-check shuffles all come from
 * `java.security.SecureRandom`. The engine's `crypto.getRandomValues` is fed by the engine host's own native source.
 * Implemented by W1-crypto (`@Singleton`, thread-safe).
 */
interface SecureRandomSource {
    /** [count] random bytes, `1…65_536` else `VaultError.RandomFailure`; any provider failure is `RandomFailure` too. */
    fun bytes(count: Int): ByteArray

    /**
     * Entropy for the engine's `mnemonic` operation: `wordCount / 3 * 4` random bytes (12→16 … 24→32) as lowercase hex.
     * [wordCount] must be 12, 15, 18, 21 or 24, else `VaultError.InvalidSecret`. The caller wipes the result.
     */
    fun entropyHex(wordCount: Int): CharArray

    /** A `kotlin.random.Random` view of this source (for `BackupVerification.create`). */
    fun asKotlinRandom(): kotlin.random.Random
}

/**
 * BIP39 seed stretching in native code (vault-crypto.md §13.7, iOS `Core/BIP39Seed.swift`): the engine receives this
 * seed, never the phrase. `phrase = join(" ", split_on_white_space(lowercase_ROOT(NFKD(mnemonic))))`, non-empty and
 * ≤ 1,024 UTF-8 bytes; passphrase ≤ 1,024 UTF-8 bytes (raw, before NFKD) — else `VaultError.InvalidSecret`.
 * `seed = PBKDF2-HMAC-SHA512(password = UTF-8(phrase), salt = UTF-8(NFKD("mnemonic" + passphrase)), 2048, 64)`,
 * implemented over raw bytes with `Mac("HmacSHA512")` (never `SecretKeyFactory` with `char[]`). Every intermediate
 * buffer is wiped. Vectors: "abandon ×11 about" + "TREZOR" → `c55257c3…3b04`; + "" → `5eb00bbd…38e4`.
 */
interface Bip39Seed {
    /** 128 lowercase hex characters; the caller wipes the result after building the engine request. */
    fun hex(mnemonic: SecretText, passphrase: SecretText): CharArray
}
