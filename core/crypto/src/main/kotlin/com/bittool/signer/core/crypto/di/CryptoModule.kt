package com.bittool.signer.core.crypto.di

import android.content.Context
import com.bittool.signer.core.common.harness.UiTestOverride
import com.bittool.signer.core.crypto.Bip39Seed
import com.bittool.signer.core.crypto.Pbkdf2Bip39Seed
import com.bittool.signer.core.crypto.SecureRandomSource
import com.bittool.signer.core.crypto.SystemSecureRandomSource
import com.bittool.signer.core.crypto.bip39.Bip39
import com.bittool.signer.core.crypto.bip39.Bip39WordlistSource
import com.bittool.signer.core.crypto.bip39.DefaultBip39
import com.bittool.signer.core.crypto.vault.AndroidKeystoreDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DefaultWalletVault
import com.bittool.signer.core.crypto.vault.DeviceKeyStore
import com.bittool.signer.core.crypto.vault.JournalCipher
import com.bittool.signer.core.crypto.vault.KeystoreJournalCipher
import com.bittool.signer.core.crypto.vault.WalletVault
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Provider
import javax.inject.Singleton

/**
 * `:core:crypto` bindings (ARCHITECTURE.md §4.10). [DeviceKeyStore] and [JournalCipher] are the debug UI-test harness's
 * file-backed fakes when `app/src/debug` provides `@UiTestOverride DeviceKeyStore` / `@UiTestOverride JournalCipher`
 * (gaps.md G3; QA round 1 sign-01: the harness emulator has no screen lock, so the production journal key, which is
 * `setUnlockedDeviceRequired`, cannot be generated there), else the Android Keystore implementations; release builds
 * bind no override, so both optionals are always empty there.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {
    @BindsOptionalOf
    @UiTestOverride
    abstract fun uiTestDeviceKeyStore(): DeviceKeyStore

    @Binds abstract fun walletVault(vault: DefaultWalletVault): WalletVault

    @BindsOptionalOf
    @UiTestOverride
    abstract fun uiTestJournalCipher(): JournalCipher

    @Binds abstract fun bip39Seed(seed: Pbkdf2Bip39Seed): Bip39Seed

    @Binds abstract fun secureRandom(random: SystemSecureRandomSource): SecureRandomSource

    companion object {
        @Provides
        @Singleton
        fun deviceKeyStore(
            @UiTestOverride override: Optional<DeviceKeyStore>,
            keystore: Provider<AndroidKeystoreDeviceKeyStore>,
        ): DeviceKeyStore = if (override.isPresent) override.get() else keystore.get()

        @Provides
        @Singleton
        fun journalCipher(
            @UiTestOverride override: Optional<JournalCipher>,
            keystore: Provider<KeystoreJournalCipher>,
        ): JournalCipher = if (override.isPresent) override.get() else keystore.get()

        /** Wordlists load lazily from the APK assets on first use (no I/O at injection time). */
        @Provides
        @Singleton
        fun bip39(@ApplicationContext context: Context): Bip39 = DefaultBip39(Bip39WordlistSource.assets(context.assets))
    }
}
