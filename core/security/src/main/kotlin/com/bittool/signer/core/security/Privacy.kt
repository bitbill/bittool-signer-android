package com.bittool.signer.core.security

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The iOS scene phases as Android sees them (app-shell.md §15.4). */
enum class ScenePhase {
    /** RESUMED. */
    Active,
    /** STARTED but not resumed (dialogs over the app, split screen, the permission prompt). */
    Inactive,
    /** Stopped (not for a configuration change). */
    Background,
}

/** Edges of [ScenePhase], in the order iOS reports them. */
enum class LifecycleEvent {
    /** `onStart`: App Lock's grace decision happens here, before the first frame. */
    WillEnterForeground,
    /** `onResume`. */
    DidBecomeActive,
    /** `onPause`: conceal recovery displays, hide revealed passphrases, stop the camera and torch. */
    WillResignActive,
    /** `onStop` with `!isChangingConfigurations`: `SignerStore.suspendSession()` then `AppAccessController.didEnterBackground()`. */
    DidEnterBackground,
}

/**
 * One source of lifecycle and capture signals, fed by `MainActivity` (W1-security implements, W2-shell attaches in
 * `onCreate`). Does not use `ProcessLifecycleOwner` for the background edge (its `ON_STOP` is ~700 ms late).
 */
interface AppLifecycleMonitor {
    val phase: StateFlow<ScenePhase>
    val events: SharedFlow<LifecycleEvent>

    /**
     * The screen is recorded or mirrored, as iOS `UIScreen.isCaptured`: a screen recording or cast shows the app (API 35+
     * `WindowManager.addScreenRecordingCallback`; below 35 the activity's `FLAG_SECURE` blanks recordings), or another
     * display that can mirror it is connected and on (every API level: an external, wireless or simulated presentation
     * display other than the built-in one, the activity's own and the app's private ones; `FLAG_SECURE` does not blank
     * secure external displays). Becoming true → `suspendSession()`, conceal, refuse reveal, show the "recorded or
     * mirrored" cover.
     */
    val isCaptured: StateFlow<Boolean>

    /** User screenshots (API 34+ `registerScreenCaptureCallback`; may never fire under FLAG_SECURE — defence in depth). */
    val screenshots: SharedFlow<Unit>

    /** The resumed activity for `BiometricPrompt`, or null. */
    val foregroundActivity: FragmentActivity?

    /** Called once by `MainActivity.onCreate` (before `setContent`). */
    fun attach(activity: FragmentActivity)
}

/**
 * Display permission for concealed recovery material (iOS `RecoveryPrivacySession`, app-shell.md §13.1): it never owns
 * a phrase. Used by the words grid, the phrase QR and the recovery cards; one instance per display. Concealed words
 * must not be composed at all (absent from the view and semantics trees, not blurred).
 */
interface RecoveryPrivacySession {
    val isRevealed: StateFlow<Boolean>

    /** Non-null after a screenshot: an alert "Screenshot Detected" must be acknowledged before any reveal. */
    val prompt: StateFlow<Prompt?>

    enum class Prompt { Screenshot }

    /** Mounts (from `DisposableEffect` enter): resets any reveal and starts observing; a re-start never accumulates observers. */
    fun start()

    /** Unmounts: stops observing, hides, clears the prompt. */
    fun stop()

    /** Tap-to-reveal is the consent: true and revealed only when mounted, resumed, not captured and no prompt pending. */
    fun reveal(): Boolean

    fun hide()

    /** The alert's OK: hides and clears the prompt; never reveals by itself. */
    fun acknowledgeWarning()
}

/**
 * Screenshot warnings for directly visible recovery material (iOS `RecoveryScreenshotSession`, §13.2): the import editor
 * and the backup check. Content visibility is not changed by this session.
 */
interface RecoveryScreenshotSession {
    /** The current warning; a newer screenshot replaces it with a new id. */
    val warning: StateFlow<Warning?>

    data class Warning(val id: Long)

    /** Mounts; [onScreenshot] runs on every screenshot **before** the warning is considered (it may unmount the view). */
    fun start(onScreenshot: () -> Unit)

    fun stop()

    /** Dismisses only when [warning] is the current one (a replaced alert's OK must not acknowledge a later screenshot). */
    fun acknowledge(warning: Warning)
}

/** Creates the per-display sessions above, wired to [AppLifecycleMonitor]. */
interface RecoverySessionFactory {
    fun privacySession(): RecoveryPrivacySession
    fun screenshotSession(): RecoveryScreenshotSession
}

/**
 * The App Lock window (app-shell.md §15.7, Ownbit `LockWindow`): a `TYPE_APPLICATION` window of the activity, full
 * screen including cutouts, `FLAG_SECURE`, no animations, added above every open dialog/sheet so it covers them while
 * preserving their state; Back on it moves the task to the back and never unlocks. Shown instantly; may fade out.
 * Its window also gets `setHideOverlayWindows(true)` on API 31+ (gaps.md G4.1) and is the only accessibility-visible
 * surface while shown. Dialogs and sheets requested while it is up are held by [WindowGate] (they would otherwise be
 * added above it); the lock screen's own content is composed outside the gate.
 */
interface LockWindowController {
    val isShowing: StateFlow<Boolean>

    /** Shows [content] (the lock screen composable from `:app`) over everything; clears focus and hides the IME first. */
    fun show(activity: Activity, content: @Composable () -> Unit)

    fun hide()
}

/**
 * Defers dialogs, sheets and toasts requested while locked or covered (Ownbit `WindowGate`): on Android such windows
 * would otherwise open above the lock window. Open when unlocked and not captured; closed in the same frame the lock
 * engages, reopened ~450 ms after unlock (the lock screen's fade-out, Ownbit `UNLOCK_SETTLE_MILLIS`). A window already
 * on screen when it closes stays (the lock window covers it); only windows not yet shown wait. `:app` mirrors [isOpen]
 * into `:core:designsystem`'s `LocalWindowGateOpen` around the NavHost, the setup overlay and the global alert (not around
 * the lock window's content), so every `SignerAlertDialog` / `SignerBottomSheet` / feature `Dialog` honours it.
 */
interface WindowGate {
    val isOpen: StateFlow<Boolean>

    /** `:app` drives it from the lock/capture/lifecycle state. */
    fun setOpen(open: Boolean)

    /** Suspends until the gate is open. */
    suspend fun awaitOpen()
}

/**
 * `FLAG_SECURE` on the activity window from `onCreate`, never toggled; API 33+ `setRecentsScreenshotEnabled(false)`;
 * app-wide autofill exclusion (`IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`). [allowCapture] (debug builds only,
 * for the screenshot tour) skips the flag.
 */
interface SecureWindowPolicy {
    fun apply(activity: Activity, allowCapture: Boolean)
}
