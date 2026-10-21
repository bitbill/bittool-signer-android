package com.bittool.signer.harness

import com.bittool.signer.core.common.harness.UiTestEnvironment
import com.bittool.signer.core.common.harness.UiTestOverride
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DeviceKeyStore
import com.bittool.signer.core.crypto.vault.JournalCipher
import com.bittool.signer.core.crypto.vault.KeystoreJournalCipher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Debug-only bindings of the UI-test harness (gaps.md G3; ARCHITECTURE §4.10, §6 item 10 — `Optional<@UiTestOverride T>`
 * replacements, because Hilt cannot replace production modules outside tests). Every provider returns the production
 * behaviour while no run is active, so a debug build used by hand is a normal app. The biometric override is in
 * `DebugBiometricModule` (Java: the production authenticator is Kotlin-internal to `:core:security`).
 *
 * Bindings are made when first injected, which is after `MainActivity` configured the run.
 */
@Module
@InstallIn(SingletonComponent::class)
object DebugUiTestModule {
    /** The active run (or none) for every `Optional<UiTestEnvironment>` consumer: storage root, preferences file. */
    @Provides
    fun uiTestEnvironment(): UiTestEnvironment = DebugUiTestHarness

    /** The run's file-backed keys, or Android Keystore without a run. */
    @Provides
    @Singleton
    @UiTestOverride
    fun deviceKeyStore(keystore: Provider<AndroidKeystoreDeviceKeyStore>): DeviceKeyStore =
        DebugUiTestHarness.run?.let { FixtureDeviceKeyStore(fixtureKeys(it.root)) } ?: keystore.get()

    /**
     * The run's file-backed Activity journal key (QA round 1 sign-01: the Keystore journal key needs a screen lock the
     * harness emulator does not have), or the Keystore cipher without a run.
     */
    @Provides
    @Singleton
    @UiTestOverride
    fun journalCipher(keystore: Provider<KeystoreJournalCipher>): JournalCipher =
        DebugUiTestHarness.run?.let { FixtureJournalCipher(File(fixtureKeys(it.root), FixtureJournalCipher.KEY_FILE)) }
            ?: keystore.get()
}

/** The run's `FixtureKeys/` directory: the wallet keys, the public-data key and the journal key. */
private fun fixtureKeys(root: File) = File(root, FixtureDeviceKeyStore.DIRECTORY)
