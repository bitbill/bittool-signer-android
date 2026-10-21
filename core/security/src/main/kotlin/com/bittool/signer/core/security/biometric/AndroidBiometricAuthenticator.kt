package com.bittool.signer.core.security.biometric

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
 * - `BIOMETRIC_WEAK` (Class 2 and 3; owner decision: App Lock only gates public pages, e.g. Huawei face unlock is Class 2), never `DEVICE_CREDENTIAL`: "No device-passcode fallback: the only alternative is a wallet
 *   password." (:60). A device credential would also forbid the negative button and, on API ≤ 29, launch a separate
 *   activity whose `onStop` would count as a background transition and cancel the very authentication it serves.
 * - No `CryptoObject`: App Lock gates the UI only and never releases a key.
 * - `setConfirmationRequired(false)`: a recognised face unlocks at once, like Face ID.
 * - Shown only on the RESUMED activity of [AppLifecycleMonitor.foregroundActivity]; otherwise
 *   [BiometricCancellationException] ([Reason.NotResumed]) at once. `BiometricPrompt.authenticate` silently does
 *   nothing after `onSaveInstanceState`, which would leave the caller suspended forever.
 * - One prompt at a time: a new request ends the previous caller ([Reason.SystemCanceled]) and takes its prompt off
 *   the screen. [cancel] (background, iOS `context.invalidate()`) and cancelling the calling coroutine do the same.
 * - **Strictly sequential on the platform** ([BiometricPlatform]): androidx keeps one client callback per activity and
 *   reports a cancelled prompt's end asynchronously. A new prompt is therefore handed to the platform only after the
 *   previous one reported its end (at most [PREVIOUS_PROMPT_END_TIMEOUT_MILLIS]); otherwise it would not be shown, and
 *   the previous prompt's late outcome — a success the person gave to another prompt — would complete it.
 * - A prompt whose activity is destroyed (recreated for a configuration change the manifest does not handle, such as
 *   attaching a keyboard) ends as [Reason.SystemCanceled]: its callback goes with the activity, and an unfinished
 *   prompt would keep App Lock "authenticating" and refusing every other attempt.
 *
 * All prompt state is confined to the main thread.
 */
