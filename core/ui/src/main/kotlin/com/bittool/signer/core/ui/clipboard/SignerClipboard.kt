package com.bittool.signer.core.ui.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Copies public text (addresses, xpubs, payloads, fingerprints, sites) the way iOS `Clipboard.copy` does
 * (`Design/DesignSystem.swift:292-298`): the copy expires after [DEFAULT_EXPIRY] (120 s). Never used for secrets
 * (DECISIONS §6).
 *
 * Android equivalents of the iOS pasteboard options:
 * - **expiry** — iOS gives the item a wall-clock `expirationDate`. Here the deadline is measured on
 *   `SystemClock.elapsedRealtime()`, which keeps counting while the device sleeps (a `Handler` timer alone runs on
 *   uptime, which stops in deep sleep). When the deadline has passed, the clipboard is cleared, but only if the clip is
 *   still the one this object wrote (same label and `ClipDescription.timestamp`), so a newer copy by the user is never
 *   wiped. Android 10+ lets an app read the clipboard (even `hasPrimaryClip()`) only while one of its windows has
 *   focus, so a deadline that passes in the background cannot be checked: the clear stays pending and is performed on
 *   the next return to the foreground, once the window has focus again (or it ends with the process). The app could
 *   write an empty clip blindly from the background, but that would also destroy whatever the user copied since.
 * - **local only** — not available (OEM cross-device clipboards); the clip is marked
 *   `ClipDescription.EXTRA_IS_SENSITIVE` instead, which keeps it out of the Android 13+ preview overlay and keyboard
 *   suggestions.
 *
 * Android 13+ shows its own copy confirmation; components keep the in-app check mark / toast for iOS parity.
 */
object SignerClipboard {
    /** The clip label the app writes (the app name). */
    const val LABEL = "BitTool Signer"

    /** iOS `expiresAfter` default. */
    val DEFAULT_EXPIRY: Duration = 120.seconds

    /**
     * How long after the app resumes the pending clear is checked again: window focus, which Android 10+ requires to
     * read the clipboard, is granted just after `onResume`.
     */
    @VisibleForTesting
    internal const val FOCUS_SETTLE_MILLIS = 500L

    /** The expiry time base (tests replace it). Elapsed real time: it includes time the device spent asleep. */
    @VisibleForTesting
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    /**
     * Reads the description of the current clip (tests replace it). Null when there is no clip, and on Android 10+
     * also whenever the app has no focused window: the two cases cannot be told apart.
     */
    @VisibleForTesting
    internal var describe: (ClipboardManager) -> ClipDescription? = { runCatching { it.primaryClipDescription }.getOrNull() }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Pending? = null
    private var observing = false

    /** The copy waiting to expire. Holds the application's clipboard service, never an activity. */
    private class Pending(val manager: ClipboardManager, val label: String, val timestamp: Long?, val dueAt: Long)

    /**
     * Copies [text] under [label], marked sensitive, and schedules its removal after [expiresAfter]. Main thread.
     *
     * Returns false when the system refused the clip, and the caller then does not confirm a copy. The clip travels to
     * the clipboard service in one binder transaction (about 1 MB for the whole process), so a very large payload — a
     * signed PSBT of many inputs, up to the engine's 2 MiB response — fails there with a `RuntimeException` instead of
     * being copied; iOS has no such limit.
     */
    @MainThread
    fun copy(context: Context, text: String, label: String = LABEL, expiresAfter: Duration = DEFAULT_EXPIRY): Boolean {
        val app = context.applicationContext
        val manager = app.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else EXTRA_IS_SENSITIVE, true)
        }
        try {
            write(manager, clip)
        } catch (refused: RuntimeException) {
            // TransactionTooLargeException (wrapped) or a SecurityException from an OEM clipboard: nothing was copied.
            return false
        }
        // The service stamps the clip when it stores it; the copy is made from a tap, so the app has focus to read it.
        val written = describe(manager)?.timestamp
        val delay = expiresAfter.inWholeMilliseconds.coerceAtLeast(0)
        pending = Pending(manager, label, written, clock() + delay)
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, delay)
        observeForeground()
        return true
    }

    /** Hands the clip to the clipboard service (tests replace it to simulate a refusal). */
    @VisibleForTesting
    internal var write: (ClipboardManager, ClipData) -> Unit = { manager, clip -> manager.setPrimaryClip(clip) }

    private val expire = Runnable { clearIfExpired() }

    /**
     * Clears the clipboard when the pending copy is due and still ours. When the clipboard cannot be read (app in the
     * background on Android 10+, or nothing on it) the clear stays pending for the next foreground. Called by the timer
     * and on every return to the foreground.
     */
    @MainThread
    fun clearIfExpired() {
        val current = pending ?: return
        if (clock() < current.dueAt) return
        val description = describe(current.manager) ?: return
        pending = null
        val ours = description.label == current.label && (current.timestamp == null || description.timestamp == current.timestamp)
        if (!ours) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            current.manager.clearPrimaryClip()
        } else {
            current.manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    /**
     * The app came to the foreground. A copy that is not due yet gets its timer re-armed for the time that is really
     * left (the `Handler` runs on uptime, which stood still while the device slept); a due one is checked now and again
     * once the window has focus.
     */
    @VisibleForTesting
    @MainThread
    internal fun onForeground() {
        val current = pending ?: return
        val left = current.dueAt - clock()
        handler.removeCallbacks(expire)
        if (left > 0) {
            handler.postDelayed(expire, left)
            return
        }
        clearIfExpired()
        if (pending != null) handler.postDelayed(expire, FOCUS_SETTLE_MILLIS)
    }

    private fun observeForeground() {
        if (observing) return
        observing = true
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = onForeground()
            })
        }
    }

    /** Whether a copy is waiting to be cleared (tests). */
    @VisibleForTesting
    internal val hasPending: Boolean get() = pending != null

    /** Forgets the pending copy and restores the real clock and reader (tests). */
    @VisibleForTesting
    internal fun resetForTesting() {
        handler.removeCallbacks(expire)
        pending = null
        clock = SystemClock::elapsedRealtime
        describe = { runCatching { it.primaryClipDescription }.getOrNull() }
        write = { manager, clip -> manager.setPrimaryClip(clip) }
    }

    /** `ClipDescription.EXTRA_IS_SENSITIVE` before API 33 (the platform reads the same key). */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
}
