package com.bittool.signer.core.designsystem.window

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.SecureFlagPolicy

/**
 * The `SecureFlagPolicy` of a dialog, sheet or popup window. A window that can show a secret ([secure]) is
 * `FLAG_SECURE` itself (dialogs are separate windows: the activity's flag does not cover them, ARCHITECTURE §4.4).
 *
 * The one exception is the debug screenshot tour (`bts.uitest.allowCapture`, gaps.md G2/G3): `:app` then leaves the
 * activity window capturable, and a secure window opened from it follows its host instead, so the tour can document
 * the Paste, Authorize and Recovery Cards sheets (QA sign-10). The host window is the one the caller's composition
 * lives in (the activity, or the dialog a nested window is opened from). Release builds are never affected: outside a
 * debuggable build, or when the host cannot be found (previews), a secure window is always `SecureOn`.
 * `SignerAlertDialog` and `SignerBottomSheet` use it; a feature-owned `Dialog` / `Popup` that can show a secret should
 * too.
 */
@Composable
fun rememberSecureFlagPolicy(secure: Boolean): SecureFlagPolicy {
    if (!secure) return SecureFlagPolicy.Inherit
    val view = LocalView.current
    val context = LocalContext.current
    return remember(view, context) {
        secureFlagPolicy(
            secure = true,
            applicationFlags = context.applicationInfo.flags,
            hostWindowFlags = hostWindowFlags(view, context),
        )
    }
}

/**
 * [rememberSecureFlagPolicy]'s rule. Pure: [hostWindowFlags] are the `WindowManager.LayoutParams.flags` of the host
 * window, null when it is unknown.
 */
fun secureFlagPolicy(secure: Boolean, applicationFlags: Int, hostWindowFlags: Int?): SecureFlagPolicy {
    if (!secure) return SecureFlagPolicy.Inherit
    val debuggable = applicationFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val hostCapturable = hostWindowFlags != null && hostWindowFlags and WindowManager.LayoutParams.FLAG_SECURE == 0
    return if (debuggable && hostCapturable) SecureFlagPolicy.Inherit else SecureFlagPolicy.SecureOn
}

private fun hostWindowFlags(view: View, context: Context): Int? {
    val window = view.dialogWindow() ?: context.findActivity()?.window
    return window?.attributes?.flags
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
