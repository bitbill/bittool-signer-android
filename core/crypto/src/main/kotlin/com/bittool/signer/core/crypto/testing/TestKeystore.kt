package com.bittool.signer.core.crypto.testing

import androidx.annotation.VisibleForTesting
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DeviceLockScreen
import com.bittool.signer.core.crypto.vault.KeystoreJournalCipher
import com.bittool.signer.core.crypto.vault.KeystoreKeyPolicy

/**
 * The production Android Keystore implementations ([AndroidKeystoreDeviceKeyStore], [KeystoreJournalCipher]) with the
 * key policy of the instrumented tests, for other modules' instrumented tests (`:core:data` androidTest) on a device
 * **without a secure lock screen** — the shared test emulator, whose security settings tests must not change.
 *
 * The production configuration (`KeystoreKeyPolicy.forDevice`) marks every key `setUnlockedDeviceRequired(true)`, which
 * API 31 Keystore refuses to generate while no lock screen exists, and the device-key store refuses to create keys
 * without one (vault-crypto.md §17.3). The test policy is `KeystoreKeyPolicy(strongBox = false, unlockedDeviceRequired =
 * false)`, the one `:core:crypto`'s own Keystore tests use; every cryptographic operation is still the production code
 * under real non-exportable Keystore keys. On a device with a lock screen use the `@Inject` constructors instead.
 *
 * **Test support only** (backlog B23): these keys are weaker than production's (usable while the device is locked), so
 * nothing outside tests may build them. Guarded three ways: `@VisibleForTesting` (lint `VisibleForTests`, an error in
 * this module and in `:app`'s whole-app lint, flags any call from production code), nothing in the app references it
 * (so R8 removes it), and `check<Variant>HasNoHarness` fails a release build whose R8 output still contains it. The
 * debug UI-test harness uses its own file-backed fakes, never this.
 */
@VisibleForTesting
object TestKeystore {
    /** The policy without StrongBox and without `setUnlockedDeviceRequired`. */
    private val testPolicy = KeystoreKeyPolicy(strongBox = false, unlockedDeviceRequired = false)

    /**
     * A new [AndroidKeystoreDeviceKeyStore] (per-wallet AES keys and the HMAC public-data key in the Android Keystore)
     * whose lock-screen gate reports [lockScreenSecure] instead of `KeyguardManager.isDeviceSecure()`.
     */
    @VisibleForTesting
    fun deviceKeyStore(lockScreenSecure: () -> Boolean = { true }): AndroidKeystoreDeviceKeyStore =
        AndroidKeystoreDeviceKeyStore(DeviceLockScreen { lockScreenSecure() }, testPolicy)

    /**
     * A new [KeystoreJournalCipher] (one per simulated process): the alias `com.bittool.signer.activity.v1` persists in
     * Keystore across instances, as in the app.
     */
    @VisibleForTesting
    fun journalCipher(): KeystoreJournalCipher = KeystoreJournalCipher(testPolicy)
}
