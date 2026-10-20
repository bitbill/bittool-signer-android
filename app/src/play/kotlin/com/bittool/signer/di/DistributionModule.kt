package com.bittool.signer.di

import com.bittool.signer.core.common.Distribution
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The `play` distribution: Google Play (App Bundle, DECISIONS.md §1). What differs from `apk` is expressed by this
 * binding (the "Distribution" row of Settings says "Google Play"; the signing-certificate row shows the Play App
 * Signing certificate, equal to the APK's under ownbit-build-release.md §7.4 option A) and by the flavor's own legal
 * texts (`src/play/assets/legal`: the Google Play clauses). No store links: the app opens no other app.
 */
@Module
@InstallIn(SingletonComponent::class)
object DistributionModule {
    @Provides
    fun distribution(): Distribution = Distribution.GooglePlay
}
