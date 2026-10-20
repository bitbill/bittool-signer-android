package com.bittool.signer.core.crypto.vault

import com.bittool.signer.core.common.coroutines.DefaultDispatcher
import com.bittool.signer.core.common.coroutines.IoDispatcher
import com.bittool.signer.core.common.storage.AtomicFiles
import com.bittool.signer.core.common.storage.InvalidFileException
import com.bittool.signer.core.common.storage.SignerDataDir
import com.bittool.signer.core.common.time.WallClock
import com.bittool.signer.core.crypto.SecureRandomSource
import com.bittool.signer.core.crypto.internal.hasUnpairedSurrogate
import com.bittool.signer.core.model.PasswordPolicy
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.VaultSecret
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.util.utf8Bytes
import com.bittool.signer.core.model.util.wipe
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [WalletVault] — port of iOS `actor WalletVault` + `VaultCrypto` (`Core/WalletVault.swift:61-279`,
 * `Core/VaultCrypto.swift:120-216`) with the Android device layer of vault-crypto.md §17.3: the outer AES-GCM layer
 * is performed by [deviceKeys] (a non-exportable Keystore key per wallet) instead of with raw key bytes.
 *
 * Every public method runs under one [Mutex] (actor serialisation: an unlock blocks a concurrent [list]); file I/O
 * and every [DeviceKeyStore] call (Keystore and `KeyguardManager` are binder IPC) run on [io], the KDF on [cpu], so
 * the vault is main-safe. No password, phrase or key is kept between calls; every byte copy is wiped.
 *
 * Cancellation: work before a persistent write is cancellable (a cancelled call changes nothing, except that an
 * unlock has already counted its attempt, vault-crypto.md §17.6). The persistent write itself and everything that
 * belongs to it ([commit]) is one non-cancellable step, and once it has started the method returns normally even if
 * the caller was cancelled meanwhile. iOS actor methods cannot be interrupted at all; here a cancellation that arrives
 * during the commit must not be reported as "nothing happened", because `SignerStore` treats a failed `create` as
 * uncommitted (it removes the new public sidecar) and runs its own post-commit steps (`resetPassword`, `delete`) only
 * after a normal return (SignerStore KDoc: "A background transition during the commit must not invite a second
 * import").
 */
@Singleton
class DefaultWalletVault @Inject constructor(
    @SignerDataDir dataDir: File,
    private val deviceKeys: DeviceKeyStore,
    private val random: SecureRandomSource,
    private val clock: WallClock,
    @DefaultDispatcher private val cpu: CoroutineDispatcher,
    @IoDispatcher private val io: CoroutineDispatcher,
) : WalletVault {
    private val directory = File(dataDir, SignerFiles.VAULTS_DIR)
    private val mutex = Mutex()
    private val attempts = AttemptCounter(directory, deviceKeys)
    private val passwordLayer = PasswordLayer(random)
    private var tidied = false

    override suspend fun list(): List<VaultMetadata> = mutex.withLock {
        withContext(io) {
            try {
                tidyOnce()
                if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return@withContext emptyList()
                val files = directory.listFiles() ?: throw VaultError.StorageFailure()
                files.filter { extension(it.name) == SignerFiles.VAULT_EXTENSION }
                    .map { file ->
                        val id = WalletId.parse(file.name.substringBeforeLast('.')) ?: throw VaultError.MalformedVault()
                        load(id).header.metadata
                    }
                    // Directory enumeration must not reorder wallets with equal timestamps (`:87-91`).
                    .sortedWith(compareBy<VaultMetadata> { it.createdAtMillis }.thenBy { it.id.uppercase })
            } catch (e: VaultError) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw VaultError.StorageFailure(e)
            }
        }
    }

    override suspend fun create(
        name: String,
        mnemonic: SecretText,
        passphrase: SecretText,
        password: SecretText,
        id: WalletId,
    ): VaultMetadata = mutex.withLock {
        // A lone UTF-16 surrogate cannot exist in a Swift string and would be stored as "?", so the header would not
        // read back as the name returned here.
        val trimmed = VaultMetadata.validName(name)?.takeUnless { hasUnpairedSurrogate(it.toCharArray()) }
            ?: throw VaultError.InvalidName()
        withContext(io) {
            tidyOnce()
            if (Files.exists(vaultFile(id).toPath(), LinkOption.NOFOLLOW_LINKS)) throw VaultError.StorageFailure()
            requireNewPassword(password)
            requireSecretBounds(mnemonic, passphrase)
            if (!deviceKeys.isDeviceSecure()) throw VaultError.DevicePasscodeRequired()
        }
        // Millisecond precision keeps creation order stable for wallets added in quick succession (`:100-101`).
        val metadata = VaultMetadata(id, trimmed, clock.currentTimeMillis())
        val salt = random.bytes(VaultFormat.SALT_BYTES)
        val headerBytes = VaultFormat.encodeHeader(metadata, salt)
        val inner = sealPasswordLayer(mnemonic, passphrase, password, salt, headerBytes)
        commit {
            // vault-crypto.md §17.3: the Keystore key must exist before the outer layer, so the iOS order changes to
            // policy → PBKDF2 + inner layer → new key (refused when the alias exists) → outer layer → atomic write,
            // and any failure after the key exists deletes it: never a key without a file or a file without a key.
            deviceKeys.create(id)
            try {
                val outer = try {
                    deviceKeys.seal(id, inner, VaultFormat.deviceAad(headerBytes))
                } catch (e: VaultError.DeviceKeyUnavailable) {
                    // The key was generated a moment ago, so this is not a lost device key ("Restore your recovery
                    // phrase…"): iOS reports a failed device-layer seal as `encryptionFailure`
                    // (`Core/VaultCrypto.swift:163-173`); key generation failures stay `deviceKeyUnavailable` (`:28-34`).
                    throw VaultError.EncryptionFailure(e)
                }
                writeVault(id, VaultFormat.encodeContainer(headerBytes, outer))
            } catch (e: Throwable) {
                try {
                    deviceKeys.delete(id)
                } catch (_: VaultError) {
                    // The key is new and protects nothing; the original failure is what the caller must see.
                }
                throw e
            }
        }
        metadata
    }

    override suspend fun unlock(id: WalletId, password: SecretText): VaultSecret = mutex.withLock { unlockLocked(id, password) }

    override suspend fun verifyPassword(id: WalletId, password: SecretText) {
        mutex.withLock { unlockLocked(id, password).close() }
    }

    override suspend fun changePassword(id: WalletId, currentPassword: SecretText, newPassword: SecretText) {
        mutex.withLock {
            requireNewPassword(newPassword)
            unlockLocked(id, currentPassword).use { secret ->
                val metadata = withContext(io) { load(id) }.header.metadata
                reencrypt(id, metadata, secret.mnemonic, secret.passphrase, newPassword)
            }
        }
    }

    override suspend fun resetPassword(id: WalletId, mnemonic: SecretText, passphrase: SecretText, newPassword: SecretText) {
        mutex.withLock {
            requireNewPassword(newPassword)
            if (passphrase.utf8Size() > MAX_PASSPHRASE_BYTES) {
                throw VaultError.InvalidPassword(UiText.Ios("The BIP39 passphrase exceeds 1,024 UTF-8 bytes."))
            }
            val metadata = withContext(io) {
                load(id).header.metadata.also { if (!deviceKeys.isDeviceSecure()) throw VaultError.DevicePasscodeRequired() }
            }
            requireSecretBounds(mnemonic, passphrase)
            reencrypt(id, metadata, mnemonic, passphrase, newPassword) {
                // Part of the same commit: the password the counter was throttling is gone (`:175, 186`); best
                // effort like iOS `try?`.
                try {
                    attempts.write(id, AttemptCounter.State.Cleared)
                } catch (_: VaultError) {
                }
            }
        }
    }

    override suspend fun delete(id: WalletId, password: SecretText) {
        mutex.withLock {
            // Authentication is required even if a UI session is already unlocked (`:196`).
            unlockLocked(id, password).close()
            commit {
                try {
                    Files.delete(vaultFile(id).toPath())
                } catch (e: IOException) {
                    throw VaultError.StorageFailure(e)
                }
                attempts.remove(id)
                // If Keystore refuses after the file is gone, report it: a remaining random key holds no secret.
                deviceKeys.delete(id)
            }
        }
    }

    override suspend fun publicDataKey(createIfMissing: Boolean): PublicDataKey? = mutex.withLock {
        withContext(io) {
            if (createIfMissing) {
                deviceKeys.publicDataKey(createIfMissing = true)
            } else {
                try {
                    deviceKeys.publicDataKey(createIfMissing = false)
                } catch (_: VaultError) {
                    null // iOS `try?`: an unreadable key makes the public files invalid (Repair), never an error here.
                }
            }
        }
    }

    // region internals (all called under the mutex)

    /** iOS `unlock(id:password:)` (`:135-152`) in the order of the [WalletVault.unlock] KDoc. */
    private suspend fun unlockLocked(id: WalletId, password: SecretText): VaultSecret {
        val (vault, previous) = withContext(io) {
            val vault = load(id)
            if (!deviceKeys.isDeviceSecure()) throw VaultError.DevicePasscodeRequired()
            vault to attempts.read(id)
        }
        val now = clock.currentTimeMillis()
        AttemptCounter.lockedSeconds(previous, now)?.let { throw VaultError.TemporarilyLocked(it) }
        // Persist the attempt before costly decryption: killing and relaunching the process cannot reset the counter.
        withContext(io) { attempts.write(id, AttemptCounter.afterAttempt(previous, now)) }

        val chars = password.chars()
        if (chars.isEmpty() || password.utf8Size() > MAX_PASSWORD_BYTES || hasUnpairedSurrogate(chars)) {
            throw VaultError.AuthenticationFailed()
        }
        val cleartext = secretWork(cpu) {
            val job = coroutineContext[Job]
            val passwordBytes = utf8Bytes(chars)
            val key = try {
                passwordLayer.deriveKey(passwordBytes, vault.header.salt, vault.header.iterations.toInt()) { job?.ensureActive() }
            } finally {
                passwordBytes.wipe()
            }
            PasswordLayer.withKey(key) {
                // AES-GCM cannot tell a wrong password from tampering: every failure of either layer reads
                // "The password is incorrect." (`Core/VaultCrypto.swift:32-35, 190-192`).
                try {
                    val inner = deviceKeys.open(id, vault.ciphertext, VaultFormat.deviceAad(vault.headerBytes))
                    try {
                        passwordLayer.open(it, inner, VaultFormat.passwordAad(vault.headerBytes))
                    } finally {
                        inner.wipe()
                    }
                } catch (_: VaultError) {
                    throw VaultError.AuthenticationFailed()
                }
            }
        }
        val secret = try {
            SecretJson.decode(cleartext)
        } finally {
            cleartext.wipe()
        }
        try {
            withContext(io) { attempts.write(id, AttemptCounter.State.Cleared) }
        } catch (e: Throwable) {
            secret.close()
            throw e
        }
        return secret
    }

    /**
     * [withContext] for work that returns a secret: when the caller is cancelled after [block] produced its result,
     * `withContext` discards the value, so it is wiped here instead of being left to the garbage collector.
     */
    private suspend fun secretWork(dispatcher: CoroutineDispatcher, block: suspend CoroutineScope.() -> ByteArray): ByteArray {
        var produced: ByteArray? = null
        try {
            return withContext(dispatcher) { block().also { produced = it } }
        } catch (e: CancellationException) {
            produced?.wipe()
            throw e
        }
    }

    /**
     * New salt and nonces, same header metadata and device key, atomic replace (a failure keeps the old file). The KDF
     * is cancellable; the outer layer, the write and [alsoCommit] are one [commit].
     */
    private suspend fun reencrypt(
        id: WalletId,
        metadata: VaultMetadata,
        mnemonic: SecretText,
        passphrase: SecretText,
        password: SecretText,
        alsoCommit: () -> Unit = {},
    ) {
        val salt = random.bytes(VaultFormat.SALT_BYTES)
        val headerBytes = VaultFormat.encodeHeader(metadata, salt)
        val inner = sealPasswordLayer(mnemonic, passphrase, password, salt, headerBytes)
        commit {
            val outer = deviceKeys.seal(id, inner, VaultFormat.deviceAad(headerBytes))
            writeVault(id, VaultFormat.encodeContainer(headerBytes, outer))
            alsoCommit()
        }
    }

    /**
     * Runs a persistent change on [io] as one step that cancellation cannot interrupt or misreport: a caller cancelled
     * before it starts gets a `CancellationException` and nothing is written; once it has started it completes, and
     * its result (or its own failure) is returned even if the caller was cancelled meanwhile. A plain
     * `withContext(io)` would not do: it discards a completed block's result and throws on resumption when the caller
     * was cancelled (prompt cancellation), and `NonCancellable + io` switches dispatchers, so it does the same. The
     * outer `withContext(NonCancellable)` keeps the caller's dispatcher and therefore never checks for cancellation on
     * exit (kotlinx.coroutines `withContext` KDoc).
     */
    private suspend fun <T> commit(block: () -> T): T {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) { withContext(io) { block() } }
    }

    /** PBKDF2 + the inner AES-GCM layer over the secret JSON (wiped). */
    private suspend fun sealPasswordLayer(
        mnemonic: SecretText,
        passphrase: SecretText,
        password: SecretText,
        salt: ByteArray,
        headerBytes: ByteArray,
    ): ByteArray = withContext(cpu) {
        val job = coroutineContext[Job]
        val passwordBytes = utf8Bytes(password.chars())
        val key = try {
            passwordLayer.deriveKey(passwordBytes, salt, VaultFormat.ITERATIONS) { job?.ensureActive() }
        } finally {
            passwordBytes.wipe()
        }
        PasswordLayer.withKey(key) {
            val cleartext = SecretJson.encode(mnemonic.chars(), passphrase.chars())
            try {
                passwordLayer.seal(it, cleartext, VaultFormat.passwordAad(headerBytes))
            } finally {
                cleartext.wipe()
            }
        }
    }

    /** iOS `load(id:)` (`:206-220`): the file of [id], bounded, decoded, validated, its header id equal to [id]. */
    private fun load(id: WalletId): EncryptedVault {
        val bytes = try {
            AtomicFiles.readBounded(vaultFile(id), VaultFormat.MAX_FILE_BYTES)
        } catch (e: InvalidFileException) {
            throw VaultError.MalformedVault(e)
        } catch (e: IOException) {
            throw VaultError.MalformedVault(e)
        } ?: throw VaultError.WalletNotFound()
        val vault = try {
            VaultFormat.decode(bytes)
        } catch (e: VaultError) {
            throw e
        } catch (e: RuntimeException) {
            throw VaultError.MalformedVault(e)
        }
        VaultFormat.validate(vault)
        if (vault.header.metadata.id != id) throw VaultError.MalformedVault()
        return vault
    }

    private fun writeVault(id: WalletId, bytes: ByteArray) {
        try {
            AtomicFiles.write(vaultFile(id), bytes)
        } catch (e: IOException) {
            throw VaultError.StorageFailure(e)
        }
    }

    private fun vaultFile(id: WalletId) = File(directory, "$id.${SignerFiles.VAULT_EXTENSION}")

    /** Leftover `.pending-*` staging files after a crash are harmless; remove them once per process. */
    private fun tidyOnce() {
        if (tidied) return
        tidied = true
        if (directory.isDirectory) AtomicFiles.deleteStalePending(directory)
    }

    /** Foundation `pathExtension`: the part after the last dot, none for a leading-dot-only name. */
    private fun extension(name: String): String = name.lastIndexOf('.').let { if (it > 0) name.substring(it + 1) else "" }

    /** `PasswordPolicy` for a password being set; a lone surrogate counts as a control character (Swift cannot hold one). */
    private fun requireNewPassword(password: SecretText) {
        val evaluation = PasswordPolicy.evaluate(password.view())
        if (!evaluation.isValid) throw VaultError.InvalidPassword(evaluation.message)
        if (hasUnpairedSurrogate(password.chars())) throw VaultError.InvalidPassword(UiText.Ios("Control characters are not allowed."))
    }

    /** iOS `encrypt` bounds (`:154-155`): phrase non-empty ≤ 8,192 UTF-8 bytes, passphrase ≤ 1,024. */
    private fun requireSecretBounds(mnemonic: SecretText, passphrase: SecretText) {
        if (mnemonic.isEmpty() || mnemonic.utf8Size() > MAX_PHRASE_BYTES || passphrase.utf8Size() > MAX_PASSPHRASE_BYTES ||
            hasUnpairedSurrogate(mnemonic.chars()) || hasUnpairedSurrogate(passphrase.chars())
        ) {
            throw VaultError.InvalidSecret()
        }
    }

    // endregion

    private companion object {
        const val MAX_PASSWORD_BYTES = 1_024
        const val MAX_PHRASE_BYTES = 8_192
        const val MAX_PASSPHRASE_BYTES = 1_024
    }
}
