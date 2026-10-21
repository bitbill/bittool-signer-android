package com.bittool.signer.harness

import com.bittool.signer.core.common.storage.AtomicFiles
import com.bittool.signer.core.crypto.vault.JournalCipher
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.util.wipe
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The UI-test run's Activity journal cipher (gaps.md G3; QA round 1 sign-01). The production `KeystoreJournalCipher`
 * generates its key with `setUnlockedDeviceRequired(true)`, which Keystore refuses on an emulator without a screen lock
 * ("User ECDH key missing"), so in a harness run — whose wallets already use [FixtureDeviceKeyStore] — every receipt
 * write failed and Activity stayed empty. This is the same envelope with a software AES-256 key in a file of the run
 * (`<run root>/FixtureKeys/journal.key`), so a cold launch with the same run id reads the same journal:
 * `"BTSR" ‖ 0x01 ‖ iv(12) ‖ ciphertext ‖ tag(16)`, the 5 header bytes as AAD.
 *
 * Same contract as the Keystore cipher: the key is created by the first [seal] and never replaced (a key that worked in
 * this process and then reads as missing fails the seal with [VaultError.DeviceKeyUnavailable] instead of orphaning the
 * journal); [open] reports a bad tag, an unknown magic or version, a truncated file and a missing key as
 * [VaultError.AuthenticationFailed]. Debug builds only, and only while a run is active (`DebugUiTestModule`); the
 * release check `check<Variant>HasNoHarness` fails a release that still contains it.
 */
internal class FixtureJournalCipher(
    private val keyFile: File,
    private val random: SecureRandom = SecureRandom(),
) : JournalCipher {
    private val lock = Any()

    @Volatile private var keyWorked = false

    override fun seal(plaintext: ByteArray): ByteArray = synchronized(lock) {
        val key = keyForSealing()
        try {
            val iv = ByteArray(IV_BYTES).also(random::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv))
            cipher.updateAAD(HEADER)
            val body = cipher.doFinal(plaintext)
            keyWorked = true
            HEADER + iv + body
        } catch (e: GeneralSecurityException) {
            throw VaultError.EncryptionFailure(e)
        } finally {
            key.wipe()
        }
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < OVERHEAD || !sealed.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            throw VaultError.AuthenticationFailed()
        }
        val key = synchronized(lock) { read() } ?: throw VaultError.AuthenticationFailed()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, sealed, HEADER.size, IV_BYTES))
            cipher.updateAAD(HEADER)
            val plaintext = cipher.doFinal(sealed, HEADER.size + IV_BYTES, sealed.size - HEADER.size - IV_BYTES)
            keyWorked = true
            return plaintext
        } catch (_: GeneralSecurityException) {
            throw VaultError.AuthenticationFailed()
        } finally {
            key.wipe()
        }
    }

    /** The key, created when the file is absent and no key has worked in this process (the caller holds [lock]). */
    private fun keyForSealing(): ByteArray {
        read()?.let { return it }
        if (keyWorked || keyFile.exists()) throw VaultError.DeviceKeyUnavailable()
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            AtomicFiles.write(keyFile, key)
        } catch (e: IOException) {
            key.wipe()
            throw VaultError.DeviceKeyUnavailable(e)
        }
        return key
    }

    /** The key bytes, or null when the file is absent or not a key. */
    private fun read(): ByteArray? = try {
        AtomicFiles.readBounded(keyFile, KEY_BYTES)?.takeIf { it.size == KEY_BYTES }
    } catch (_: IOException) {
        null
    }

    override fun toString(): String = "FixtureJournalCipher(<ui-test run>)"

    companion object {
        /** The key's file name in the run's `FixtureKeys/` directory (next to [FixtureDeviceKeyStore]'s keys). */
        const val KEY_FILE = "journal.key"

        /** `"BTSR"` + format version 1, as `KeystoreJournalCipher`. */
        private val HEADER = byteArrayOf('B'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), 0x01)
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val OVERHEAD = 5 + IV_BYTES + TAG_BYTES
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
