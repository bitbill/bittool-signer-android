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
 *
 * Lost key (QA round 3 activity-keyboard-07, the harness side of QA L1): unlike a wallet key, an absent journal key
 * file is normal (a run that has not signed yet), so absence alone proves nothing. A key is provably lost only while
 * the loss record `journal.key.lost` that [loseKey] writes sits next to a key file that is absent (or not a key) — the
 * fixture's stand-in for `KeystoreLossProof` — and [discardLostKey] then answers true, as the Keystore cipher does after
 * removing the screen lock. The next [seal] creates a new key, which ends the loss. A journal no key opens while a key is present (or no
 * loss was recorded, `bts.uitest.unreadableJournal`) stays unreadable.
 */
internal class FixtureJournalCipher(
    private val keyFile: File,
    private val random: SecureRandom = SecureRandom(),
) : JournalCipher {
    private val lock = Any()

    /** [loseKey]'s record that the key in [keyFile] is gone for good. */
    private val lossRecord = lossRecord(keyFile)

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

    /**
     * Proven only by [loseKey]'s record next to a key that does not read (a plain absent key is a run that never
     * sealed). As the Keystore cipher removes an invalidated entry, what is left of the key file is removed, so the next
     * [seal] creates a new key.
     */
    override fun discardLostKey(): Boolean = synchronized(lock) {
        try {
            if (!lossRecord.isFile || read() != null) return@synchronized false
            if (!AtomicFiles.delete(keyFile)) return@synchronized false
            keyWorked = false
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * The key, created when the file is absent and no key has worked in this process, or one did but its loss is
     * recorded (the caller holds [lock]). A new key ends a recorded loss.
     */
    private fun keyForSealing(): ByteArray {
        read()?.let { return it }
        if (keyFile.exists() || (keyWorked && !lossRecord.isFile)) throw VaultError.DeviceKeyUnavailable()
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            AtomicFiles.write(keyFile, key)
        } catch (e: IOException) {
            key.wipe()
            throw VaultError.DeviceKeyUnavailable(e)
        }
        // The new key works: nothing is lost any more. A record left behind is harmless (the key is present).
        AtomicFiles.delete(lossRecord)
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

        /**
         * `bts.uitest.unreadableJournal` (QA round 2, activity tour): replaces the run's sealed journal [journal]
         * (`<run root>/receipts.bin`, created if absent) with a well-formed envelope that no key opens (`"BTSR"` ‖ 0x01,
         * a zero IV and a zero tag around no ciphertext), the state of a journal whose key is gone. The store then reads
         * it as unreadable ("Activity Unavailable" without "Clear Activity", DECISIONS §10), and since an unreadable
         * journal is never overwritten, later launches of the run keep it. The journal key is left as it is. Call
         * before anything opens the run's storage.
         */
        fun makeUnreadable(journal: File) {
            AtomicFiles.write(journal, HEADER + ByteArray(IV_BYTES) + ByteArray(TAG_BYTES))
        }

        /**
         * `bts.uitest.loseJournalKey` (QA round 3 activity-keyboard-07): what removing the screen lock does to the
         * Keystore journal key, for the run whose key is [keyFile] and whose journal is [journal]: the key is deleted
         * and its loss recorded (`journal.key.lost`), so [discardLostKey] proves it. A run without a journal gets one the
         * lost key sealed (as [makeUnreadable]: no key opens it), so the store always meets a journal it can never read
         * again; an existing journal is kept, its records are what the loss takes. The store then starts the journal
         * again and Activity shows "Activity Started Again" until "OK" (`SignerState.activityRestarted`); the next
         * signature seals under a new key. Every launch with the switch loses the key again. Call before anything opens
         * the run's storage.
         */
        fun loseKey(keyFile: File, journal: File) {
            if (!journal.exists()) makeUnreadable(journal)
            AtomicFiles.write(lossRecord(keyFile), ByteArray(0))
            if (!AtomicFiles.delete(keyFile)) throw IOException("The journal key could not be deleted")
        }

        /** The loss record next to [keyFile] (`journal.key.lost`). */
        private fun lossRecord(keyFile: File) = File(keyFile.parentFile, keyFile.name + LOSS_SUFFIX)

        private const val LOSS_SUFFIX = ".lost"

        /** `"BTSR"` + format version 1, as `KeystoreJournalCipher`. */
        private val HEADER = byteArrayOf('B'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), 0x01)
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val OVERHEAD = 5 + IV_BYTES + TAG_BYTES
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
