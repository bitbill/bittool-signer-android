package com.bittool.signer.core.crypto.vault

import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.VaultSecret
import com.bittool.signer.core.model.WalletId

/**
 * The encrypted wallet store (vault-crypto.md §6–§9, iOS `actor WalletVault`). Implemented by W1-crypto as a
 * `@Singleton` bound in its Hilt module; every method suspends and all of them are serialised by one `Mutex`
 * (an unlock blocks a concurrent [list], as on iOS). KDF work runs on `Dispatchers.Default`, file I/O on
 * `Dispatchers.IO`. No password, phrase or derived key is retained between calls.
 *
 * Storage (normative for Android, never portable): `noBackupFilesDir/BitToolSigner/Vaults/<lowercase-uuid>.bsvault`
 * (format 2 container `{"ciphertext","format":2,"header"}`, sorted keys, ≤ 65,536 bytes) and the retry counter
 * `.<lowercase-uuid>.attempts`; atomic writes via a `.pending-<UUID>` file + rename; reads refuse symlinks and
 * oversized files. Password layer: PBKDF2-HMAC-SHA256, 600,000 iterations on write (600,000–2,000,000 accepted on
 * read), 32-byte salt → AES-256-GCM with AAD `com.bittool.signer.vault.v2/password\0 + headerBytes`. Device layer:
 * a non-exportable Android Keystore AES-256-GCM key per wallet (alias `com.bittool.signer.vault.device-key.v1/<uuid>`,
 * StrongBox when available, `setUnlockedDeviceRequired(true)` on API 28+, no user-authentication binding) with AAD
 * `com.bittool.signer.vault.v2/device\0 + headerBytes`. The stored header bytes are authenticated verbatim and never
 * re-encoded. Every secret operation goes through the persistent retry counter (§8): the attempt is persisted before
 * any password work; from the 5th consecutive failure a delay of `min(300, 2^(count−4))` s applies (wall clock).
 *
 * Byte formats fixed here so they are chosen once and kept forever (vault-crypto.md §6.3, §17.5; Android vaults never
 * need to read iOS files):
 * - header JSON (compact, keys sorted, UTF-8): `{"iterations":600000,"kdf":"PBKDF2-HMAC-SHA256","metadata":
 *   {"createdAt":<seconds since 2001-01-01T00:00:00Z as a JSON number with millisecond precision, i.e.
 *   (epochMillis − 978_307_200_000) / 1000.0>,"id":"<UPPERCASE uuid>","name":"<trimmed name>"},"salt":"<std base64,
 *   padded, 32 bytes>","version":2}`; `header`/`ciphertext` of the container are standard padded base64; on read the id
 *   is compared as a UUID with the file name (either case) and `createdAt` is kept as epoch milliseconds;
 * - the secret JSON `{"mnemonic":…,"passphrase":…}` (sorted keys) is built in a `ByteArray` and wiped after sealing;
 * - attempts file `.<lowercase-uuid>.attempts` = `DeviceKeyStore.seal(id, plaintext, aad)` with plaintext
 *   `{"count":<0…16>,"retryAfter":<epoch ms, 0 = distant past>}` and aad = UTF-8 of
 *   `"com.bittool.signer.auth.v1/" + UPPERCASE uuid` (no NUL); read: regular file ≤ 1,024 bytes, opens and decodes,
 *   `count ∈ 0…16`, else [VaultError.MalformedVault] (a damaged counter fails closed until reset/delete removes it).
 *
 * A secure lock screen is required for every create **and** every secret operation (vault-crypto.md §17.3 option 1):
 * when `DeviceKeyStore.isDeviceSecure()` is false they throw [VaultError.DevicePasscodeRequired] after loading the
 * file and before the attempt is counted.
 *
 * Secret arguments are owned by the caller (it wipes them); the vault copies to byte arrays and wipes its copies.
 * Errors: [VaultError] subclasses only (plus `CancellationException`); unexpected exceptions of [list] are
 * [VaultError.StorageFailure], of a load [VaultError.MalformedVault].
 *
 * Cancellation (iOS actor calls cannot be interrupted): a call cancelled before its persistent write throws
 * `CancellationException` and changes nothing but the retry counter (an unlock has already counted its attempt); once
 * the write of [create], [changePassword], [resetPassword] (with its counter reset) or [delete] has started, it
 * completes and the call returns normally (or with that write's own error) even if the caller was cancelled
 * meanwhile. A `CancellationException` from these four therefore always means that no wallet, password or file was
 * changed. Main-safe: every file and Keystore call runs on the injected dispatchers. Members added after Wave 1
 * ([isDeviceKeyLost], [removeWithLostDeviceKey]) have conservative defaults so every existing implementation keeps
 * compiling; [removeWithLostDeviceKey]'s commit follows the same cancellation rule as [delete]. [isDeviceSecure] (QA L4)
 * has a default as well.
 */
