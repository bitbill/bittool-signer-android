package com.bittool.signer.core.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.Bootstrap
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowViewRootImpl

/**
 * A rotation of an activity that handles it itself, as `MainActivity` does (`configChanges="orientation|screenSize|…"`):
 * the activity stays, its views get the new configuration. Robolectric has no window manager to send it, so this
 * delivers it the way Android does, in the platform's order (QA activity-keyboard-01):
 * 1. The process configuration (`ActivityThread.handleConfigurationChanged`): the Application's `ComponentCallbacks`.
 *    Below API 33 these also hold the callbacks registered on an Activity context (`Context.registerComponentCallbacks`
 *    forwards to the Application; Compose registers its window-size observer there). The activity's resources, and
 *    with them its window bounds, are still the old ones at this point.
 * 2. The display, and the activity's resources and window bounds.
 * 3. `Activity.onConfigurationChanged` (from API 33 also the activity's own `ComponentCallbacks`), then the views of its
 *    window (`ViewRootImpl.updateConfiguration` → `View.onConfigurationChanged`).
 * 4. The window's new frame (`ViewRootImpl.dispatchResized`): the views are laid out at the new size.
 *
 * [qualifiers] are Robolectric qualifiers applied on top of the current configuration ("w852dp-h393dp-land"); the
 * window bounds follow them. Not `RuntimeEnvironment.setQualifiers`: Robolectric would relaunch the test's activity,
 * which declares no `configChanges`. Same as `:feature:setup`'s test helper.
 */
@Suppress("DEPRECATION") // Resources.updateConfiguration: what the platform does to these resources here.
internal fun Activity.rotateItself(qualifiers: String) {
    val next = Configuration(resources.configuration)
    val metrics = DisplayMetrics().apply { setTo(resources.displayMetrics) }
    Bootstrap.applyQualifiers("+$qualifiers", Build.VERSION.SDK_INT, next, metrics)
    // 1. The process configuration, while the activity's resources and window bounds are the old ones.
    application.onConfigurationChanged(Configuration(next))
    application.resources.updateConfiguration(next, metrics)
    // 2. The display, the activity's resources and window bounds.
    ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, "+$qualifiers")
    resources.updateConfiguration(next, metrics)
    // 3. The activity, then the views of its window.
    onConfigurationChanged(Configuration(next))
    window.decorView.dispatchConfigurationChanged(Configuration(next))
    // 4. The window's new size.
    Shadow.extract<ShadowViewRootImpl>(window.decorView.parent).callDispatchResized()
    shadowOf(Looper.getMainLooper()).idle()
}

/** A phone in portrait and the same phone turned to landscape (about a Pixel 8's window). */
internal const val PORTRAIT = "w393dp-h852dp-port"
internal const val LANDSCAPE = "w852dp-h393dp-land"
