package com.bittool.signer.core.data.doubles

import com.bittool.signer.core.crypto.vault.PublicDataKey
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.crypto.vault.WalletNames
import com.bittool.signer.core.crypto.vault.WalletVault
import com.bittool.signer.core.model.PasswordPolicy
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.VaultSecret
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.util.utf8Size
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A Wave-1 stand-in for W1-crypto's `DefaultWalletVault`: the [WalletVault] KDoc semantics the store relies on (vault
 * order, name/policy/secret validation, a counted unlock that fails with `AuthenticationFailed` for a wrong, empty or
 * oversized password, verify-before-delete, the public-data key lifecycle), without PBKDF2, files or Keystore. One
 * [Mutex] serialises every call, like the real vault.
 *
 * Test probes: [unlockAttempts] counts every unlock attempt per wallet, never reset (iOS asserts that no attempts file
 * exists, i.e. nothing ever tried a password), [revision] changes only when a wallet's stored secret or password is
 * rewritten (iOS compares the vault file bytes), [failList] makes the next [list] fail, [holdNextCreate] /
 * [holdNextUnlock] hold a call after its effect, [issuedSecrets] lists the secrets handed out, [loseDeviceKey] makes a
 * wallet's device key provably gone (QA backlog B9: every password operation then fails uncounted with
 * `DeviceKeyUnavailable`, and [removeWithLostDeviceKey] removes the wallet).
 */
class InMemoryWalletVault : WalletVault {

    private class Record(
        val metadata: VaultMetadata,
        var mnemonic: CharArray,
        var passphrase: CharArray,
        var password: CharArray,
        var revision: Int = 0,
    )

    private val mutex = Mutex()
    private val records = LinkedHashMap<WalletId, Record>()
    private val attempts = HashMap<WalletId, Int>()
    private val lostKeys = HashSet<WalletId>()
    private var publicKey: PublicDataKey? = null
    private val clock = AtomicLong(1_760_000_000_000L)

    /** When set, the next [list] throws it once. */
    @Volatile
    var failList: Throwable? = null

    @Volatile
    private var createGate: EngineGate? = null

    @Volatile
    private var unlockGate: EngineGate? = null

    /**
     * The lock screen (`isDeviceSecure`, QA L4). Like the real vault, without one [isDeviceKeyLost] is false and every
     * password operation refuses with `DevicePasscodeRequired`.
     */
    @Volatile
    var deviceSecure: Boolean = true

    /** How many times [isDeviceKeyLost] was asked, per wallet (a proven loss is not proven again). */
    val lossChecks = java.util.concurrent.ConcurrentHashMap<WalletId, Int>()

    /** Every secret [unlock] handed out, so tests can check that the store wiped the ones it did not return. */
    val issuedSecrets = java.util.concurrent.CopyOnWriteArrayList<VaultSecret>()

    /** Holds the next [create] **after** it stored the wallet (the commit happened), until released. */
    fun holdNextCreate(): EngineGate = EngineGate().also { createGate = it }

    /** Holds the next [unlock] **after** it decrypted (the secret exists), until released. */
    fun holdNextUnlock(): EngineGate = EngineGate().also { unlockGate = it }

    fun unlockAttempts(id: WalletId): Int = attempts[id] ?: 0

    fun revision(id: WalletId): Int? = records[id]?.revision

    fun contains(id: WalletId): Boolean = id in records

    /** The device key of [id] is gone for good (Keystore lost or invalidated it). */
    fun loseDeviceKey(id: WalletId) {
        lostKeys += id
    }

    /** Forgets the public-data key (e.g. a reinstall of the HMAC key): every sidecar then reads as invalid. */
    fun replacePublicDataKey(key: PublicDataKey?) {
        publicKey = key
    }

    override suspend fun list(): List<VaultMetadata> = mutex.withLock {
        failList?.let {
            failList = null
            throw it
        }
        records.values.map { it.metadata }.sortedWith(compareBy<VaultMetadata> { it.createdAtMillis }.thenBy { it.id.uppercase })
    }

