package com.bittool.signer.core.crypto.kdf

import com.bittool.signer.core.crypto.internal.wipe
import com.bittool.signer.core.model.util.wipe

/**
 * PBKDF2-HMAC-SHA256 (RFC 8018 §5.2) over exact bytes in pure Kotlin: the fallback of the vault's password KDF when
 * the native library cannot load, and the implementation JVM unit tests exercise ([Pbkdf2] dispatches; iOS
 * `VaultCrypto.deriveKey` = CommonCrypto `CCKeyDerivationPBKDF`, `Core/VaultCrypto.swift:125-149`).
 *
 * It mirrors the native loop: the HMAC inner/outer pad states are computed once, so each iteration is two
 * compressions on the 8-word chaining values in `IntArray`s (no byte conversion, no JNI hop, no allocation inside the
 * loop) — 415 ms for 600,000 iterations on the emulator, against ≈ 2.2 s for `SecretKeyFactory` and ≈ 5.7 s for a
 * pre-keyed `Mac` loop ([Pbkdf2] KDoc).
 *
 * Secret hygiene: the padded key, both pad states, every `U` and the accumulator are ours and are zeroed in
 * `finally`; the caller owns and wipes [password] and the returned key. Correctness is pinned by the spec vector
 * (`Test-Password1`, salt `00…1f` → `05b079f1…01d9`), the RFC 7914 §11 vectors and a cross-check against the JCA
 * implementation on random inputs.
 */
internal object Pbkdf2HmacSha256 {
    private const val HASH_BYTES = 32

    /**
     * Derives [keyLength] bytes. [checkpoint] runs every [CHECKPOINT_INTERVAL] iterations so a cancelled coroutine
     * stops early (an aborted unlock has already counted its attempt, like iOS); it must throw to abort.
     */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int,
        checkpoint: () -> Unit = {},
    ): ByteArray {
        require(iterations >= 1) { "iterations must be positive" }
        require(keyLength in 1..(HASH_BYTES * 1024)) { "unsupported key length" }
        val key = ByteArray(Sha256.BLOCK_BYTES)
        val inner = IntArray(8)
        val outer = IntArray(8)
        val sha = Sha256()
        val u = IntArray(8)
        val t = IntArray(8)
        val block = IntArray(16)
        val w = IntArray(64)
        val indexBytes = ByteArray(4)
        val out = ByteArray(keyLength)
        try {
            // K0: the key, hashed first when longer than one block, zero-padded to 64 bytes.
            if (password.size > Sha256.BLOCK_BYTES) {
                sha.reset()
                sha.update(password)
                sha.finish()
                Sha256.wordsToBytes(sha.state, key, 0)
            } else {
                password.copyInto(key)
            }
            padState(key, 0x36, sha, inner)
            padState(key, 0x5c, sha, outer)

            // The constant tail of a 32-byte message that follows one 64-byte pad block: 0x80, zeros, length 768 bits.
            block[8] = 0x80000000.toInt()
            block[15] = (Sha256.BLOCK_BYTES + HASH_BYTES) * 8

            val blocks = (keyLength + HASH_BYTES - 1) / HASH_BYTES
            for (blockIndex in 1..blocks) {
                // U1 = HMAC(P, S || INT(i)) through the streaming hash (the salt has any length).
                indexBytes[0] = (blockIndex ushr 24).toByte()
                indexBytes[1] = (blockIndex ushr 16).toByte()
                indexBytes[2] = (blockIndex ushr 8).toByte()
                indexBytes[3] = blockIndex.toByte()
                sha.resume(inner, Sha256.BLOCK_BYTES.toLong())
                sha.update(salt)
                sha.update(indexBytes)
                sha.finish()
                sha.state.copyInto(block, 0, 0, 8)
                Sha256.compress(outer, block, u, w)
                u.copyInto(t)

                // U2…Uc: each is HMAC(P, U(j-1)) = H(opad ‖ H(ipad ‖ U)), two compressions on words.
                for (j in 2..iterations) {
                    u.copyInto(block, 0, 0, 8)
                    Sha256.compress(inner, block, u, w)
                    u.copyInto(block, 0, 0, 8)
                    Sha256.compress(outer, block, u, w)
                    t[0] = t[0] xor u[0]
                    t[1] = t[1] xor u[1]
                    t[2] = t[2] xor u[2]
                    t[3] = t[3] xor u[3]
                    t[4] = t[4] xor u[4]
                    t[5] = t[5] xor u[5]
                    t[6] = t[6] xor u[6]
                    t[7] = t[7] xor u[7]
                    if (j % CHECKPOINT_INTERVAL == 0) checkpoint()
                }
                val offset = (blockIndex - 1) * HASH_BYTES
                val count = minOf(HASH_BYTES, keyLength - offset)
                val words = ByteArray(HASH_BYTES)
                Sha256.wordsToBytes(t, words, 0)
                words.copyInto(out, offset, 0, count)
                words.wipe()
            }
            return out
        } catch (e: Throwable) {
            out.wipe()
            throw e
        } finally {
            key.wipe()
            inner.wipe()
            outer.wipe()
            sha.wipe()
            u.wipe()
            t.wipe()
            block.wipe()
            w.wipe()
        }
    }

    /** The chaining value after compressing `K0 xor pad` (one block): the reusable HMAC half-state. */
    private fun padState(key: ByteArray, pad: Int, sha: Sha256, into: IntArray) {
        val padded = ByteArray(Sha256.BLOCK_BYTES) { (key[it].toInt() xor pad).toByte() }
        try {
            sha.reset()
            sha.update(padded)
            sha.state.copyInto(into)
        } finally {
            padded.wipe()
        }
    }

    /** How often [derive] calls its checkpoint (cheap: ~1.7 % of the loop count at 600,000 iterations). */
    const val CHECKPOINT_INTERVAL = 10_000
}
