package com.bittool.signer.core.crypto.vault

import android.content.Context
import android.security.keystore.KeyInfo
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.util.constantTimeEquals
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.ProviderException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DeviceKeyStore] in the Android Keystore (vault-crypto.md §7, §17.3–§17.4; iOS `KeychainDeviceKeyStore`,
 * `Core/WalletVault.swift:15-59`, whose service/account become the alias `com.bittool.signer.vault.device-key.v1/<uuid>`).
 *
 * - Per-wallet keys: non-exportable AES-256-GCM, randomised encryption (Keystore picks the 12-byte IV), StrongBox when
 *   the device has it with a TEE retry, `setUnlockedDeviceRequired(true)` on API 28+, no user-authentication binding.
 *   Sealed boxes are `iv(12) ‖ ciphertext ‖ tag(16)`, the layout of CryptoKit's `.combined`.
 * - The public-data key: HMAC-SHA256 in the TEE under the fixed wallet id `0b175160-9a7e-4c1d-8000-00000000c0de`
 *   ("never collides with random wallet UUIDs"), never deleted.
 * - A key is generated only after `containsAlias` reported the alias absent, and never over an existing entry. A key
 *   that reads as missing for an existing wallet is [VaultError.DeviceKeyUnavailable], never a reason to create one
 *   (on API 26–30 a failed binder call can report a live key as missing); once the public-data key has been read in
 *   this process it is not created again either, unless that proof shows it gone. Only the proof of [isKeyLost],
 *   which needs evidence that Keystore answered, may conclude that a key is gone (QA backlog B9: removing such a
 *   wallet without its password, and creating the public-data key again for the wallet re-imported afterwards).
 * - A secure lock screen is required to create any key ([VaultError.DevicePasscodeRequired]); iOS's
 *   `WhenPasscodeSetThisDeviceOnly` equivalent is the vault's runtime gate (option 1 of §17.3).
 *
 * Thread-safe (one lock); the vault calls it under its own mutex anyway.
 */
