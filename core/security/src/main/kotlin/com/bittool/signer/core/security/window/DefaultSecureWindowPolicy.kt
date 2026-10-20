package com.bittool.signer.core.security.window

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.bittool.signer.core.common.log.Log
import com.bittool.signer.core.security.SecureWindowPolicy
import com.bittool.signer.core.security.SecureWindows
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The activity window's protections (DECISIONS.md §6, app-shell.md §15.8–§15.9, gaps.md G4.1, G5.4), applied by
 * `MainActivity.onCreate` before `setContent`:
 *
 * - `FLAG_SECURE`, set once and never cleared (toggling re-creates the surface and blinks on some devices; Ownbit
 *   `RecentsGuard.kt`): blank Recents thumbnail, black screenshots, recordings and casts — the OS-enforced form of the
 *   iOS app-switcher shield and "recorded or mirrored" cover (PrivacyShield.swift:18-62).
 * - API 33+ `setRecentsScreenshotEnabled(false)`: iOS covers the app switcher whether or not App Lock is on.
 * - API 31+ `setHideOverlayWindows(true)` (tapjacking, G4.1).
 * - App-wide autofill exclusion (§15.9) and, on API 30+, content-capture exclusion (G5.4) on the decor view. When the
 *   decor does not exist yet (the call precedes `super.onCreate`), they are applied once the activity is created, so
 *   this never installs the decor before AppCompat does.
 *
 * `allowCapture` (the debug screenshot tour, gaps.md G2/G3) is honoured only in a debuggable build: a release build
 * keeps `FLAG_SECURE` whatever the caller passes. The decision is per process and is mirrored by the lock window.
 */
@Singleton
internal class DefaultSecureWindowPolicy @Inject constructor() : SecureWindowPolicy {

    /** Whether this process runs without `FLAG_SECURE` (debug screenshot tour only). */
    @Volatile
    var captureAllowed: Boolean = false
        private set

    override fun apply(activity: Activity, allowCapture: Boolean) {
        val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val honoured = allowCapture && debuggable
        if (allowCapture && !honoured) Log.w(TAG) { "allowCapture ignored outside a debuggable build" }
        captureAllowed = honoured

        val window = activity.window
        if (!honoured) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(false)
        SecureWindows.hideOverlayWindows(window)

        val decor = window.peekDecorView()
        val owner = activity as? LifecycleOwner
        when {
            decor != null -> excludeServices(decor)
            owner != null && !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED) ->
                owner.lifecycle.addObserver(object : LifecycleEventObserver {
                    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                        if (event != Lifecycle.Event.ON_CREATE) return
                        source.lifecycle.removeObserver(this)
                        excludeServices(activity.window.decorView)
                    }
                })
            else -> excludeServices(window.decorView)
        }
    }

    private fun excludeServices(decor: View) {
        SecureWindows.excludeFromAutofill(decor)
        SecureWindows.excludeFromContentCapture(decor)
    }

    private companion object {
        const val TAG = "SecureWindowPolicy"
    }
}
