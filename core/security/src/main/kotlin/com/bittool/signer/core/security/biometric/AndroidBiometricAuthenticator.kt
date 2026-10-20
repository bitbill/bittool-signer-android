package com.bittool.signer.core.security.biometric

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.bittool.signer.core.common.log.Log
import com.bittool.signer.core.l10n.resolve
import com.bittool.signer.core.security.AppLifecycleMonitor
import com.bittool.signer.core.security.BiometricAuthenticator
import com.bittool.signer.core.security.BiometricCancellationException
import com.bittool.signer.core.security.BiometricCancellationException.Reason
import com.bittool.signer.core.security.BiometricPromptSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * `androidx.biometric.BiometricPrompt` behind [BiometricAuthenticator] (app-shell.md §15.6), the counterpart of iOS
 * `BiometricAuthentication` (AppAccessController.swift:53-79):
 *
 * - `BIOMETRIC_STRONG` only, never `DEVICE_CREDENTIAL`: "No device-passcode fallback: the only alternative is a wallet
 *   password." (:60). A device credential would also forbid the negative button and, on API ≤ 29, launch a separate
 *   activity whose `onStop` would count as a background transition and cancel the very authentication it serves.
 * - No `CryptoObject`: App Lock gates the UI only and never releases a key.
 * - `setConfirmationRequired(false)`: a recognised face unlocks at once, like Face ID.
 * - Shown only on the RESUMED activity of [AppLifecycleMonitor.foregroundActivity]; otherwise
 *   [BiometricCancellationException] ([Reason.NotResumed]) at once. `BiometricPrompt.authenticate` silently does
 *   nothing after `onSaveInstanceState`, which would leave the caller suspended forever.
 * - One prompt at a time: a new request cancels the previous one. [cancel] (background, iOS `context.invalidate()`) and
 *   cancelling the calling coroutine both dismiss the prompt.
 *
 * All prompt state is confined to the main thread.
 */
@Singleton
internal class AndroidBiometricAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lifecycle: AppLifecycleMonitor,
) : BiometricAuthenticator {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var active: Session? = null

    override fun isAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun authenticate(prompt: BiometricPromptSpec): Unit = withContext(Dispatchers.Main.immediate) {
        cancel()
        val activity = lifecycle.foregroundActivity
        if (activity == null || !activity.canShowPrompt()) throw BiometricCancellationException(Reason.NotResumed)
        // iOS evaluates `canEvaluatePolicy` before prompting and reports the unavailable text (:63-66).
        if (!isAvailable()) throw BiometricErrors.unavailable()
        val info = promptInfo(prompt, activity.resources)
        val session = Session()
        val biometricPrompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), session.callback)
        session.prompt = biometricPrompt
        active = session
        try {
            suspendCancellableCoroutine { continuation ->
                session.continuation = continuation
                try {
                    biometricPrompt.authenticate(info)
                } catch (e: RuntimeException) {
                    Log.w(TAG, e) { "BiometricPrompt refused to start" }
                    session.finish(BiometricErrors.failed())
                }
            }
        } finally {
            if (active === session) active = null
            // The caller was cancelled while the prompt was up: take it off the screen.
            if (!session.finished) {
                session.finished = true
                biometricPrompt.cancelAuthentication()
            }
        }
    }

    override fun cancel() {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            mainHandler.post { cancel() }
            return
        }
        val session = active ?: return
        active = null
        session.finish(BiometricCancellationException(Reason.SystemCanceled))
        session.prompt?.cancelAuthentication()
    }

    private fun FragmentActivity.canShowPrompt(): Boolean =
        !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !supportFragmentManager.isStateSaved

    /** One prompt's outcome: resumed exactly once, by the callback, by [cancel] or by the caller's cancellation. */
    private class Session {
        var prompt: BiometricPrompt? = null
        var continuation: CancellableContinuation<Unit>? = null
        var finished = false

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (finished) return
                finished = true
                continuation?.takeIf { it.isActive }?.resume(Unit)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                finish(BiometricErrors.forErrorCode(errorCode))
            }

            // One non-matching attempt is not terminal: the prompt stays up and lets the person try again.
            override fun onAuthenticationFailed() = Unit
        }

        fun finish(error: Exception) {
            if (finished) return
            finished = true
            continuation?.takeIf { it.isActive }?.resumeWithException(error)
        }
    }

    internal companion object {
        private const val TAG = "Biometric"

        /** The prompt as the person sees it, in the app language of [resources]. */
        fun promptInfo(prompt: BiometricPromptSpec, resources: Resources): BiometricPrompt.PromptInfo =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(prompt.title.resolve(resources))
                .apply { prompt.description?.let { setDescription(it.resolve(resources)) } }
                .setNegativeButtonText(prompt.negativeButton.resolve(resources))
                .setAllowedAuthenticators(BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
    }
}
