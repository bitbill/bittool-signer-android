package com.bittool.signer.di

import com.bittool.signer.core.common.Distribution
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The `apk` distribution: the APK downloaded from bittool.com/signer (DECISIONS.md §1). What differs from `play` is
 * expressed by this binding (Settings › About and Open Source › This build read it through `BuildInfo`, the
 * "Distribution" row says "Direct download (APK)" and the signing-certificate row is what users compare with the
 * published fingerprint) and by the flavor's own legal texts (`src/apk/assets/legal`: no store clause, the direct-
 * download clause with the APK verification steps). No network, no updater: both flavors are offline.
 */
@Module
@InstallIn(SingletonComponent::class)
object DistributionModule {
    @Provides
    fun distribution(): Distribution = Distribution.DirectApk
}
