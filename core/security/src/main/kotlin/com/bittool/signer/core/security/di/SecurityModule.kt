package com.bittool.signer.core.security.di

import com.bittool.signer.core.common.harness.UiTestOverride
import com.bittool.signer.core.security.AppAccessController
import com.bittool.signer.core.security.AppLifecycleMonitor
import com.bittool.signer.core.security.BiometricAuthenticator
import com.bittool.signer.core.security.LockWindowController
import com.bittool.signer.core.security.RecoverySessionFactory
import com.bittool.signer.core.security.SecureWindowPolicy
import com.bittool.signer.core.security.WindowGate
import com.bittool.signer.core.security.access.DefaultAppAccessController
import com.bittool.signer.core.security.biometric.AndroidBiometricAuthenticator
import com.bittool.signer.core.security.lifecycle.DefaultAppLifecycleMonitor
import com.bittool.signer.core.security.privacy.DefaultRecoverySessionFactory
import com.bittool.signer.core.security.window.DefaultLockWindowController
import com.bittool.signer.core.security.window.DefaultSecureWindowPolicy
import com.bittool.signer.core.security.window.DefaultWindowGate
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.Optional
import javax.inject.Provider
import javax.inject.Singleton

/**
 * `:core:security` bindings (ARCHITECTURE.md §4.10). Everything is a process singleton: App Lock state survives
 * configuration changes but not process death (a new process is a cold launch and locks again, iOS parity).
 *
 * `BiometricAuthenticator` follows the debug UI-test replacement pattern (`UiTestEnvironment.kt`): `app/src/debug`
 * may provide `@UiTestOverride BiometricAuthenticator` (gaps.md G3: always fails with its fixed message); release
 * builds provide nothing and get [AndroidBiometricAuthenticator].
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class SecurityModule {
    @Binds abstract fun appAccessController(impl: DefaultAppAccessController): AppAccessController

    @Binds abstract fun appLifecycleMonitor(impl: DefaultAppLifecycleMonitor): AppLifecycleMonitor

    @Binds abstract fun recoverySessionFactory(impl: DefaultRecoverySessionFactory): RecoverySessionFactory

    @Binds abstract fun lockWindowController(impl: DefaultLockWindowController): LockWindowController

    @Binds abstract fun windowGate(impl: DefaultWindowGate): WindowGate

    @Binds abstract fun secureWindowPolicy(impl: DefaultSecureWindowPolicy): SecureWindowPolicy

    @BindsOptionalOf @UiTestOverride abstract fun biometricAuthenticatorOverride(): BiometricAuthenticator

    companion object {
        @Provides
        @Singleton
        fun biometricAuthenticator(
            @UiTestOverride override: Optional<BiometricAuthenticator>,
            device: Provider<AndroidBiometricAuthenticator>,
        ): BiometricAuthenticator = if (override.isPresent) override.get() else device.get()
    }
}
