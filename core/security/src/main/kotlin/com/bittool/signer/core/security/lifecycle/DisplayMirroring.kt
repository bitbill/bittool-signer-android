package com.bittool.signer.core.security.lifecycle

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.core.content.ContextCompat
import com.bittool.signer.core.common.log.Log

/**
 * Display mirroring as part of "captured" (QA audit A03): the Android side of iOS `UIScreen.isCaptured`, which Apple
 * defines as recorded, mirrored, AirPlayed or otherwise cloned (RecoveryPrivacySession.swift:27,
 * PrivacyShield.swift:47).
 *
 * `FLAG_SECURE` does not cover it: it blanks only displays that are not secure, and the platform treats a wired
 * external display (HDMI, USB-C DisplayPort) and an HDCP Miracast sink as secure, so the activity is composed onto
 * them. The API 35 screen-recording callback reports MediaProjection captures only, never a mirror.
 *
 * A display counts as a mirror target ([isMirrorTarget]) when it is a presentation display (`FLAG_PRESENTATION`:
 * external, wireless and simulated secondary displays) that is switched on and is not
 * - the built-in display (`DEFAULT_DISPLAY`),
 * - the display the activity itself is on (the app runs there by the person's choice, e.g. a desktop mode), or
 * - a private display (`FLAG_PRIVATE`): those are visible only to the app that created them, so one this app can see is
 *   its own virtual display.
 */
internal object DisplayMirroring {
    private const val TAG = "DisplayMirroring"

    /** Pure rule over one display's id, flags and state (the platform `Display` getters), unit-tested directly. */
    fun isMirrorTarget(displayId: Int, flags: Int, state: Int, ownDisplayId: Int): Boolean =
        displayId != Display.DEFAULT_DISPLAY &&
            displayId != ownDisplayId &&
            flags and Display.FLAG_PRESENTATION != 0 &&
            flags and Display.FLAG_PRIVATE == 0 &&
            state != Display.STATE_OFF

    /** True while any connected display other than the built-in one and [activity]'s own could show its screen. */
    fun isMirrored(displayManager: DisplayManager, activity: Activity): Boolean {
        val own = ownDisplayId(activity)
        return displayManager.displays.any { isMirrorTarget(it.displayId, it.flags, it.state, own) }
    }

    /**
     * Reports the current state to [onMirroring] at once, then every change while registered (display added, removed,
     * switched on or off); the returned action unregisters. Null when the platform refuses (no display service).
     */
    fun register(activity: Activity, onMirroring: (Boolean) -> Unit): (() -> Unit)? {
        val displayManager = activity.getSystemService(DisplayManager::class.java) ?: return null
        var last: Boolean? = null
        fun evaluate() {
            val mirrored = isMirrored(displayManager, activity)
            if (mirrored == last) return
            last = mirrored
            onMirroring(mirrored)
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = evaluate()

            override fun onDisplayRemoved(displayId: Int) = evaluate()

            override fun onDisplayChanged(displayId: Int) {
                // The built-in display reports every refresh-rate and brightness change; it never counts.
                if (displayId != Display.DEFAULT_DISPLAY) evaluate()
            }
        }
        return try {
            displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            evaluate()
            val unregister: () -> Unit = { displayManager.unregisterDisplayListener(listener) }
            unregister
        } catch (e: RuntimeException) {
            Log.w(TAG, e) { "Display listener unavailable" }
            null
        }
    }

    private fun ownDisplayId(activity: Activity): Int = ContextCompat.getDisplayOrDefault(activity).displayId
}
