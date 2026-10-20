package com.bittool.signer.core.crypto.kdf

import com.bittool.signer.core.model.util.wipe
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The two PBKDF2 uses of the app (vault-crypto.md §6.4, §13.7, §17.6), over exact bytes.
 *
 * **Vault password KDF, PBKDF2-HMAC-SHA256, 600,000 iterations.** Measured on the shared emulator (arm64, API 31,
 * debug build, `Pbkdf2BenchmarkTest`, median of 5 after a warm-up):
 *
 * | Candidate | 600,000 iterations |
 * |---|---|
 * | native C, ARMv8 SHA-2 instructions (production on ARMv8 CPUs) | 41 ms |
 * | native C, portable compression (production without the instructions) | 398 ms |
 * | word-level Kotlin [Pbkdf2HmacSha256] (fallback when the library cannot load; JVM tests) | 415 ms |
 * | `SecretKeyFactory("PBKDF2WithHmacSHA256")` (Bouncy Castle, pure Java, `char[]` input) | ≈ 2,200 ms |
 * | pre-keyed `Mac("HmacSHA256")` loop (Conscrypt; at full length the low-memory killer ended the process) | ≈ 5,670 ms |
 *
 * So [hmacSha256] uses [NativePbkdf2] when it loads and [Pbkdf2HmacSha256] otherwise; both are pinned to the same
 * vectors and cross-checked on the device. The iteration count is never lowered.
 *
 * **BIP39 seed, PBKDF2-HMAC-SHA512, 2,048 iterations** ([hmacSha512]): cheap, so it follows the `Bip39Seed` contract
 * literally: a pre-keyed `Mac("HmacSHA512")` over the raw bytes. `SecretKeySpec` and Conscrypt keep copies of the key
 * that cannot be wiped (vault-crypto.md §17.7 accepts this, like iOS `SymmetricKey`); our own buffers are wiped.
 */
internal object Pbkdf2 {
    /** Which implementation [hmacSha256] uses on this device: `native-armv8`, `native-portable` or `kotlin`. */
    val sha256Implementation: String by lazy {
        when {
            !NativePbkdf2.isAvailable -> "kotlin"
            NativePbkdf2.accelerated() == 1 -> "native-armv8"
            else -> "native-portable"
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 of [password] and [salt]. [checkpoint] is called periodically by the Kotlin fallback so a
     * cancelled coroutine can stop it (the native path finishes in well under a second). The caller wipes the result.
     */
    fun hmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int, checkpoint: () -> Unit = {}): ByteArray {
        require(iterations >= 1 && keyLength >= 1)
        if (NativePbkdf2.isAvailable && password.size <= NATIVE_MAX_PASSWORD && salt.size <= NATIVE_MAX_SALT && keyLength <= NATIVE_MAX_OUTPUT) {
            val out = ByteArray(keyLength)
            check(NativePbkdf2.pbkdf2HmacSha256(password, salt, iterations, out) == 0) { "native PBKDF2 refused its arguments" }
            return out
        }
        return Pbkdf2HmacSha256.derive(password, salt, iterations, keyLength, checkpoint)
    }

    /** PBKDF2-HMAC-SHA512 over raw bytes with a pre-keyed platform `Mac`; [password] must be non-empty. */
    fun hmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        require(password.isNotEmpty() && iterations >= 1 && keyLength >= 1)
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val hashLength = mac.macLength
        val out = ByteArray(keyLength)
        val u = ByteArray(hashLength)
        val t = ByteArray(hashLength)
        val index = ByteArray(4)
        try {
            val blocks = (keyLength + hashLength - 1) / hashLength
            for (block in 1..blocks) {
                index[0] = (block ushr 24).toByte()
                index[1] = (block ushr 16).toByte()
                index[2] = (block ushr 8).toByte()
                index[3] = block.toByte()
                mac.update(salt)
                mac.update(index)
                mac.doFinal(u, 0)
                u.copyInto(t)
                for (j in 2..iterations) {
                    mac.update(u)
                    mac.doFinal(u, 0)
                    for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
                }
                val offset = (block - 1) * hashLength
                t.copyInto(out, offset, 0, minOf(hashLength, keyLength - offset))
            }
            return out
        } catch (e: Throwable) {
            out.wipe()
            throw e
        } finally {
            u.wipe()
            t.wipe()
            mac.reset()
        }
    }

    // Mirrors MAX_PASSWORD / MAX_SALT / MAX_OUTPUT in pbkdf2_jni.c; larger inputs (never produced by the vault) use Kotlin.
    private const val NATIVE_MAX_PASSWORD = 4096
    private const val NATIVE_MAX_SALT = 1024
    private const val NATIVE_MAX_OUTPUT = 1024
}
