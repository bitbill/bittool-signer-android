package com.bittool.signer.core.crypto.vault

import com.bittool.signer.core.crypto.SecureRandomSource
import com.bittool.signer.core.crypto.kdf.Pbkdf2
import com.bittool.signer.core.model.util.wipe
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The vault's inner (password) layer (vault-crypto.md §6.4–§6.5, §17.7; iOS `VaultCrypto.deriveKey/encrypt/decrypt`):
 * `PBKDF2-HMAC-SHA256(UTF-8(password), salt, iterations) → 32 bytes → AES-256-GCM`, combined layout
 * `nonce(12) ‖ ciphertext ‖ tag(16)` with a fresh random nonce, AAD [VaultFormat.passwordAad]. Software AES-GCM
 * (Conscrypt on Android) with a new `Cipher` per operation; the derived key array is wiped (the `SecretKeySpec` clone
 * cannot be, like iOS `SymmetricKey`).
 */
internal class PasswordLayer(private val random: SecureRandomSource) {
    /** Derives the key; [checkpoint] lets a cancelled caller stop the Kotlin fallback early. Caller wipes the result. */
    fun deriveKey(password: ByteArray, salt: ByteArray, iterations: Int, checkpoint: () -> Unit): ByteArray = try {
        Pbkdf2.hmacSha256(password, salt, iterations, KEY_BYTES, checkpoint)
    } catch (e: IllegalStateException) {
        throw VaultError.KeyDerivationFailure(e)
    }

    /** Seals [cleartext] under [key]; any cipher failure is [VaultError.EncryptionFailure]. */
    fun seal(key: ByteArray, cleartext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = random.bytes(NONCE_BYTES)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            val body = cipher.doFinal(cleartext)
            return nonce + body
        } catch (e: GeneralSecurityException) {
            throw VaultError.EncryptionFailure(e)
        }
    }

    /** Opens a combined box; a bad tag, a short box or any cipher failure is [VaultError.AuthenticationFailed]. */
    fun open(key: ByteArray, combined: ByteArray, aad: ByteArray): ByteArray {
        if (combined.size < NONCE_BYTES + TAG_BITS / 8) throw VaultError.AuthenticationFailed()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, combined, 0, NONCE_BYTES))
            cipher.updateAAD(aad)
            return cipher.doFinal(combined, NONCE_BYTES, combined.size - NONCE_BYTES)
        } catch (_: AEADBadTagException) {
            throw VaultError.AuthenticationFailed()
        } catch (_: GeneralSecurityException) {
            throw VaultError.AuthenticationFailed()
        }
    }

    companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** Runs [block] with [key] and wipes it afterwards. */
        inline fun <R> withKey(key: ByteArray, block: (ByteArray) -> R): R = try {
            block(key)
        } finally {
            key.wipe()
        }
    }
}
