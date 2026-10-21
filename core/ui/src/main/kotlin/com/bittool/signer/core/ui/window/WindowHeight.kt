package com.bittool.signer.core.ui.window

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The height of this composition's window, for layout breakpoints (a phone in landscape, a split screen): what the
 * in-app keyboard's row heights, the import step's "Continue" and the backup step's Security Tips placement depend on.
 * It follows every configuration change, rotations included, and recomposes what reads it.
 *
 * Read from `LocalConfiguration` (the activity's configuration, delivered to the hosting view), never from
 * `LocalWindowInfo.containerDpSize` (QA activity-keyboard-01). Compose (1.12) refreshes that size only from the
 * `ComponentCallbacks` it registers on the view's context, and below API 33 an activity hands those to the Application
 * (`Context.registerComponentCallbacks`). On a rotation the Application hears of the new process configuration first,
 * while the activity's resources and window bounds are still the old ones, so the size is recomputed from the old
 * bounds; when the activity's own configuration follows, Compose sees nothing new. `MainActivity` handles rotation
 * itself (`configChanges`), so the value stayed one orientation behind until the next configuration change: portrait
 * rows and a pinned "Continue" in landscape (the editor off screen), the landscape layout again after turning back.
 *
 * `Configuration.screenHeightDp` is the window's height without the system bars below API 35 and with them from API 35
 * (target SDK 35 and later), the window's full height (as `containerDpSize` would report it) where it is undefined.
 */
// Compose lint recommends `containerSize`, exactly the value that is stale here. This height picks layouts and the
// keyboard's row heights; nothing is sized to it.
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
@ReadOnlyComposable
fun windowHeight(): Dp {
    val height = LocalConfiguration.current.screenHeightDp
    return if (height != Configuration.SCREEN_HEIGHT_DP_UNDEFINED) height.dp else LocalWindowInfo.current.containerDpSize.height
}
