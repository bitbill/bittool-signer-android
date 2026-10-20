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
 * - **expiry** — a main-thread timer clears the clipboard when it fires, but only if the clip is still the one this
 *   object wrote (same label and `ClipDescription.timestamp`, so a newer copy by the user is never wiped). Android 10+
 *   lets an app read the clipboard description only while it has focus, so a timer that fires in the background leaves
 *   the clear pending, and the next return to the foreground performs it (or it ends with the process).
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

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Pending? = null
    private var observing = false

    private class Pending(val context: Context, val label: String, val timestamp: Long?, val dueAt: Long)

    /** Copies [text] under [label], marked sensitive, and schedules its removal after [expiresAfter]. Main thread. */
    @MainThread
    fun copy(context: Context, text: String, label: String = LABEL, expiresAfter: Duration = DEFAULT_EXPIRY) {
        val app = context.applicationContext
        val manager = app.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE else EXTRA_IS_SENSITIVE, true)
        }
        manager.setPrimaryClip(clip)
        val written = runCatching { manager.primaryClipDescription?.timestamp }.getOrNull()
        val due = SystemClock.uptimeMillis() + expiresAfter.inWholeMilliseconds
        pending = Pending(app, label, written, due)
        handler.removeCallbacks(expire)
        handler.postAtTime(expire, due)
        observeForeground()
    }

    private val expire = Runnable { clearIfExpired() }

    /**
     * Clears the clipboard when the pending copy is due and still ours; keeps it pending when the description cannot be
     * read (app in the background). Called by the timer and on every return to the foreground.
     */
    @MainThread
    fun clearIfExpired() {
        val current = pending ?: return
        if (SystemClock.uptimeMillis() < current.dueAt) return
        val manager = current.context.getSystemService(ClipboardManager::class.java) ?: return
        val description = runCatching { manager.primaryClipDescription }.getOrNull()
        if (description == null) {
            // Unreadable while unfocused (Android 10+) or empty: retry on the next foreground unless it is empty now.
            if (runCatching { manager.hasPrimaryClip() }.getOrDefault(true)) return
            pending = null
            return
        }
        pending = null
        val ours = description.label == current.label && (current.timestamp == null || description.timestamp == current.timestamp)
        if (!ours) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    private fun observeForeground() {
        if (observing) return
        observing = true
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = clearIfExpired()
            })
        }
    }

    /** Whether a copy is waiting to be cleared (tests). */
    @VisibleForTesting
    internal val hasPending: Boolean get() = pending != null

    /** `ClipDescription.EXTRA_IS_SENSITIVE` before API 33 (the platform reads the same key). */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
}
