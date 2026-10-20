package com.bittool.signer.harness

import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.security.BiometricAuthenticator
import com.bittool.signer.core.security.BiometricPromptSpec

/**
 * The UI-test run's biometrics (gaps.md G3; iOS `SimulatorFixtureBiometrics`, SimulatorUITestContext.swift:59-62):
 * available, and every prompt fails with a fixed message, so App Lock's lock screen and Settings' switch show it and
 * the suite continues with a wallet password. Debug-harness English on purpose, not a string resource.
 */
internal class FixtureBiometricAuthenticator : BiometricAuthenticator {
    override fun isAvailable(): Boolean = true

    override suspend fun authenticate(prompt: BiometricPromptSpec) {
        throw AppIssue(MESSAGE)
    }

    override fun cancel() = Unit

    companion object {
        const val MESSAGE = "Biometric authentication was cancelled. Use a wallet password to continue."
    }
}