interface WalletVault {
    /**
     * Every stored wallet, sorted by creation time ascending, ties by uppercase UUID string ascending. One damaged file
     * fails the whole list ([VaultError.MalformedVault]); a missing directory is an empty list.
     */
    suspend fun list(): List<VaultMetadata>

    /**
     * Creates wallet [id]: name trimmed and 1–40 graphemes ([VaultError.InvalidName]); [password] must pass
     * `PasswordPolicy` ([VaultError.InvalidPassword]); [mnemonic] is the canonical phrase (non-empty, ≤ 8,192 UTF-8
     * bytes) and [passphrase] ≤ 1,024 bytes ([VaultError.InvalidSecret]); requires a secure lock screen
     * ([VaultError.DevicePasscodeRequired]); an existing file for [id] is [VaultError.StorageFailure]. Order: policy →
     * PBKDF2 + inner layer → new Keystore key (fails if the alias exists) → outer layer → atomic write; any failure after
     * key generation deletes the alias. `createdAt` is the current time floored to milliseconds.
     */
    suspend fun create(name: String, mnemonic: SecretText, passphrase: SecretText, password: SecretText, id: WalletId): VaultMetadata

    /**
     * Decrypts wallet [id] with [password] (counted, throttled). Exact order (vault-crypto.md §8): load + validate the
     * file ([VaultError.WalletNotFound], [VaultError.MalformedVault], [VaultError.UnsupportedVersion]) → secure lock
     * screen ([VaultError.DevicePasscodeRequired], not counted) → read the attempts file ([VaultError.MalformedVault];
     * missing/invalidated device key → [VaultError.DeviceKeyUnavailable]) → inside a delay window
     * [VaultError.TemporarilyLocked] with `max(1, ceil(remaining s))` (not counted) → persist `count+1` and the next
     * delay **before** any password work (sealing with a missing device key → [VaultError.DeviceKeyUnavailable], an I/O
     * failure → [VaultError.StorageFailure]; both abort uncounted, iOS reads the device key at this point too) → an
     * empty password or one over 1,024 UTF-8 bytes is
     * [VaultError.AuthenticationFailed] (counted) → PBKDF2 + both layers (any failure [VaultError.AuthenticationFailed])
     * → success resets the counter. The caller closes the returned secret.
     */
    suspend fun unlock(id: WalletId, password: SecretText): VaultSecret

    /** [unlock] whose secret dies inside the vault (App Lock's "use a wallet password"); same errors and counter. */
    suspend fun verifyPassword(id: WalletId, password: SecretText)

    /**
     * Policy-checks [newPassword] first (not counted), then unlocks with [currentPassword] (counted) and re-encrypts the
     * same secret and header metadata under a new salt with the same device key; atomic replace (a failure keeps the old
     * password valid). Two full KDF runs.
     */
    suspend fun changePassword(id: WalletId, currentPassword: SecretText, newPassword: SecretText)

    /**
     * Re-encrypts wallet [id] under [newPassword] without the old one, from the supplied canonical [mnemonic] and
     * [passphrase]; keeps id, name, createdAt and device key; resets the retry counter. Order (vault-crypto.md §9.5):
     * `PasswordPolicy` on [newPassword] ([VaultError.InvalidPassword] with the policy message) → [passphrase] ≤ 1,024
     * UTF-8 bytes ([VaultError.InvalidPassword] "The BIP39 passphrase exceeds 1,024 UTF-8 bytes.") → load
     * ([VaultError.WalletNotFound]) → encrypt with the existing device key and header metadata → atomic replace →
     * best-effort counter reset. A refused reset changes nothing. The vault cannot tell whether the phrase belongs to
     * this wallet: `SignerStore.resetPassword` checks the `walletKeyId` first.
     */
    suspend fun resetPassword(id: WalletId, mnemonic: SecretText, passphrase: SecretText, newPassword: SecretText)

    /**
     * Verifies [password] (a full [unlock], counted), then removes the vault file ([VaultError.StorageFailure]), the
     * attempts file (best effort) and the device key. A key-deletion failure after the file is gone is reported as
     * [VaultError.DeviceKeyUnavailable]. A wrong password changes nothing.
     */
    suspend fun delete(id: WalletId, password: SecretText)

