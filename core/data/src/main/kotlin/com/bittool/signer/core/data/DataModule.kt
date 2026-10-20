package com.bittool.signer.core.data

import com.bittool.signer.core.data.activity.EncryptedReceiptRepository
import com.bittool.signer.core.data.preferences.DataStoreSignerPreferences
import com.bittool.signer.core.data.reference.AssetLegalDocumentRepository
import com.bittool.signer.core.data.reference.AssetReferenceDataRepository
import com.bittool.signer.core.data.reference.PackTokenIconSource
import com.bittool.signer.core.data.wallet.FilePublicWalletRepository
import com.bittool.signer.core.model.TokenIconSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * `:core:data`'s bindings (ARCHITECTURE.md §4.10). Every implementation is a `@Singleton` with an `@Inject`
 * constructor. Consumed from other modules: `WalletVault`, `JournalCipher`, `Bip39`, `Bip39Seed`,
 * `SecureRandomSource` (`:core:crypto`'s `CryptoModule`), `QrCodeEncoder` (`:core:scanner`'s `ScannerModule`),
 * `SignerEngine` / `EngineCalls` (`:core:engine`), dispatchers, clocks, `@SignerDataDir` and
 * `Optional<UiTestEnvironment>` (`:core:common`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun signerStore(store: DefaultSignerStore): SignerStore

    @Binds abstract fun signerPreferences(preferences: DataStoreSignerPreferences): SignerPreferences

    @Binds abstract fun publicWalletRepository(repository: FilePublicWalletRepository): PublicWalletRepository

    @Binds abstract fun receiptRepository(repository: EncryptedReceiptRepository): ReceiptRepository

    @Binds abstract fun referenceDataRepository(repository: AssetReferenceDataRepository): ReferenceDataRepository

    @Binds abstract fun legalDocumentRepository(repository: AssetLegalDocumentRepository): LegalDocumentRepository

    @Binds abstract fun tokenIconSource(source: PackTokenIconSource): TokenIconSource
}
