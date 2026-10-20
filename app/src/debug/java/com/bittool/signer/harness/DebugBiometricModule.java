package com.bittool.signer.harness;

import com.bittool.signer.core.common.harness.UiTestOverride;
import com.bittool.signer.core.security.BiometricAuthenticator;
import com.bittool.signer.core.security.biometric.AndroidBiometricAuthenticator;
import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Provider;
import javax.inject.Singleton;

/**
 * The UI-test run's biometric replacement (gaps.md G3): the always-failing {@link FixtureBiometricAuthenticator} while
 * a run is active, the production {@code BiometricPrompt} authenticator otherwise, so a debug build used by hand keeps
 * real biometrics. {@code :core:security} binds {@code BiometricAuthenticator} to this override whenever it is
 * present, and in debug builds it always is.
 *
 * <p>Written in Java on purpose: {@code AndroidBiometricAuthenticator} is {@code internal} to {@code :core:security}
 * (a Kotlin visibility; the class is public to Java), and its frozen contract offers no other way to reach the
 * production instance. Debug builds only.
 */
@Module
@InstallIn(SingletonComponent.class)
public final class DebugBiometricModule {
    private DebugBiometricModule() {
    }

    @Provides
    @Singleton
    @UiTestOverride
    static BiometricAuthenticator biometricAuthenticator(Provider<AndroidBiometricAuthenticator> device) {
        if (DebugUiTestHarness.INSTANCE.getRun() != null) return new FixtureBiometricAuthenticator();
        return device.get();
    }
}
