package com.bittool.signer.core.scanner

import com.bittool.signer.core.model.QrCodeEncoder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hilt bindings of `:core:scanner` (ARCHITECTURE.md §4.10): the app's [QrCodeEncoder] is ZXing's. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScannerModule {
    @Binds
    abstract fun bindQrCodeEncoder(encoder: ZxingQrCodeEncoder): QrCodeEncoder
}
