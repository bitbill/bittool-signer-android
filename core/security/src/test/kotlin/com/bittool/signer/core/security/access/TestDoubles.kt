package com.bittool.signer.core.security.access

import com.bittool.signer.core.common.testing.FakeWallClock
import com.bittool.signer.core.crypto.vault.PublicDataKey
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.crypto.vault.WalletVault
import com.bittool.signer.core.data.PreferenceValues
import com.bittool.signer.core.data.SignerPreferences
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.VaultSecret
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.useAndWipe
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Wave-1 double of W1-data's DataStore preferences (iOS tests use a throw-away `UserDefaults` suite): the first [load]
 * can be held back ([loadGate]) or fail ([loadFailure]); writes are recorded and can fail ([writeFailure]).
 */
internal class TestPreferences(appLockEnabled: Boolean = false) : SignerPreferences {
    var stored = PreferenceValues(appLockEnabled = appLockEnabled)
        private set
    var loadGate: CompletableDeferred<Unit>? = null
    var loadFailure: Throwable? = null
    var writeFailure: Throwable? = null
    var writeGate: CompletableDeferred<Unit>? = null
    val appLockWrites = mutableListOf<Boolean>()
    var loads = 0
        private set

    private val _values = MutableStateFlow<PreferenceValues?>(null)
    override val values: StateFlow<PreferenceValues?> = _values

    override suspend fun load(): PreferenceValues {
        loads += 1
        loadGate?.await()
        loadFailure?.let { throw it }
        _values.value = stored
        return stored
    }

    override suspend fun setAppLockEnabled(enabled: Boolean) {
        writeGate?.await()
        writeFailure?.let { throw it }
        appLockWrites += enabled
        stored = stored.copy(appLockEnabled = enabled)
        _values.value = stored
    }

    override suspend fun setAppearance(appearance: Appearance) {
        stored = stored.copy(appearance = appearance)
        _values.value = stored
    }

    override suspend fun setOnboardingVersionCompleted(version: Int) {
        stored = stored.copy(onboardingVersionCompleted = version)
        _values.value = stored
    }
}

/**
 * Wave-1 double of W1-crypto's vault for App Lock: per-wallet passwords and the persistent retry counter of
 * WalletVault.swift:135-152 / vault-crypto.md §8 (counted before checking; from the 5th consecutive failure a delay of
 * `min(300, 2^(count−4))` s on the wall clock; success resets). Only [verifyPassword] is used by App Lock.
 */
internal class TestVault(private val clock: FakeWallClock = FakeWallClock()) : WalletVault {
    private class Entry(val password: String) {
        var failures = 0
        var retryAfterMillis = 0L
    }

    private val wallets = mutableMapOf<WalletId, Entry>()
    var verifications = 0
        private set

    fun add(id: WalletId, password: String) {
        wallets[id] = Entry(password)
    }

    override suspend fun verifyPassword(id: WalletId, password: SecretText) {
        verifications += 1
        val entry = wallets[id] ?: throw VaultError.WalletNotFound()
        val now = clock.currentTimeMillis()
        if (now < entry.retryAfterMillis) {
            throw VaultError.TemporarilyLocked(maxOf(1L, ceil((entry.retryAfterMillis - now) / 1000.0).toLong()))
        }
        val count = min(entry.failures + 1, 16)
        entry.failures = count
        entry.retryAfterMillis = if (count >= 5) now + min(300L, 1L shl (count - 4)) * 1000 else 0L
        val matches = SecretText.of(entry.password).useAndWipe { password.contentEquals(it) }
        if (!matches) throw VaultError.AuthenticationFailed()
        entry.failures = 0
        entry.retryAfterMillis = 0L
    }

    override suspend fun list(): List<VaultMetadata> = unused()
    override suspend fun create(name: String, mnemonic: SecretText, passphrase: SecretText, password: SecretText, id: WalletId): VaultMetadata = unused()
    override suspend fun unlock(id: WalletId, password: SecretText): VaultSecret = unused()
    override suspend fun changePassword(id: WalletId, currentPassword: SecretText, newPassword: SecretText): Unit = unused()
    override suspend fun resetPassword(id: WalletId, mnemonic: SecretText, passphrase: SecretText, newPassword: SecretText): Unit = unused()
    override suspend fun delete(id: WalletId, password: SecretText): Unit = unused()
    override suspend fun publicDataKey(createIfMissing: Boolean): PublicDataKey? = unused()

    private fun unused(): Nothing = error("App Lock only verifies passwords")
}

/** A test password the caller wipes, like every `SecretText` passed to the controller. */
internal fun password(text: String): SecretText = SecretText.of(text)
