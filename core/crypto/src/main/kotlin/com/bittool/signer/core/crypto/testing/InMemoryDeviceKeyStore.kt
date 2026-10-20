package com.bittool.signer.core.crypto.testing

import com.bittool.signer.core.crypto.vault.DeviceKeyStore
import com.bittool.signer.core.crypto.vault.PublicDataKey
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.util.constantTimeEquals
import com.bittool.signer.core.model.util.wipe
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A [DeviceKeyStore] with software keys in memory, for JVM tests, the Integration rerun of `:core:data`'s tests and
 * Wave 2 (the iOS tests' `MemoryDeviceKeys`, `BitToolSignerTests/SecurityTests.swift:221-243`). Same contract as the
 * Keystore implementation: AES-256-GCM `iv(12) ‖ ciphertext ‖ tag(16)`, a key is never replaced, a missing key is
 * [VaultError.DeviceKeyUnavailable], a bad tag [VaultError.AuthenticationFailed], creation needs [deviceSecure].
 * Keys live as long as the instance (share one instance between vault instances to model a process restart).
 * Thread-safe. Never bound in production.
 *
 * Fault hooks: [deviceSecure], [replaceKey] (a different device key: "wrong device"), [forgetKey] (a lost or
 * invalidated key), [forgetPublicDataKey], [failNextSeal].
 */
class InMemoryDeviceKeyStore(
    @Volatile var deviceSecure: Boolean = true,
    private val random: SecureRandom = SecureRandom(),
) : DeviceKeyStore {
    private val lock = Any()
    private val keys = HashMap<WalletId, ByteArray>()
    private var publicKey: ByteArray? = null

    /** When set, the next [seal] throws [VaultError.DeviceKeyUnavailable] (e.g. to test cleanup after key creation). */
    @Volatile var failNextSeal: Boolean = false

    override fun isDeviceSecure(): Boolean = deviceSecure

    override fun create(id: WalletId): Unit = synchronized(lock) {
        if (!deviceSecure) throw VaultError.DevicePasscodeRequired()
        if (id in keys) throw VaultError.DeviceKeyUnavailable()
        keys[id] = ByteArray(KEY_BYTES).also(random::nextBytes)
    }

    override fun seal(id: WalletId, plaintext: ByteArray, aad: ByteArray): ByteArray {
        if (failNextSeal) {
            failNextSeal = false
            throw VaultError.DeviceKeyUnavailable()
        }
        val key = keyOf(id)
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
        val key = keyOf(id)
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
        keys.remove(id)?.wipe()
    }

    override fun publicDataKey(createIfMissing: Boolean): PublicDataKey? = synchronized(lock) {
        publicKey?.let { return SoftwarePublicDataKey(it.copyOf()) }
        if (!createIfMissing) return null
        if (!deviceSecure) throw VaultError.DevicePasscodeRequired()
        val key = ByteArray(KEY_BYTES).also(random::nextBytes)
        publicKey = key
        SoftwarePublicDataKey(key.copyOf())
    }

    /** Whether a key exists for [id]. */
    fun containsKey(id: WalletId): Boolean = synchronized(lock) { id in keys }

    /** The number of wallet keys (the public-data key is not counted). */
    val keyCount: Int get() = synchronized(lock) { keys.size }

    /** Replaces the key of [id] with a fresh random one: every box sealed before fails with a bad tag. */
    fun replaceKey(id: WalletId): Unit = synchronized(lock) {
        keys[id]?.wipe()
        keys[id] = ByteArray(KEY_BYTES).also(random::nextBytes)
    }

    /** Drops the key of [id] as if Keystore had lost or invalidated it. */
    fun forgetKey(id: WalletId): Unit = synchronized(lock) {
        keys.remove(id)?.wipe()
    }

    /** Drops the public-data key (every public file then fails its MAC under a newly created key). */
    fun forgetPublicDataKey(): Unit = synchronized(lock) {
        publicKey?.wipe()
        publicKey = null
    }

    private fun keyOf(id: WalletId): ByteArray = synchronized(lock) {
        keys[id]?.copyOf() ?: throw VaultError.DeviceKeyUnavailable()
    }

    override fun toString(): String = "InMemoryDeviceKeyStore(keys=$keyCount)"

    private class SoftwarePublicDataKey(private val key: ByteArray) : PublicDataKey {
        override fun mac(message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

        override fun verify(message: ByteArray, tag: ByteArray): Boolean = constantTimeEquals(mac(message), tag)

        override fun toString(): String = "PublicDataKey(<memory>)"
    }

    private companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
