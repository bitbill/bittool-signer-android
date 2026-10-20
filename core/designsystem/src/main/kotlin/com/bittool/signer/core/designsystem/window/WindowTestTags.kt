package com.bittool.signer.core.designsystem.window

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Whether test tags are published as view resource names: debuggable builds only (gaps.md G1.0), so UiAutomator finds
 * `By.res("signing.decode")` and release builds expose nothing. The same rule `:app` applies at its window roots. Pure.
 */
fun exposesTestTags(applicationFlags: Int): Boolean = applicationFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/** [exposesTestTags] for the running app. */
@Composable
@ReadOnlyComposable
fun appExposesTestTags(): Boolean = exposesTestTags(LocalContext.current.applicationInfo.flags)

/**
 * `testTagsAsResourceId` for the root of a separate window's content when [enabled] ([appExposesTestTags]). `:app`
 * sets the flag once at the root of the activity's composition, but Compose looks for it only among a node's semantics
 * ancestors **in the same window**: every dialog, sheet and popup is a window of its own. `SignerAlertDialog` and
 * `SignerBottomSheet` apply it themselves; a feature-owned `Dialog` / `Popup` applies it to its content root.
 */
fun Modifier.windowTestTags(enabled: Boolean): Modifier =
    if (enabled) this.semantics { testTagsAsResourceId = true } else this
