package com.bittool.signer.core.security.biometric

import androidx.biometric.BiometricPrompt
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.security.BiometricCancellationException
import com.bittool.signer.core.security.BiometricCancellationException.Reason
import com.bittool.signer.core.security.BiometricException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

/** The error mapping of app-shell.md §15.6 (iOS AppAccessController.swift:63-75). */
class BiometricErrorsTest {

    @Test
    fun dismissalsAreSilentCancellations() {
        val expected = mapOf(
            BiometricPrompt.ERROR_NEGATIVE_BUTTON to Reason.NegativeButton,
            BiometricPrompt.ERROR_USER_CANCELED to Reason.UserCanceled,
            BiometricPrompt.ERROR_CANCELED to Reason.SystemCanceled,
            BiometricPrompt.ERROR_TIMEOUT to Reason.Timeout,
        )
        for ((code, reason) in expected) {
            val error = assertIs<BiometricCancellationException>(BiometricErrors.forErrorCode(code))
            assertIs<CancellationException>(error)
            assertEquals(reason, error.reason)
        }
    }

    @Test
    fun lockoutAndUnavailabilityHaveTheirAndroidTexts() {
        for (code in listOf(BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT)) {
            val error = assertIs<BiometricException>(BiometricErrors.forErrorCode(code))
            assertEquals(BiometricException.Kind.Lockout, error.kind)
            assertEquals(UiText.Resource(R.string.security_biometric_lockout), error.text)
        }
        for (code in listOf(
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
        )) {
            val error = assertIs<BiometricException>(BiometricErrors.forErrorCode(code))
            assertEquals(BiometricException.Kind.Unavailable, error.kind)
            assertEquals(UiText.Resource(R.string.security_biometric_unavailable), error.text)
        }
    }

    @Test
    fun everythingElseIsAuthenticationFailed() {
        for (code in listOf(
            BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
            BiometricPrompt.ERROR_NO_SPACE,
            BiometricPrompt.ERROR_VENDOR,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            9_999,
        )) {
            val error = assertIs<BiometricException>(BiometricErrors.forErrorCode(code))
            assertEquals(BiometricException.Kind.Failed, error.kind)
            assertEquals(UiText.Ios("Authentication failed. Try again or use a wallet password."), error.text)
        }
    }
}
