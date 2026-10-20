package com.bittool.signer.feature.sign.common

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Whether test tags are published as view resource names (gaps.md G1.0: debug builds only, so UiAutomator finds
 * `By.res("signing.decode")`). Pure, unit-tested.
 */
internal fun exposesTestTags(applicationFlags: Int): Boolean = applicationFlags and ApplicationInfo.FLAG_DEBUGGABLE != 0

/**
 * `testTagsAsResourceId` for the content of a sheet or dialog. `:app` sets it once at the root of the activity's
 * composition, but Compose looks for it only among a node's semantics ancestors **in the same window**, and every
 * `SignerBottomSheet` is a window of its own: without this the e2e suite cannot see `signing.payload`,
 * `signing.decode`, `authorize.sign` or the "Wallet password" field (gaps.md G1.8). Debuggable builds only, like the
 * root.
 */
@Composable
@ReadOnlyComposable
internal fun windowTestTags(): Modifier =
    if (exposesTestTags(LocalContext.current.applicationInfo.flags)) Modifier.semantics { testTagsAsResourceId = true } else Modifier
