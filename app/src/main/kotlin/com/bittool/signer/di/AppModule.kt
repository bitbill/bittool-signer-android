package com.bittool.signer.di

import android.content.Context
import com.bittool.signer.BuildConfig
import com.bittool.signer.appearance.AppearanceController
import com.bittool.signer.identity.SigningCertificate
import com.bittool.signer.core.common.BuildInfo
import com.bittool.signer.core.common.Distribution
import com.bittool.signer.core.engine.EngineModule
import com.bittool.signer.core.security.AppLifecycleMonitor
import com.bittool.signer.core.security.SecureWindowPolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * `:app`'s bindings (ARCHITECTURE.md §4.10): the facts of this build and the engine's test-operation switch. The
 * distribution comes from the flavor's own `DistributionModule` (`src/apk`, `src/play`), so no code tests a
 * `BuildConfig` string.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Test-only engine operations exist in debug builds only (`EngineModule`): never in a release. */
    @Provides
    @Named(EngineModule.ENGINE_TEST_OPERATIONS)
    fun engineTestOperations(): Boolean = BuildConfig.DEBUG

    /**
     * Settings › About and Open Source › This build. The version is "1.0.0 (1)" like iOS: the build number defaults to
     * 1 (`-Psigner.buildNumber`, DECISIONS §10), so [BuildInfo.displayVersion] matches the iOS `CURRENT_PROJECT_VERSION`.
     * The signing certificate is read once from `PackageManager` (first signer, SHA-256 as lowercase hex, the apksigner form).
     */
    @Provides
    @Singleton
    fun buildInfo(@ApplicationContext context: Context, distribution: Distribution): BuildInfo = BuildInfo(
        applicationId = BuildConfig.APPLICATION_ID,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        distribution = distribution,
        isDebug = BuildConfig.DEBUG,
        repositoryUrl = BuildConfig.REPOSITORY_URL,
        signingCertificateSha256 = SigningCertificate.sha256(context),
    )
}

/**
 * What `MainActivity` needs **before** `super.onCreate()` (before Hilt injects the activity): the secure-window policy
 * (FLAG_SECURE must precede the first frame), the lifecycle monitor (it observes every lifecycle edge, including the
 * first `ON_CREATE`) and the appearance controller (the night mode must be known before AppCompat applies it).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ShellEntryPoint {
    fun secureWindowPolicy(): SecureWindowPolicy
    fun lifecycleMonitor(): AppLifecycleMonitor
    fun appearance(): AppearanceController
}