@Singleton
internal class AndroidBiometricAuthenticator(
    private val context: Context,
    private val lifecycle: AppLifecycleMonitor,
    private val platform: BiometricPlatform,
) : BiometricAuthenticator {

    @Inject
    constructor(@ApplicationContext context: Context, lifecycle: AppLifecycleMonitor) :
        this(context, lifecycle, BiometricPlatform.AndroidX)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The prompt whose caller is waiting for an outcome. */
    private var active: Session? = null

    /** The last prompt handed to the platform, until the platform has nothing left of it. */
    private var launched: Session? = null

    override fun isAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    override suspend fun authenticate(prompt: BiometricPromptSpec): Unit = withContext(Dispatchers.Main.immediate) {
        cancel()
        val activity = lifecycle.foregroundActivity
        if (activity == null || !activity.canShowPrompt()) throw BiometricCancellationException(Reason.NotResumed)
        // iOS evaluates `canEvaluatePolicy` before prompting and reports the unavailable text (:63-66).
        if (!isAvailable()) throw BiometricErrors.unavailable()
        val session = Session(activity, promptInfo(prompt, activity.resources))
        val previous = launched
        active = session
        try {
            suspendCancellableCoroutine { continuation ->
                session.continuation = continuation
                session.startAfter(previous)
            }
        } finally {
            if (active === session) active = null
            // The caller was cancelled while the prompt was up (or still waiting): take it off the screen.
            session.abandon()
        }
    }

    override fun cancel() {
        if (Looper.myLooper() !== Looper.getMainLooper()) {
            mainHandler.post { cancel() }
            return
        }
        val session = active ?: return
        active = null
        session.abandon()
    }

    private fun FragmentActivity.canShowPrompt(): Boolean =
        !isFinishing && !isDestroyed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            !supportFragmentManager.isStateSaved

    /**
     * One prompt: its caller's outcome (resumed exactly once: by the platform, by [abandon] or by the destroyed
     * activity) and its platform life (from [start] until the platform reported an outcome, it never started, or its
     * activity is gone — [ended]).
     */
    private inner class Session(activity: FragmentActivity, private val info: BiometricPrompt.PromptInfo) :
        BiometricPlatform.Outcome {

        var continuation: CancellableContinuation<Unit>? = null
        private var host: FragmentActivity? = activity
        private var handle: BiometricPlatform.Handle? = null
        private var finished = false
        private var started = false
        private var cancelRequested = false
        var ended = false
            private set
        private val endListeners = ArrayList<() -> Unit>(1)
        private val startNow = Runnable { start() }
        private val hostObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                finish(BiometricCancellationException(Reason.SystemCanceled))
                end()
            }
        }

        init {
            activity.lifecycle.addObserver(hostObserver)
        }

        /** Starts now, or once [previous] has ended (bounded: a platform that never reports must not block biometrics). */
        fun startAfter(previous: Session?) {
            if (previous == null || previous.ended) {
                start()
                return
            }
            previous.endListeners += { mainHandler.post(startNow) }
            mainHandler.postDelayed(startNow, PREVIOUS_PROMPT_END_TIMEOUT_MILLIS)
        }

        private fun start() {
            if (started) return
            started = true
            mainHandler.removeCallbacks(startNow)
            val activity = host
            if (finished || activity == null || !activity.canShowPrompt()) {
                finish(BiometricCancellationException(Reason.NotResumed))
                end()
                return
            }
            // A prompt that just ended removes its fragment with a posted transaction; without flushing it, androidx
            // would reuse the departing fragment, and this prompt's outcome would go with it.
            try {
                activity.supportFragmentManager.executePendingTransactions()
            } catch (e: IllegalStateException) {
                Log.w(TAG, e) { "Pending fragment transactions could not run before the prompt" }
            }
            launched = this
            handle = try {
                platform.launch(activity, info, this)
            } catch (e: RuntimeException) {
                Log.w(TAG, e) { "BiometricPrompt refused to start" }
                finish(BiometricErrors.failed())
                end()
                null
            }
        }

        override fun succeeded() {
            if (!finished) {
                finished = true
                continuation?.takeIf { it.isActive }?.resume(Unit)
            }
            end()
        }

        override fun failed(errorCode: Int) {
            finish(BiometricErrors.forErrorCode(errorCode))
            end()
        }

        /**
         * The caller is gone or replaced: ends it as a system cancel and takes the prompt off the screen (once; a prompt
         * that already ended or never started has nothing to cancel).
         */
        fun abandon() {
            finish(BiometricCancellationException(Reason.SystemCanceled))
            if (!started) {
                started = true
                end()
            } else if (!ended && !cancelRequested) {
                cancelRequested = true
                handle?.cancel()
            }
        }

        private fun finish(error: Exception) {
            if (finished) return
            finished = true
            continuation?.takeIf { it.isActive }?.resumeWithException(error)
        }

        /** The platform has nothing left of this prompt: the next one may start. */
        private fun end() {
            if (ended) return
            ended = true
            mainHandler.removeCallbacks(startNow)
            host?.lifecycle?.removeObserver(hostObserver)
            host = null
            handle = null
            if (launched === this) launched = null
            val listeners = endListeners.toList()
            endListeners.clear()
            listeners.forEach { it() }
        }
    }

    internal companion object {
        private const val TAG = "Biometric"

        /** How long a new prompt waits for a cancelled one to report its end (normally a few tens of milliseconds). */
        const val PREVIOUS_PROMPT_END_TIMEOUT_MILLIS = 1_000L

        /** The prompt as the person sees it, in the app language of [resources]. */
        fun promptInfo(prompt: BiometricPromptSpec, resources: Resources): BiometricPrompt.PromptInfo =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(prompt.title.resolve(resources))
                .apply { prompt.description?.let { setDescription(it.resolve(resources)) } }
                .setNegativeButtonText(prompt.negativeButton.resolve(resources))
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .setConfirmationRequired(false)
                .build()
    }
}
