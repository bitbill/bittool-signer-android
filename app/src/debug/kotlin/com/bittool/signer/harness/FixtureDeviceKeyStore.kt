package com.bittool.signer.harness

import com.bittool.signer.core.common.storage.AtomicFiles
import com.bittool.signer.core.common.storage.InvalidFileException
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
 *
 * Lost keys (backlog B24): a file cannot "fail to answer" the way a Keystore binder call can, so a wallet key whose
 * file is gone (or is not a key) is provably lost ([isKeyLost]), which lets e2e reach the lost-key removal (B9). The
 * harness switch `bts.uitest.loseDeviceKeys=true` deletes the run's wallet keys ([loseWalletKeys]) before anything is
 * injected; launched again with the same run id, every wallet of the run then has a lost device key.
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

    /** Lost = no key file, or one that is not a key; a read error proves nothing (false). Never throws. */
    override fun isKeyLost(id: WalletId): Boolean = synchronized(lock) {
        try {
            val bytes = AtomicFiles.readBounded(keyFile(id), KEY_BYTES)
            (bytes == null || bytes.size != KEY_BYTES).also { bytes?.wipe() }
        } catch (_: InvalidFileException) {
            true
        } catch (_: IOException) {
            false
        }
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

    private fun keyFile(id: WalletId) = File(directory, "$id$KEY_SUFFIX")

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

    companion object {
        /** The run's key directory under its root (`<run root>/FixtureKeys/`). */
        const val DIRECTORY = "FixtureKeys"

        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC = "HmacSHA256"
        private const val PUBLIC_DATA_KEY_FILE = "public-data.key"
        private const val KEY_SUFFIX = ".key"

        /**
         * `bts.uitest.loseDeviceKeys` (B24): deletes every wallet key (`<wallet id>.key`) in [directory], keeping the
         * public-data key and the journal key, as if Keystore had lost the wallets' device keys. Returns how many it
         * deleted. Call before anything opens the run's storage.
         */
        fun loseWalletKeys(directory: File): Int {
            val walletKeys = directory.listFiles { file ->
                file.isFile && file.name.endsWith(KEY_SUFFIX) && WalletId.parse(file.name.removeSuffix(KEY_SUFFIX)) != null
            }
            return walletKeys.orEmpty().count { it.delete() }
        }
    }
}
