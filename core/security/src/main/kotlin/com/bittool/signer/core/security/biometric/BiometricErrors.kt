package com.bittool.signer.core.security.biometric

import androidx.biometric.BiometricPrompt
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.security.BiometricCancellationException
import com.bittool.signer.core.security.BiometricCancellationException.Reason
import com.bittool.signer.core.security.BiometricException

/**
 * The error mapping of app-shell.md §15.6, the Android counterpart of the iOS `LAError` switch
 * (AppAccessController.swift:63-75): cancellations are silent, lockout and unavailability have their own texts, and
 * everything else is "Authentication failed. Try again or use a wallet password.".
 */
internal object BiometricErrors {
    /** What `onAuthenticationError(code, _)` turns into. The platform message is never shown (ARCHITECTURE.md §4.2). */
    fun forErrorCode(code: Int): Exception = when (code) {
        BiometricPrompt.ERROR_NEGATIVE_BUTTON -> BiometricCancellationException(Reason.NegativeButton)
        BiometricPrompt.ERROR_USER_CANCELED -> BiometricCancellationException(Reason.UserCanceled)
        BiometricPrompt.ERROR_CANCELED -> BiometricCancellationException(Reason.SystemCanceled)
        BiometricPrompt.ERROR_TIMEOUT -> BiometricCancellationException(Reason.Timeout)
        BiometricPrompt.ERROR_LOCKOUT,
        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
        -> lockout()
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
        -> unavailable()
        else -> failed()
    }

    /** iOS: "Biometric authentication is unavailable. Check Face ID or Touch ID in iOS Settings, …" (G7.4 wording). */
    fun unavailable(): BiometricException =
        BiometricException(BiometricException.Kind.Unavailable, UiText.Resource(R.string.security_biometric_unavailable))

    /** iOS `biometryLockout` (G7.4 wording: "unlock your phone with its screen lock first"). */
    fun lockout(): BiometricException =
        BiometricException(BiometricException.Kind.Lockout, UiText.Resource(R.string.security_biometric_lockout))

    /** iOS default branch and `success == false`. */
    fun failed(): BiometricException =
        BiometricException(BiometricException.Kind.Failed, UiText.Ios("Authentication failed. Try again or use a wallet password."))
}
