package com.bittool.signer.feature.setup.flow

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.feature.setup.ui.LinkButton
import com.bittool.signer.core.l10n.R as L10n

/**
 * The screen-lock requirement, told before anything secret is typed (QA wallets-04; Android only, DECISIONS §4): the
 * device key of every wallet needs a secure lock screen (`DeviceKeyStore.isDeviceSecure()`), and without one the vault
 * refuses only at the very end, after the phrase, the password and the key derivation. Shown at the top of the first
 * step while the device has no screen lock (its primary action is disabled meanwhile), with "Open Settings" as the way
 * out; the flow checks again whenever it resumes.
 */
@Composable
internal fun DeviceLockNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    Column(modifier.fillMaxWidth().testTag(DEVICE_LOCK_NOTICE_TAG), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Callout(
            text = stringResource(L10n.string.crypto_device_lock_required),
            title = stringResource(L10n.string.setup_screen_lock_required),
            tone = Tone.Warning,
        )
        LinkButton(
            text = stringResource(L10n.string.open_settings),
            onClick = { openScreenLockSettings(activity ?: context) },
            trailingIcon = SignerIcons.ArrowForward,
            modifier = Modifier.testTag(OPEN_LOCK_SETTINGS_TAG),
        )
    }
}

/**
 * Opens the system page that sets a screen lock ("Choose a screen lock"), or the security settings, or Settings, the
 * first one this device has. Nothing is returned: the flow re-checks the lock screen when it resumes.
 */
internal fun openScreenLockSettings(context: Context) {
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

internal const val DEVICE_LOCK_NOTICE_TAG = "setup.deviceLock"
internal const val OPEN_LOCK_SETTINGS_TAG = "setup.openLockSettings"
