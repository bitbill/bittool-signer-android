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
 * `:core:crypto` bindings (ARCHITECTURE.md §4.10). [DeviceKeyStore] is the debug UI-test harness's file-backed fake
 * when `app/src/debug` provides `@UiTestOverride DeviceKeyStore` (gaps.md G3), else the Android Keystore; release
 * builds bind no override, so the optional is always empty there.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {
    @BindsOptionalOf
    @UiTestOverride
    abstract fun uiTestDeviceKeyStore(): DeviceKeyStore

    @Binds abstract fun walletVault(vault: DefaultWalletVault): WalletVault

    @Binds abstract fun journalCipher(cipher: KeystoreJournalCipher): JournalCipher

    @Binds abstract fun bip39Seed(seed: Pbkdf2Bip39Seed): Bip39Seed

    @Binds abstract fun secureRandom(random: SystemSecureRandomSource): SecureRandomSource

    companion object {
        @Provides
        @Singleton
        fun deviceKeyStore(
            @UiTestOverride override: Optional<DeviceKeyStore>,
            keystore: Provider<AndroidKeystoreDeviceKeyStore>,
        ): DeviceKeyStore = if (override.isPresent) override.get() else keystore.get()

        /** Wordlists load lazily from the APK assets on first use (no I/O at injection time). */
        @Provides
        @Singleton
        fun bip39(@ApplicationContext context: Context): Bip39 = DefaultBip39(Bip39WordlistSource.assets(context.assets))
    }
}