@Singleton
class AndroidKeystoreDeviceKeyStore internal constructor(
    private val lockScreen: DeviceLockScreen,
    private val policy: KeystoreKeyPolicy,
) : DeviceKeyStore {
    @Inject constructor(@ApplicationContext context: Context) :
        this(DeviceLockScreen.of(context), KeystoreKeyPolicy.forDevice(context))

    private val lock = Any()

    @Volatile private var publicKeySeen = false

    override fun isDeviceSecure(): Boolean = lockScreen.isSecure()

    override fun create(id: WalletId) = synchronized(lock) {
        if (!isDeviceSecure()) throw VaultError.DevicePasscodeRequired()
        // The public-data key's fixed id is reserved; an AES key there would break every public file's MAC.
        if (id == PUBLIC_DATA_KEY_ID) throw VaultError.DeviceKeyUnavailable()
        val alias = alias(id)
        try {
            val store = Keystore.open()
            // Never replace: an existing alias belongs to another vault (or a public-data key) and stays as it is.
            if (Keystore.contains(store, alias)) throw VaultError.DeviceKeyUnavailable()
            Keystore.generateAes(store, alias, policy)
            Unit
        } catch (e: VaultError) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw VaultError.DeviceKeyUnavailable(e)
        } catch (e: ProviderException) {
            throw VaultError.DeviceKeyUnavailable(e)
        } catch (e: IOException) {
            throw VaultError.DeviceKeyUnavailable(e)
        }
    }

    override fun seal(id: WalletId, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val key = walletKey(id)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            val body = cipher.doFinal(plaintext)
            val iv = cipher.iv
            if (iv == null || iv.size != IV_BYTES) throw VaultError.DeviceKeyUnavailable()
            return iv + body
        } catch (e: VaultError) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw VaultError.DeviceKeyUnavailable(e)
        } catch (e: ProviderException) {
            throw VaultError.DeviceKeyUnavailable(e)
        }
    }

    override fun open(id: WalletId, combined: ByteArray, aad: ByteArray): ByteArray {
        if (combined.size < IV_BYTES + TAG_BYTES) throw VaultError.AuthenticationFailed()
        val key = walletKey(id)
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, combined, 0, IV_BYTES))
                updateAAD(aad)
            }
        } catch (e: GeneralSecurityException) {
            throw VaultError.DeviceKeyUnavailable(e) // includes KeyPermanentlyInvalidatedException
        } catch (e: ProviderException) {
            throw VaultError.DeviceKeyUnavailable(e)
        }
        try {
            return cipher.doFinal(combined, IV_BYTES, combined.size - IV_BYTES)
        } catch (_: AEADBadTagException) {
            throw VaultError.AuthenticationFailed()
        } catch (_: BadPaddingException) {
            throw VaultError.AuthenticationFailed()
        } catch (_: IllegalBlockSizeException) {
            throw VaultError.AuthenticationFailed()
        } catch (e: InvalidKeyException) {
            throw VaultError.DeviceKeyUnavailable(e)
        } catch (e: GeneralSecurityException) {
            throw VaultError.DeviceKeyUnavailable(e)
        } catch (e: ProviderException) {
            throw VaultError.DeviceKeyUnavailable(e)
        }
    }

    override fun delete(id: WalletId) = synchronized(lock) {
        // The public-data key is never deleted, not even with the last wallet (vault-crypto.md §7.2).
        if (id == PUBLIC_DATA_KEY_ID) throw VaultError.DeviceKeyUnavailable()
        val alias = alias(id)
        try {
            val store = Keystore.open()
            if (Keystore.contains(store, alias)) store.deleteEntry(alias)
        } catch (e: GeneralSecurityException) {
            throw VaultError.DeviceKeyUnavailable(e)
        } catch (e: ProviderException) {
            throw VaultError.DeviceKeyUnavailable(e)
        } catch (e: IOException) {
            throw VaultError.DeviceKeyUnavailable(e)
        }
    }

    /**
     * Provably lost (QA backlog B9): the shared [KeystoreLossProof] — [LOSS_CONFIRMATIONS] independent observations, each
     * on a freshly loaded Keystore, report the same loss: **absent** (a probe key generated for the observation is listed
     * before and after the wallet's alias reads as absent everywhere: a Keystore that failed to answer would lose the probe
     * too) or **invalidated** (`KeyPermanentlyInvalidatedException`). A usable key, any other refusal (a locked device, a
     * transient error) or a disagreement between observations is not a loss.
     */
    override fun isKeyLost(id: WalletId): Boolean = synchronized(lock) {
        if (id == PUBLIC_DATA_KEY_ID) return false
        KeystoreLossProof.provenLoss(alias(id)) != null
    }

    override fun publicDataKey(createIfMissing: Boolean): PublicDataKey? = synchronized(lock) {
        val alias = PUBLIC_DATA_ALIAS
        try {
            val store = Keystore.open()
            Keystore.secretKey(store, alias)?.takeIf { it.algorithm == HMAC }?.let {
                publicKeySeen = true
                return KeystorePublicDataKey(it)
            }
            if (!createIfMissing) return null
            // Never regenerate over an entry we cannot use (generateKey would silently replace it), and never because a
            // key this process already used now merely reads as missing (flaky Keystore): either would make every other
            // wallet's public file fail its MAC. A used key that Keystore *provably* lacks (the proof of isKeyLost) is
            // gone for good, so every public file already fails its MAC; it is created again, or a wallet re-imported
            // after the lost-key removal could not save its public file until the next launch (QA backlog B9).
            if (Keystore.contains(store, alias)) throw VaultError.DeviceKeyUnavailable()
            if (!isDeviceSecure()) throw VaultError.DevicePasscodeRequired()
            if (publicKeySeen && KeystoreLossProof.provenLoss(alias) != KeystoreLossProof.Loss.Absent) {
                throw VaultError.DeviceKeyUnavailable()
            }
            val key = Keystore.generateHmac(alias, policy)
            publicKeySeen = true
            KeystorePublicDataKey(key)
        } catch (e: VaultError) {
            if (createIfMissing) throw e else null
        } catch (e: GeneralSecurityException) {
            if (createIfMissing) throw VaultError.DeviceKeyUnavailable(e) else null
        } catch (e: ProviderException) {
            if (createIfMissing) throw VaultError.DeviceKeyUnavailable(e) else null
        } catch (e: IOException) {
            if (createIfMissing) throw VaultError.DeviceKeyUnavailable(e) else null
        }
    }

    /** The AES key of an existing wallet; absent or unreadable is [VaultError.DeviceKeyUnavailable]. */
    private fun walletKey(id: WalletId): SecretKey = try {
        Keystore.secretKey(Keystore.open(), alias(id))?.takeIf { it.algorithm == AES }
            ?: throw VaultError.DeviceKeyUnavailable()
    } catch (e: VaultError) {
        throw e
    } catch (e: GeneralSecurityException) {
        throw VaultError.DeviceKeyUnavailable(e)
    } catch (e: ProviderException) {
        throw VaultError.DeviceKeyUnavailable(e)
    } catch (e: IOException) {
        throw VaultError.DeviceKeyUnavailable(e)
    }

    /** Test hook: whether a key exists for [id] (instrumented tests check the alias guard and deletion). */
    internal fun containsKey(id: WalletId): Boolean = Keystore.contains(Keystore.open(), alias(id))

    /** Test hook: the security level of [id]'s key as Keystore reports it (logged by the instrumented tests). */
    internal fun keyInfo(id: WalletId): KeyInfo {
        val key = walletKey(id)
        return SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
    }

    override fun toString(): String = "AndroidKeystoreDeviceKeyStore"

    private class KeystorePublicDataKey(private val key: SecretKey) : PublicDataKey {
        override fun mac(message: ByteArray): ByteArray = try {
            Mac.getInstance(HMAC).run {
                init(key)
                doFinal(message)
            }
        } catch (e: GeneralSecurityException) {
            throw VaultError.DeviceKeyUnavailable(e)
        } catch (e: ProviderException) {
            throw VaultError.DeviceKeyUnavailable(e)
        }

        override fun verify(message: ByteArray, tag: ByteArray): Boolean = try {
            constantTimeEquals(mac(message), tag)
        } catch (_: VaultError) {
            false
        }

        override fun toString(): String = "PublicDataKey(<keystore>)"
    }

    companion object {
        /** `com.bittool.signer.vault.device-key.v1/` + lowercase UUID (iOS service + account). */
        const val ALIAS_PREFIX = "com.bittool.signer.vault.device-key.v1/"

        /** iOS `WalletVault.publicDataKeyID` (`Core/WalletVault.swift:119`). */
        val PUBLIC_DATA_KEY_ID: WalletId = WalletId(UUID.fromString("0B175160-9A7E-4C1D-8000-00000000C0DE"))

        internal val PUBLIC_DATA_ALIAS: String = alias(PUBLIC_DATA_KEY_ID)

        internal fun alias(id: WalletId): String = ALIAS_PREFIX + id

        /** Throw-away keys of [isKeyLost] ([KeystoreLossProof.PROBE_PREFIX]); never under [ALIAS_PREFIX]. */
        internal const val PROBE_PREFIX = KeystoreLossProof.PROBE_PREFIX

        /** Independent observations that must agree before a key counts as lost ([KeystoreLossProof.LOSS_CONFIRMATIONS]). */
        internal const val LOSS_CONFIRMATIONS = KeystoreLossProof.LOSS_CONFIRMATIONS

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val HMAC = "HmacSHA256"
        private const val AES = "AES"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
    }
}
