package com.bittool.signer.core.designsystem.window

import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Whether new windows (dialogs, sheets, feature-owned `Dialog` / `Popup` windows) may appear now (ARCHITECTURE §1.10
 * row 17, §6 item 16). `:app` mirrors `:core:security`'s `WindowGate` into this local: it is closed in the same frame
 * App Lock engages (and while the screen is captured) and reopened about 450 ms after unlock. A Boolean, so it does not
 * clash with the security module's `WindowGate` type. Default (previews, tests, other hosts): open.
 *
 * iOS presents every sheet and alert beneath its lock window; on Android a window added after the lock window would
 * draw above it, so a window requested while the gate is closed keeps its state in the caller and appears once the gate
 * opens. Not provided around the lock window's own content.
 */
val LocalWindowGateOpen: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }

/**
 * `true` when the window the caller wants to show may be on screen: the gate is open, or it has been open at some point
 * since the caller entered the composition. A window that already appeared is never taken away when the gate closes
 * again (the lock window added later covers it); only a window that has not appeared yet waits (Ownbit `WindowGate.kt`
 * semantics). `SignerAlertDialog` and `SignerBottomSheet` check it; every feature-owned window must too.
 */
@Composable
fun rememberWindowGateReleased(): Boolean {
    val open = LocalWindowGateOpen.current
    var released by remember { mutableStateOf(open) }
    if (open && !released) SideEffect { released = true }
    return open || released
}

/**
 * For content hosted in its own window (a dialog, a sheet): hides other apps' `TYPE_APPLICATION_OVERLAY` windows while
 * this window is visible (API 31+, gaps G4.1; the activity window gets the same call from `:app`). The permission
 * `HIDE_OVERLAY_WINDOWS` is declared in this module's manifest. No effect outside a dialog window or below API 31.
 */
@Composable
fun HideOverlayWindowsEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.dialogWindow()
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setHideOverlayWindows(true) }
        }
        onDispose {}
    }
}

/** The window of the dialog hosting [this] Compose view, or null when it is not in a dialog. */
internal fun View.dialogWindow(): Window? {
    var current: Any? = this
    while (current is View) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent
    }
    return null
}