    override suspend fun create(name: String, mnemonic: SecretText, passphrase: SecretText, password: SecretText, id: WalletId): VaultMetadata {
        val created = mutex.withLock {
            val trimmed = WalletNames.valid(name) ?: throw VaultError.InvalidName()
            val policy = PasswordPolicy.evaluate(password.view())
            if (!policy.isValid) throw VaultError.InvalidPassword(policy.message)
            if (mnemonic.isEmpty() || mnemonic.utf8Size() > 8_192 || passphrase.utf8Size() > 1_024) throw VaultError.InvalidSecret()
            if (id in records) throw VaultError.StorageFailure()
            val metadata = VaultMetadata(id, trimmed, clock.getAndAdd(1_000))
            records[id] = Record(metadata, mnemonic.chars().copyOf(), passphrase.chars().copyOf(), password.chars().copyOf())
            metadata
        }
        createGate?.let { gate ->
            createGate = null
            gate.pause()
        }
        return created
    }

    override suspend fun unlock(id: WalletId, password: SecretText): VaultSecret {
        val secret = mutex.withLock { unlockLocked(id, password) }
        issuedSecrets += secret
        unlockGate?.let { gate ->
            unlockGate = null
            gate.pause()
        }
        return secret
    }

    override suspend fun verifyPassword(id: WalletId, password: SecretText) {
        unlock(id, password).close()
    }

    override suspend fun changePassword(id: WalletId, currentPassword: SecretText, newPassword: SecretText) = mutex.withLock {
        val policy = PasswordPolicy.evaluate(newPassword.view())
        if (!policy.isValid) throw VaultError.InvalidPassword(policy.message)
        unlockLocked(id, currentPassword).close()
        val record = records.getValue(id)
        record.password = newPassword.chars().copyOf()
        record.revision++
        Unit
    }

    override suspend fun resetPassword(id: WalletId, mnemonic: SecretText, passphrase: SecretText, newPassword: SecretText) = mutex.withLock {
        val policy = PasswordPolicy.evaluate(newPassword.view())
        if (!policy.isValid) throw VaultError.InvalidPassword(policy.message)
        if (passphrase.utf8Size() > 1_024) throw VaultError.InvalidPassword(UiText.Ios("The BIP39 passphrase exceeds 1,024 UTF-8 bytes."))
        val record = records[id] ?: throw VaultError.WalletNotFound()
        if (id in lostKeys) throw VaultError.DeviceKeyUnavailable()
        record.mnemonic = mnemonic.chars().copyOf()
        record.passphrase = passphrase.chars().copyOf()
        record.password = newPassword.chars().copyOf()
        record.revision++
        Unit
    }

    override suspend fun delete(id: WalletId, password: SecretText) = mutex.withLock {
        unlockLocked(id, password).close()
        records.remove(id)
        Unit
    }

    override suspend fun isDeviceKeyLost(id: WalletId): Boolean = mutex.withLock {
        if (id !in records) throw VaultError.WalletNotFound()
        lossChecks.merge(id, 1, Int::plus)
        deviceSecure && id in lostKeys
    }

    override suspend fun isDeviceSecure(): Boolean = deviceSecure

    override suspend fun removeWithLostDeviceKey(id: WalletId) = mutex.withLock {
        if (id !in records) throw VaultError.WalletNotFound()
        if (id !in lostKeys) throw VaultError.DeviceKeyLossUnconfirmed()
        records.remove(id)
        lostKeys.remove(id)
        attempts.remove(id)
        Unit
    }

    override suspend fun publicDataKey(createIfMissing: Boolean): PublicDataKey? = mutex.withLock {
        publicKey ?: if (createIfMissing) HmacPublicDataKey().also { publicKey = it } else null
    }

    private fun unlockLocked(id: WalletId, password: SecretText): VaultSecret {
        val record = records[id] ?: throw VaultError.WalletNotFound()
        if (!deviceSecure) throw VaultError.DevicePasscodeRequired()
        // The real vault cannot seal the attempt without the key: refused before anything is counted.
        if (id in lostKeys) throw VaultError.DeviceKeyUnavailable()
        attempts[id] = (attempts[id] ?: 0) + 1
        if (password.isEmpty() || password.utf8Size() > 1_024 || !SecretText.wrap(record.password.copyOf()).useAndCompare(password)) {
            throw VaultError.AuthenticationFailed()
        }
        return VaultSecret(SecretText.wrap(record.mnemonic.copyOf()), SecretText.wrap(record.passphrase.copyOf()))
    }

    private fun SecretText.useAndCompare(other: SecretText): Boolean = try {
        contentEquals(other)
    } finally {
        wipe()
    }
}
