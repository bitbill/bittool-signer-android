package com.bittool.signer.core.security.biometric

import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * The platform half of a biometric prompt, behind a seam so [AndroidBiometricAuthenticator]'s sequencing can be tested
 * against a scripted double: androidx `BiometricPrompt` in the app.
 *
 * What the sequencing has to respect (androidx.biometric 1.1.0, `BiometricFragment` / `BiometricViewModel`): one
 * activity has **one** client callback, replaced by every `BiometricPrompt` constructed on it and looked up only when
 * an outcome is delivered; a cancelled prompt reports its end (`ERROR_CANCELED`) asynchronously and counts as showing
 * until then, so a prompt requested in between is not shown at all, and the cancelled prompt's late outcome — even a
 * success — is delivered to the newer prompt's callback.
 */
internal fun interface BiometricPlatform {

    /** Shows [info] on [activity]; [outcome] is called on the main thread, at most once per terminal event. */
    fun launch(activity: FragmentActivity, info: BiometricPrompt.PromptInfo, outcome: Outcome): Handle

    /** The terminal events of one prompt. A non-matching attempt is not terminal and is not reported. */
    interface Outcome {
        fun succeeded()

        /** `BiometricPrompt.ERROR_*`; the platform's own message is never shown (ARCHITECTURE.md §4.2). */
        fun failed(errorCode: Int)
    }

    /** Takes a launched prompt off the screen; it then reports `ERROR_CANCELED`. */
    fun interface Handle {
        fun cancel()
    }

    companion object {
        /** androidx `BiometricPrompt` on the activity's main executor. */
        val AndroidX = BiometricPlatform { activity, info, outcome ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = outcome.succeeded()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = outcome.failed(errorCode)

                // One non-matching attempt is not terminal: the prompt stays up and lets the person try again.
                override fun onAuthenticationFailed() = Unit
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            prompt.authenticate(info)
            Handle { prompt.cancelAuthentication() }
        }
    }
}
