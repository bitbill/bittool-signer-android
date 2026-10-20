package com.bittool.signer.core.security

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/**
 * How a biometric prompt ended without a result (contract addition of W1-security, compatible with
 * [BiometricAuthenticator.authenticate]'s "throws `CancellationException`"). Every caller that treats cancellation as
 * silent keeps working unchanged; the lock screen may additionally look at [reason] to switch to the password form
 * only when the person chose "Use a Wallet Password" (app-shell.md §15.6), and to leave everything as it was after a
 * system cancel (the app went to the background) — iOS shows nothing in either case (AppAccessController.swift:72).
 */
class BiometricCancellationException(val reason: Reason) : CancellationException("Biometric prompt ended: $reason") {
    enum class Reason {
        /** `ERROR_NEGATIVE_BUTTON`: the prompt's negative button ("Use a Wallet Password" or "Cancel"). */
        NegativeButton,

        /** `ERROR_USER_CANCELED`: Back, a tap outside, or the system's own cancel affordance. */
        UserCanceled,

        /** `ERROR_CANCELED` or [BiometricAuthenticator.cancel] (background, a newer prompt): iOS `systemCancel`/`appCancel`. */
        SystemCanceled,

        /** `ERROR_TIMEOUT`: the prompt timed out without a decision. */
        Timeout,

        /** No resumed activity to show the prompt on (it is only shown while the activity is RESUMED). */
        NotResumed,
    }
}

/**
 * The synchronous signals the recovery sessions observe: the Android mapping of the iOS notifications that
 * `RecoveryPrivacySession` and `RecoveryScreenshotSession` subscribe to (app-shell.md §15.8: screenshot →
 * `ScreenCaptureCallback`, `willResignActive` → `ON_PAUSE`, `didEnterBackground` → `ON_STOP` without a configuration
 * change, `capturedDidChange` → screen-recording callback, `didBecomeActive` → `ON_RESUME`).
 */
enum class PrivacySignal {
    /** `onStart` after a stay in the background (iOS `willEnterForegroundNotification`). */
    WillEnterForeground,

    /** `onResume` (iOS `didBecomeActiveNotification`). */
    DidBecomeActive,

    /** `onPause` (iOS `willResignActiveNotification`). */
    WillResignActive,

    /** `onStop` without a configuration change (iOS `didEnterBackgroundNotification`). */
    DidEnterBackground,

    /** A user screenshot, reported after the fact (iOS `userDidTakeScreenshotNotification`). */
    Screenshot,

    /** Screen recording or mirroring started or stopped (iOS `UIScreen.capturedDidChangeNotification`). */
    CaptureChanged,
}

/**
 * iOS `NotificationCenter` for [PrivacySignal]s, injected into the recovery sessions exactly as the iOS sessions take a
 * `center:` (RecoveryPrivacySession.swift:25): observers run synchronously on the posting (main) thread, in
 * registration order, so a session has concealed its content before the posting lifecycle callback returns.
 */
fun interface PrivacySignalCenter {
    /** Registers [observer] until the returned handle is closed (closing twice is harmless). */
    fun observe(observer: (PrivacySignal) -> Unit): AutoCloseable
}

/**
 * The default [PrivacySignalCenter]: a synchronous multicast. Observers registered or removed while a signal is being
 * delivered take effect immediately: a removed observer that has not run yet is skipped (a callback that unmounts a
 * session must not let it react to the same signal). Main thread only.
 */
class PrivacySignalDispatcher : PrivacySignalCenter {
    private class Registration(val observer: (PrivacySignal) -> Unit) {
        @Volatile var active = true
    }

    private val registrations = CopyOnWriteArrayList<Registration>()

    /** Live observers (tests use it to prove that re-mounting never accumulates observers). */
    val observerCount: Int get() = registrations.size

    override fun observe(observer: (PrivacySignal) -> Unit): AutoCloseable {
        val registration = Registration(observer)
        registrations += registration
        return AutoCloseable {
            registration.active = false
            registrations.remove(registration)
        }
    }

    /** Delivers [signal] to every live observer, synchronously. */
    fun post(signal: PrivacySignal) {
        for (registration in registrations) {
            if (registration.active) registration.observer(signal)
        }
    }
}
