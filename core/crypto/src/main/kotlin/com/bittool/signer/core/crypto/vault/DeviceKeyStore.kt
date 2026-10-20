package com.bittool.signer.core.crypto.vault

import com.bittool.signer.core.model.WalletId

/**
 * The per-wallet device-key boundary (vault-crypto.md §7, §17.3–17.4). Android keys never leave Keystore, so instead of
 * handing out key bytes (iOS `DeviceKeyStore.read`) this interface performs the vault's outer AES-256-GCM layer.
 * Bindings (W1-crypto): `AndroidKeystoreDeviceKeyStore` in production, `InMemoryDeviceKeyStore` in JVM tests, and the
 * debug UI-test harness's file-backed fake as `Optional<@UiTestOverride DeviceKeyStore>` (gaps.md G3). Not thread-safe
 * by itself: [WalletVault] calls it under its mutex. Errors are [VaultError]s. Members added after Wave 1 ([isKeyLost])
 * have conservative defaults so every existing implementation keeps compiling.
 */
interface DeviceKeyStore {
    /** `KeyguardManager.isDeviceSecure()`; every create and secret operation refuses with [VaultError.DevicePasscodeRequired] when false. */
    fun isDeviceSecure(): Boolean

    /**
     * Generates the AES-256-GCM key of [id] (alias `com.bittool.signer.vault.device-key.v1/<lowercase-uuid>`, StrongBox
     * when present with a non-StrongBox retry, `setUnlockedDeviceRequired(true)` on API 28+, no user-auth binding).
     * Checks `containsAlias` first and **never** replaces a key: an existing alias is [VaultError.DeviceKeyUnavailable].
     */
    fun create(id: WalletId)

    /** Outer layer: `iv(12) ‖ ciphertext ‖ tag(16)` of [plaintext] with [aad]. Missing/invalidated key → [VaultError.DeviceKeyUnavailable]. */
    fun seal(id: WalletId, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** Inverse of [seal]. Bad tag → [VaultError.AuthenticationFailed]; missing key (also a flaky "missing" on API 26–30) → [VaultError.DeviceKeyUnavailable], never a reason to create. */
    fun open(id: WalletId, combined: ByteArray, aad: ByteArray): ByteArray

    /** Deletes the key of [id]; an absent alias is success, any other failure [VaultError.DeviceKeyUnavailable]. */
    fun delete(id: WalletId)

    /**
     * Whether the key of [id] is **provably** gone, so that nothing can ever open what it sealed (the lost-key dead end,
     * QA backlog B9): Keystore holds no entry for it *while Keystore demonstrably answers*, or the entry is permanently
     * invalidated. A missing key is not proof by itself: a failed binder call reports a live key as missing (API 26–30,
     * see [open]), so the Keystore implementation confirms the absence repeatedly against a key it has just generated,
     * and a key that only failed to load, or a device that is locked, is not lost. Never throws: false when unsure.
     *
     * The default is false, so an implementation that cannot prove a loss never lets a wallet be removed without its
     * password ([WalletVault.removeWithLostDeviceKey]).
     */
    fun isKeyLost(id: WalletId): Boolean = false

    /** The public-data HMAC key; semantics of [WalletVault.publicDataKey] (which delegates here). */
    fun publicDataKey(createIfMissing: Boolean): PublicDataKey?
}

/**
 * Seals the Activity journal (DECISIONS.md §9.3): AES-GCM under the non-exportable Keystore key
 * [SignerFiles.RECEIPTS_KEY_ALIAS] (created on first [seal]; `setUnlockedDeviceRequired(true)` on API 28+). Output layout
 * `magic "BTSR" ‖ version 0x01 ‖ iv(12) ‖ ciphertext ‖ tag(16)`, the header bytes as AAD. [open] throws
 * [VaultError.AuthenticationFailed] for a bad tag, unknown magic/version or a lost key; the caller then reports
 * "The local activity record could not be read." and never overwrites the file silently.
 */
interface JournalCipher {
    fun seal(plaintext: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}
