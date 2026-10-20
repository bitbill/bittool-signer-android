package com.bittool.signer.core.security.window

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** The hidden `ValueAnimator.setDurationScale` the system calls when the global animator scale changes. */
private fun setAnimatorDurationScale(scale: Float) {
    ValueAnimator::class.java.getMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, scale)
}

/**
 * The lock window (app-shell.md §11.10, §15.7): a secure, full-screen application window above everything that keeps
 * Back from unlocking, hides the app from accessibility while shown, fades out on unlock and holds the window gate
 * until it is gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class LockWindowControllerTest {

    private lateinit var controller: ActivityController<ComponentActivity>
    private val activity: ComponentActivity get() = controller.get()

    @Before
    fun setUp() {
        ShadowDialog.reset()
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.HIDE_OVERLAY_WINDOWS)
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun lockDialog(): Dialog = ShadowDialog.getLatestDialog()

    private fun policy(captureAllowed: Boolean = false) = DefaultSecureWindowPolicy().also { policy ->
        if (captureAllowed) {
            val debug = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
            debug.applicationInfo.flags = debug.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
            policy.apply(debug, allowCapture = true)
        }
    }

    @Test
    @Suppress("DEPRECATION") // SOFT_INPUT_ADJUST_RESIZE: still what gives Compose IME insets below API 30
    fun theLockWindowIsASecureFullScreenWindowWithTheLockScreen() {
        val lockWindow = DefaultLockWindowController(policy())
        var compositions = 0
        lockWindow.show(activity) {
            SideEffect { compositions += 1 }
            BasicText("BitTool Signer Is Locked")
        }
        idle()
        val dialog = lockDialog()
        assertTrue(dialog.isShowing)
        assertTrue(lockWindow.isShowing.value)
        assertTrue(lockWindow.isPresent)
        assertTrue(compositions > 0, "The lock screen content is composed")
        val attributes = checkNotNull(dialog.window).attributes
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION, attributes.type, "An application window of the activity")
        assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND == 0)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, attributes.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, attributes.height)
        assertEquals(0, attributes.windowAnimations)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, attributes.layoutInDisplayCutoutMode)
        assertEquals(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            attributes.softInputMode,
        )
        assertTrue(dialog.window!!.hidesOverlayWindows())
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, dialog.window!!.decorView.importantForAutofill)
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
            activity.window.decorView.importantForAccessibility,
            "The app beneath is not traversable while locked",
        )
    }

    @Test
    fun theDebugScreenshotTourAlsoCapturesTheLockScreen() {
        val lockWindow = DefaultLockWindowController(policy(captureAllowed = true))
        lockWindow.show(activity) { BasicText("Locked") }
        idle()
        assertTrue(checkNotNull(lockDialog().window).attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
    }

    @Test
    fun backMovesTheTaskToTheBackAndNeverUnlocks() {
        val lockWindow = DefaultLockWindowController(policy())
        val backs = mutableListOf<Activity>()
        lockWindow.onBack = { backs += it }
        lockWindow.show(activity) { BasicText("Locked") }
        idle()
        val dialog = lockDialog() as ComponentDialog
        dialog.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(listOf<Activity>(activity), backs)
        assertTrue(dialog.isShowing)
        assertTrue(lockWindow.isShowing.value)
    }

    @Test
    fun theDefaultBackMovesTheTaskToTheBack() {
        val lockWindow = DefaultLockWindowController(policy())
        lockWindow.show(activity) { BasicText("Locked") }
        idle()
        (lockDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        assertTrue(shadowOf(activity).isTaskMovedToBack)
        assertTrue(lockDialog().isShowing)
    }

    @Test
    fun showingAgainKeepsTheWindowAndUpdatesItsContent() {
        val lockWindow = DefaultLockWindowController(policy())
        var shown = ""
        lockWindow.show(activity) { SideEffect { shown = "first" } }
        idle()
        val first = lockDialog()
        lockWindow.show(activity) { SideEffect { shown = "second" } }
        idle()
        assertSame(first, lockDialog())
        assertEquals("second", shown)
    }

    @Test
    fun unlockingFadesOutThenRemovesTheWindowAndReleasesTheGate() {
        val lockWindow = DefaultLockWindowController(policy())
        val gate = DefaultWindowGate(lockWindow)
        val accessibilityBefore = activity.window.decorView.importantForAccessibility
        lockWindow.show(activity) { BasicText("Locked") }
        gate.setOpen(false)
        idle()
        val dialog = lockDialog()

        lockWindow.hide()
        gate.setOpen(true)
        assertFalse(lockWindow.isShowing.value)
        assertTrue(lockWindow.isPresent, "Still fading out")
        assertFalse(gate.isOpen.value, "Held windows wait for the fade-out")
        assertEquals(accessibilityBefore, activity.window.decorView.importantForAccessibility, "The app is readable again at once")

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DefaultLockWindowController.FADE_OUT_MILLIS + 100))
        assertFalse(dialog.isShowing)
        assertFalse(lockWindow.isPresent)
        assertTrue(gate.isOpen.value)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.O])
    fun onTheMinimumApiTheLockWindowIsSecureAndFadesOut() {
        val lockWindow = DefaultLockWindowController(policy())
        lockWindow.show(activity) { BasicText("Locked") }
        idle()
        val dialog = lockDialog()
        assertTrue(checkNotNull(dialog.window).attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        lockWindow.hide()
        assertTrue(lockWindow.isPresent)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DefaultLockWindowController.FADE_OUT_MILLIS + 100))
        assertFalse(dialog.isShowing)
        assertFalse(lockWindow.isPresent)
    }

    @Test
    fun withAnimationsOffTheWindowGoesAtOnce() {
        setAnimatorDurationScale(0f) // Developer options › Animator duration scale: off
        try {
            val lockWindow = DefaultLockWindowController(policy())
            val gate = DefaultWindowGate(lockWindow)
            lockWindow.show(activity) { BasicText("Locked") }
            idle()
            val dialog = lockDialog()
            lockWindow.hide()
            gate.setOpen(true)
            assertFalse(dialog.isShowing)
            assertFalse(lockWindow.isPresent)
            assertTrue(gate.isOpen.value)
        } finally {
            setAnimatorDurationScale(1f)
        }
    }

    @Test
    fun aNewLockDuringTheFadeOutGetsANewWindow() {
        val lockWindow = DefaultLockWindowController(policy())
        lockWindow.show(activity) { BasicText("Locked") }
        idle()
        val first = lockDialog()
        lockWindow.hide()
        lockWindow.show(activity) { BasicText("Locked again") }
        idle()
        val second = lockDialog()
        assertNotSame(first, second)
        assertFalse(first.isShowing)
        assertTrue(second.isShowing)
        assertTrue(lockWindow.isShowing.value)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, activity.window.decorView.importantForAccessibility)
    }

    @Test
    fun aDestroyedActivityTakesItsLockWindowWithIt() {
        val lockWindow = DefaultLockWindowController(policy())
        lockWindow.show(activity) { BasicText("Locked") }
        idle()
        val dialog = lockDialog()
        controller.pause().stop().destroy()
        assertFalse(dialog.isShowing)
        assertFalse(lockWindow.isShowing.value)
        assertFalse(lockWindow.isPresent)

        // The recreated activity shows a fresh lock window.
        val recreated = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        lockWindow.show(recreated) { BasicText("Locked") }
        idle()
        assertTrue(lockDialog().isShowing)
        assertNotSame(dialog, lockDialog())
    }

    @Test
    fun aFinishingActivityGetsNoLockWindow() {
        val lockWindow = DefaultLockWindowController(policy())
        activity.finish()
        lockWindow.show(activity) { BasicText("Locked") }
        assertFalse(lockWindow.isShowing.value)
        assertFalse(lockWindow.isPresent)
    }
}
