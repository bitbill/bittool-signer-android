package com.bittool.signer.core.scanner.internal

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

/**
 * The window that hosts this view: the dialog or bottom-sheet window when the view is inside a Compose `Dialog` or
 * `ModalBottomSheet` (both implement [DialogWindowProvider]), else the activity window. Window flags and brightness
 * must go to that window: a sheet is a separate window above the activity (wallets-pairing.md §13.9 item 8).
 */
internal fun View.hostWindow(): Window? {
    if (this is DialogWindowProvider) return window
    var parent = parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    return context.findActivity()?.window
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Reference-counted window requests of the QR display: keep the screen on and full brightness while at least one
 * code is shown in that window, restoring the window's own brightness setting when the last one goes. Main thread
 * only (composition effects).
 */
@MainThread
internal object QrWindowEffects {
    private class Counts(var keepOn: Int = 0, var bright: Int = 0, var brightnessBefore: Float = BRIGHTNESS_NONE)

    private val windows = WeakHashMap<Window, Counts>()

    fun acquire(window: Window, maxBrightness: Boolean) {
        val counts = windows.getOrPut(window) { Counts() }
        if (counts.keepOn++ == 0) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (maxBrightness && counts.bright++ == 0) {
            counts.brightnessBefore = window.attributes.screenBrightness
            window.setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
        }
    }

    fun release(window: Window, maxBrightness: Boolean) {
        val counts = windows[window] ?: return
        if (maxBrightness && counts.bright > 0 && --counts.bright == 0) window.setBrightness(counts.brightnessBefore)
        if (counts.keepOn > 0 && --counts.keepOn == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (counts.keepOn == 0 && counts.bright == 0) windows.remove(window)
    }

    private fun Window.setBrightness(value: Float) {
        attributes = attributes.also { it.screenBrightness = value }
    }

    private const val BRIGHTNESS_NONE = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
}
