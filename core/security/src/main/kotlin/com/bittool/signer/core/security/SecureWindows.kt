package com.bittool.signer.core.security

import android.annotation.SuppressLint
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import com.bittool.signer.core.common.log.Log
import java.util.WeakHashMap

/**
 * Android-only hardening helpers for windows and views (DECISIONS.md §6, §9.7; gaps.md G4, G5). iOS has no
 * counterpart: it has no third-party overlays and no accessibility services that read other apps.
 *
 * The Compose-side equivalents for features (`Modifier.obscuredTouchGuard`, `AccessibilitySensitive`) live in
 * `:core:designsystem`; these work on platform windows and views (the activity window, the lock window).
 */
object SecureWindows {
    private const val TAG = "SecureWindows"

    /**
     * API 31+ `Window.setHideOverlayWindows(true)` (gaps.md G4.1): while [window] is visible, the system hides every
     * `TYPE_APPLICATION_OVERLAY` window of other apps, so an overlay cannot hide the amount or steer a tap. Needs
     * `HIDE_OVERLAY_WINDOWS` (normal, declared by this module). Returns whether the call was made.
     */
    fun hideOverlayWindows(window: Window): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            window.setHideOverlayWindows(true)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, e) { "HIDE_OVERLAY_WINDOWS not granted" }
            false
        }
    }

    /**
     * Keeps autofill services away from [view] and everything below it (app-shell.md §15.9): password managers must
     * never capture wallet passwords, passphrases or phrase text. iOS keeps AutoFill away from new secrets; an app-wide
     * exclusion is the conservative Android equivalent.
     */
    fun excludeFromAutofill(view: View) {
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    /**
     * API 30+: keeps [view] and its descendants out of content capture (gaps.md G5.4): the app has no reason to take
     * part in it, and it must not see text that `FLAG_SECURE` keeps out of screenshots.
     */
    fun excludeFromContentCapture(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        }
    }

    /**
     * Whether another window covered the touch (gaps.md G4.2): `FLAG_WINDOW_IS_OBSCURED`, or on API 29+
     * `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`.
     */
    fun isObscured(event: MotionEvent): Boolean {
        val flags = event.flags
        if (flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0
    }

    /**
     * The platform-view form of `filterTouchesWhenObscured` with feedback (gaps.md G4.2–G4.3): a gesture whose
     * `ACTION_DOWN` arrives obscured is consumed whole (the view never sees it, so it cannot click) and [onBlocked] runs
     * once, e.g. to show `security_overlay_blocked`. Unlike `filterTouchesWhenObscured`, it also refuses partially
     * obscured touches. Never install it on a root view: screen-dimmer apps would make the whole app unresponsive.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun refuseObscuredTouches(view: View, onBlocked: () -> Unit) {
        var blocking = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    blocking = isObscured(event)
                    if (blocking) onBlocked()
                    blocking
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> blocking.also { blocking = false }
                else -> blocking
            }
        }
    }

    private val sensitiveViews = WeakHashMap<View, Int>()

    /**
     * API 34+ `ACCESSIBILITY_DATA_SENSITIVE_YES` on [view] until the returned handle is closed (gaps.md G5.1): only
     * services that declare `isAccessibilityTool` (TalkBack, Switch Access, Voice Access…) can then read or act on it.
     * Nested marks of the same view are counted; the last close restores `ACCESSIBILITY_DATA_SENSITIVE_AUTO`. Below
     * API 34 nothing equivalent exists and the handle does nothing (`FLAG_SECURE` and conceal-on-pause remain).
     */
    @MainThread
    fun markAccessibilityDataSensitive(view: View): AutoCloseable {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return AutoCloseable { }
        val count = sensitiveViews[view] ?: 0
        if (count == 0) Api34.setSensitive(view, true)
        sensitiveViews[view] = count + 1
        var closed = false
        return AutoCloseable {
            if (closed) return@AutoCloseable
            closed = true
            val remaining = (sensitiveViews[view] ?: 1) - 1
            if (remaining <= 0) {
                sensitiveViews.remove(view)
                Api34.setSensitive(view, false)
            } else {
                sensitiveViews[view] = remaining
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        fun setSensitive(view: View, sensitive: Boolean) {
            view.setAccessibilityDataSensitive(
                if (sensitive) View.ACCESSIBILITY_DATA_SENSITIVE_YES else View.ACCESSIBILITY_DATA_SENSITIVE_AUTO,
            )
        }
    }
}
