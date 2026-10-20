package com.bittool.signer.core.crypto.vault

import android.os.Build
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [JournalCipher] for `receipts.bin` (DECISIONS.md §9.3, gaps.md G7.2, activity-settings.md §14.2): AES-256-GCM under
 * the non-exportable Keystore key [SignerFiles.RECEIPTS_KEY_ALIAS] (TEE: the journal can be 2 MiB, too much for
 * StrongBox's throughput; `setUnlockedDeviceRequired(true)` on API 28+, the Android equivalent of iOS
 * `completeFileProtection`). Layout `"BTSR" ‖ 0x01 ‖ iv(12) ‖ ciphertext ‖ tag(16)`; the 5 header bytes are the AAD.
 *
 * The key is created by the first [seal] when the alias is absent and is never replaced: an entry it cannot use, or a
 * key that reads as missing after it worked in this process (API 26–30 binder flakiness), fails the seal with
 * [VaultError.DeviceKeyUnavailable] instead of silently orphaning the existing journal. [open] reports a bad tag,
 * an unknown magic or version, a truncated file and a lost key as [VaultError.AuthenticationFailed]; the store then
 * shows "The local activity record could not be read." and never overwrites the file silently.
 */
@Singleton
class KeystoreJournalCipher internal constructor(private val policy: KeystoreKeyPolicy) : JournalCipher {
    @Inject constructor() : this(KeystoreKeyPolicy(strongBox = false, unlockedDeviceRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P))

    private val lock = Any()

    @Volatile private var keyWorked = false

    override fun seal(plaintext: ByteArray): ByteArray = synchronized(lock) {
        val key = keyForSealing()
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(HEADER)
            val body = cipher.doFinal(plaintext)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) throw VaultError.EncryptionFailure()
            keyWorked = true
            HEADER + iv + body
        } catch (e: VaultError) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw VaultError.EncryptionFailure(e)
        } catch (e: ProviderException) {
            throw VaultError.EncryptionFailure(e)
        }
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < OVERHEAD || !sealed.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            throw VaultError.AuthenticationFailed()
        }
        try {
            val key = Keystore.secretKey(Keystore.open(), ALIAS) ?: throw VaultError.AuthenticationFailed()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, HEADER.size, IV_BYTES))
            cipher.updateAAD(HEADER)
            val plaintext = cipher.doFinal(sealed, HEADER.size + IV_BYTES, sealed.size - HEADER.size - IV_BYTES)
            keyWorked = true
            return plaintext
        } catch (e: VaultError) {
            throw e
        } catch (_: GeneralSecurityException) {
            throw VaultError.AuthenticationFailed()
        } catch (_: ProviderException) {
            throw VaultError.AuthenticationFailed()
        } catch (_: IOException) {
            throw VaultError.AuthenticationFailed()
        }
    }

    private fun keyForSealing(): SecretKey = try {
        val store = Keystore.open()
        Keystore.secretKey(store, ALIAS) ?: run {
            if (keyWorked || Keystore.contains(store, ALIAS)) throw VaultError.DeviceKeyUnavailable()
            Keystore.generateAes(store, ALIAS, policy, allowStrongBox = false)
        }
    } catch (e: VaultError) {
        throw e
    } catch (e: GeneralSecurityException) {
        throw VaultError.DeviceKeyUnavailable(e)
    } catch (e: ProviderException) {
        throw VaultError.DeviceKeyUnavailable(e)
    } catch (e: IOException) {
        throw VaultError.DeviceKeyUnavailable(e)
    }

    override fun toString(): String = "KeystoreJournalCipher"

    internal companion object {
        const val ALIAS = SignerFiles.RECEIPTS_KEY_ALIAS
        /** `"BTSR"` + format version 1. */
        val HEADER = byteArrayOf('B'.code.toByte(), 'T'.code.toByte(), 'S'.code.toByte(), 'R'.code.toByte(), 0x01)
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        /** 4 + 1 + 12 + 16 = 33, the margin `ReceiptRepository` adds to its read bound. */
        const val OVERHEAD = 5 + IV_BYTES + TAG_BYTES
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