    /**
     * Whether wallet [id] can never be unlocked again because its device key is provably gone
     * ([DeviceKeyStore.isKeyLost]: no Keystore entry while Keystore demonstrably answers, or a permanently invalidated
     * one), the case in which [removeWithLostDeviceKey] removes it without its password (QA backlog B9). Requires the
     * file to load ([VaultError.WalletNotFound], [VaultError.MalformedVault], [VaultError.UnsupportedVersion]); false
     * without a secure lock screen (every secret operation then reports [VaultError.DevicePasscodeRequired] first) and
     * whenever the loss is not proven. Reads nothing secret and counts no attempt.
     *
     * The default is false (an implementation that cannot prove a loss never removes a wallet without its password).
     */
    suspend fun isDeviceKeyLost(id: WalletId): Boolean = false

    /**
     * Removes wallet [id] **without its password** because its device key is provably gone, which leaves the wallet
     * undecryptable forever and otherwise a dead end: it cannot be unlocked, reset, deleted or re-imported (QA backlog
     * B9; iOS has no way out of it). Order, all under the mutex: load ([VaultError.WalletNotFound],
     * [VaultError.MalformedVault], [VaultError.UnsupportedVersion]) → secure lock screen
     * ([VaultError.DevicePasscodeRequired]) → the loss is re-proven here, never taken from an earlier
     * [isDeviceKeyLost] ([VaultError.DeviceKeyLossUnconfirmed] when it is not: nothing changes, only [delete] with the
     * password can remove a wallet whose key may still exist) → one commit: the vault file ([VaultError.StorageFailure]),
     * the attempts file and any key remnant (an invalidated entry), both best effort. No attempt is counted.
     *
     * The default refuses with [VaultError.DeviceKeyLossUnconfirmed].
     */
    suspend fun removeWithLostDeviceKey(id: WalletId) {
        throw VaultError.DeviceKeyLossUnconfirmed()
    }

    /**
     * Android-only (QA L4): whether the device has a secure lock screen right now (`DeviceKeyStore.isDeviceSecure()`),
     * without which every create and secret operation refuses with [VaultError.DevicePasscodeRequired] and
     * [isDeviceKeyLost] is false. A stateless query: it reads no file, counts nothing and does not wait for other vault
     * calls (so a running unlock never delays it).
     *
     * The default is true: an implementation that cannot tell leaves the verdict to the operations themselves.
     */
    suspend fun isDeviceSecure(): Boolean = true

    /**
     * The app-wide public-data HMAC key (Keystore HMAC-SHA256, alias `com.bittool.signer.vault.device-key.v1/
     * 0b175160-9a7e-4c1d-8000-00000000c0de`, TEE preferred over StrongBox). Null only when it definitely does not exist
     * and [createIfMissing] is false; Keystore errors also yield null for `false` (iOS `try?`). With [createIfMissing]
     * it is created when absent ([VaultError.DevicePasscodeRequired] without a secure lock screen) and **never**
     * regenerated over an existing alias. Never deleted, not even with the last wallet.
     */
    suspend fun publicDataKey(createIfMissing: Boolean): PublicDataKey?
}

/**
 * A handle on the Keystore public-data HMAC key; the key bytes never leave Keystore. Thread-safe. Used by the public
 * wallet repository (`:core:data`) to authenticate sidecars: `mac = HMAC-SHA256(key, "com.bittool.signer.public-wallet.v2"
 * ‖ 0x00 ‖ lowercase uuid ‖ 0x00 ‖ bodyBytes)` (vault-crypto.md §10.3).
 */
interface PublicDataKey {
    /** HMAC-SHA256 of [message] (32 bytes). Throws [VaultError.DeviceKeyUnavailable] when Keystore refuses. */
    fun mac(message: ByteArray): ByteArray

    /** Constant-time check of [tag] against `mac(message)`; false on any mismatch or Keystore failure. */
    fun verify(message: ByteArray, tag: ByteArray): Boolean
}

/** Naming of the signer's private files (all under `@SignerDataDir`, i.e. `noBackupFilesDir/BitToolSigner`). */
object SignerFiles {
    const val VAULTS_DIR = "Vaults"
    const val VAULT_EXTENSION = "bsvault"
    const val PUBLIC_WALLETS_DIR = "PublicWallets"
    /**
     * The Activity journal, Keystore-encrypted (DECISIONS.md §9.3, activity-settings.md §14.2): AES-GCM under the
     * non-exportable key [RECEIPTS_KEY_ALIAS]; the plaintext is `ReceiptJournal.encode` output with every iOS limit.
     */
    const val RECEIPTS_FILE = "receipts.bin"
    const val RECEIPTS_KEY_ALIAS = "com.bittool.signer.activity.v1"
}
