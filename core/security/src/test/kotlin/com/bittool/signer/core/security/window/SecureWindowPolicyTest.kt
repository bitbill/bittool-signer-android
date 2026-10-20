package com.bittool.signer.core.security.window

import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** DECISIONS.md §6, app-shell.md §15.8–§15.9, gaps.md G4.1 / G5.4 on the activity window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class SecureWindowPolicyTest {

    @Before
    fun grantTheManifestPermission() {
        // Normal permissions are granted at install; Robolectric grants nothing by itself.
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
    }

    @Test
    fun withoutTheOverlayPermissionTheWindowIsStillSecure() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).create().get()
        DefaultSecureWindowPolicy().apply(activity, allowCapture = false)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertFalse(activity.window.hidesOverlayWindows())
    }

    @Test
    fun theActivityWindowIsSecureHidesOverlaysAndKeepsServicesOut() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).create().get()
        val policy = DefaultSecureWindowPolicy()
        policy.apply(activity, allowCapture = false)
        val window = activity.window
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertTrue(window.hidesOverlayWindows())
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, window.decorView.importantForAutofill)
        assertEquals(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS, window.decorView.importantForContentCapture)
        assertFalse(policy.captureAllowed)
    }

    @Test
    fun appliedBeforeOnCreateItNeverInstallsTheDecorEarly() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java)
        val activity = controller.get()
        val policy = DefaultSecureWindowPolicy()
        policy.apply(activity, allowCapture = false)
        assertEquals(null, activity.window.peekDecorView(), "The decor is left to AppCompat / setContent")
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        controller.create()
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, activity.window.decorView.importantForAutofill)
    }

    @Test
    fun allowCaptureIsHonouredOnlyInADebuggableBuild() {
        val release = Robolectric.buildActivity(FragmentActivity::class.java).create().get()
        release.applicationInfo.flags = release.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        val releasePolicy = DefaultSecureWindowPolicy()
        releasePolicy.apply(release, allowCapture = true)
        assertTrue(release.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertFalse(releasePolicy.captureAllowed)

        val debug = Robolectric.buildActivity(FragmentActivity::class.java).create().get()
        debug.applicationInfo.flags = debug.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        val debugPolicy = DefaultSecureWindowPolicy()
        debugPolicy.apply(debug, allowCapture = true)
        assertFalse(debug.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertTrue(debugPolicy.captureAllowed)
        assertTrue(debug.window.hidesOverlayWindows(), "Overlay hiding does not depend on the capture switch")
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O])
    fun belowApi31ItStillSecuresTheWindow() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).create().get()
        DefaultSecureWindowPolicy().apply(activity, allowCapture = false)
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, activity.window.decorView.importantForAutofill)
    }
}

/** `Window.setHideOverlayWindows(true)` sets the hidden `SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` private flag. */
internal fun Window.hidesOverlayWindows(): Boolean {
    val params = WindowManager.LayoutParams::class.java
    val flag = params.getDeclaredField("SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS").apply { isAccessible = true }.getInt(null)
    val privateFlags = params.getDeclaredField("privateFlags").apply { isAccessible = true }.getInt(attributes)
    return privateFlags and flag != 0
}
