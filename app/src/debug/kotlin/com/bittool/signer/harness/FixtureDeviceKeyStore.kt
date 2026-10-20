package com.bittool.signer.harness

import com.bittool.signer.core.common.storage.AtomicFiles
import com.bittool.signer.core.crypto.vault.DeviceKeyStore
import com.bittool.signer.core.crypto.vault.PublicDataKey
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.util.constantTimeEquals
import com.bittool.signer.core.model.util.wipe
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The UI-test run's device keys (gaps.md G3; iOS `SimulatorFixtureDeviceKeys`, SimulatorUITestContext.swift:48-57):
 * software AES-256 keys in files under `<run root>/FixtureKeys/`, so a later cold launch with the same run id opens the
 * same wallets (the App Lock flow G1.17), plus the public-data HMAC key in the same directory. Same contract as the
 * Keystore implementation (`iv(12) ‖ ciphertext ‖ tag(16)`, a key is never replaced, a missing key is
 * [VaultError.DeviceKeyUnavailable], a bad tag [VaultError.AuthenticationFailed]). The emulator of the e2e suite need
 * not have a screen lock, so [isDeviceSecure] is true. Debug builds only, and only while a run is active: production
 * keys stay in Android Keystore (`:core:crypto` instrumented tests cover that).
 */
internal class FixtureDeviceKeyStore(
    private val directory: File,
    private val random: SecureRandom = SecureRandom(),
) : DeviceKeyStore {
    private val lock = Any()

    override fun isDeviceSecure(): Boolean = true

    override fun create(id: WalletId): Unit = synchronized(lock) {
        val file = keyFile(id)
        if (file.exists()) throw VaultError.DeviceKeyUnavailable()
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            AtomicFiles.write(file, key)
        } catch (e: IOException) {
            throw VaultError.DeviceKeyUnavailable(e)
        } finally {
            key.wipe()
        }
    }

    override fun seal(id: WalletId, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val key = read(keyFile(id)) ?: throw VaultError.DeviceKeyUnavailable()
        try {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            return iv + cipher.doFinal(plaintext)
        } catch (e: GeneralSecurityException) {
            throw VaultError.DeviceKeyUnavailable(e)
        } finally {
            key.wipe()
        }
    }

    override fun open(id: WalletId, combined: ByteArray, aad: ByteArray): ByteArray {
        if (combined.size < IV_BYTES + TAG_BITS / 8) throw VaultError.AuthenticationFailed()
        val key = read(keyFile(id)) ?: throw VaultError.DeviceKeyUnavailable()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, combined, 0, IV_BYTES))
            cipher.updateAAD(aad)
            return cipher.doFinal(combined, IV_BYTES, combined.size - IV_BYTES)
        } catch (_: GeneralSecurityException) {
            throw VaultError.AuthenticationFailed()
        } finally {
            key.wipe()
        }
    }

    override fun delete(id: WalletId): Unit = synchronized(lock) {
        if (!AtomicFiles.delete(keyFile(id))) throw VaultError.DeviceKeyUnavailable()
    }

    override fun publicDataKey(createIfMissing: Boolean): PublicDataKey? = synchronized(lock) {
        val file = File(directory, PUBLIC_DATA_KEY_FILE)
        read(file)?.let { return FileHmacKey(it) }
        if (!createIfMissing) return null
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            AtomicFiles.write(file, key)
        } catch (e: IOException) {
            key.wipe()
            throw VaultError.DeviceKeyUnavailable(e)
        }
        FileHmacKey(key)
    }

    private fun keyFile(id: WalletId) = File(directory, "$id.key")

    private fun read(file: File): ByteArray? = synchronized(lock) {
        try {
            AtomicFiles.readBounded(file, KEY_BYTES)?.takeIf { it.size == KEY_BYTES }
        } catch (e: IOException) {
            throw VaultError.DeviceKeyUnavailable(e)
        }
    }

    override fun toString(): String = "FixtureDeviceKeyStore(<ui-test run>)"

    /** The run's public-data key; holds its own copy of the key bytes for the process lifetime (test data only). */
    private class FileHmacKey(private val key: ByteArray) : PublicDataKey {
        override fun mac(message: ByteArray): ByteArray = Mac.getInstance(HMAC).run {
            init(SecretKeySpec(key, HMAC))
            doFinal(message)
        }

        override fun verify(message: ByteArray, tag: ByteArray): Boolean = constantTimeEquals(mac(message), tag)

        override fun toString(): String = "PublicDataKey(<ui-test run>)"
    }

    private companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val HMAC = "HmacSHA256"
        const val PUBLIC_DATA_KEY_FILE = "public-data.key"
    }
}
