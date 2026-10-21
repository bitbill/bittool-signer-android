package com.bittool.signer.core.ui.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Opens the system page that sets a screen lock ("Choose a screen lock"), or the security settings, or Settings, the
 * first one this device has (QA wallets-04, L4; Android only: every wallet's device key needs a secure lock screen).
 * Nothing is returned: the caller looks at the lock screen again when it resumes. Shared by Welcome, setup and the
 * wallet pages, which otherwise share no code.
 */
fun openScreenLockSettings(context: Context) {
    val actions = listOf(DevicePolicyManager.ACTION_SET_NEW_PASSWORD, Settings.ACTION_SECURITY_SETTINGS, Settings.ACTION_SETTINGS)
    for (action in actions) {
        val intent = Intent(action).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // The next, more general page.
        } catch (_: SecurityException) {
            // A restricted profile can refuse the direct page; the next one is plain Settings navigation.
        }
    }
}
