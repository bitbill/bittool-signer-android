package com.bittool.signer.core.security.window

import android.app.AlertDialog
import android.content.DialogInterface
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bittool.signer.core.security.SecureWindows
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lock window on a real window manager (app-shell.md §11.10, §15.7): it covers a dialog that was already open —
 * touches meant for the dialog land on the lock screen and Back never reaches it — with the activity window's full
 * frame and `FLAG_SECURE`; after unlocking, the dialog beneath is usable again with its state intact.
 */
@RunWith(AndroidJUnit4::class)
class LockWindowDeviceTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(ComponentActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun onActivity(block: (ComponentActivity) -> Unit) = activityRule.scenario.onActivity(block)

    private fun tapCenterOf(view: View) {
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
        val x = location[0] + view.width / 2f
        val y = location[1] + view.height / 2f
        val downTime = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
        instrumentation.waitForIdleSync()
    }

    @Test
    fun theLockWindowCoversAnOpenDialogUntilUnlocked() {
        val lockWindow = DefaultLockWindowController(DefaultSecureWindowPolicy())
        val dialogClicks = AtomicInteger()
        val lockTaps = AtomicInteger()
        val backs = AtomicInteger()
        val dialog = AtomicReference<AlertDialog>()
        val lockRoot = AtomicReference<View>()

        onActivity { activity ->
            dialog.set(
                AlertDialog.Builder(activity)
                    .setTitle("A sheet beneath the lock")
                    .setPositiveButton("Continue", null)
                    .setCancelable(true)
                    .create()
                    .apply {
                        // Keep the dialog open after its button so the state beneath can be checked.
                        setOnShowListener {
                            getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { dialogClicks.incrementAndGet() }
                        }
                        show()
                    },
            )
        }
        instrumentation.waitForIdleSync()
        val button = dialog.get().getButton(DialogInterface.BUTTON_POSITIVE)

        onActivity { activity ->
            lockWindow.onBack = { backs.incrementAndGet() }
            lockWindow.show(activity) {
                val view = LocalView.current
                SideEffect { lockRoot.set(view.rootView) }
                Box(Modifier.fillMaxSize().clickable { lockTaps.incrementAndGet() })
            }
        }
        instrumentation.waitForIdleSync()

        tapCenterOf(button)
        assertEquals("The dialog beneath gets no touch", 0, dialogClicks.get())
        assertEquals(1, lockTaps.get())

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
        assertEquals(1, backs.get())
        assertTrue("Back never reaches the dialog beneath", dialog.get().isShowing)

        val root = checkNotNull(lockRoot.get()) { "The lock screen was composed" }
        onActivity { activity ->
            val decor = activity.window.decorView
            assertEquals("Same frame as the activity window", decor.width, root.width)
            assertEquals(decor.height, root.height)
            val params = root.layoutParams as WindowManager.LayoutParams
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, decor.importantForAccessibility)
        }

        onActivity { lockWindow.hide() }
        SystemClock.sleep(DefaultLockWindowController.FADE_OUT_MILLIS * 4)
        instrumentation.waitForIdleSync()
        assertFalse(lockWindow.isPresent)
        tapCenterOf(button)
        assertEquals("After unlocking, the dialog kept its state and works", 1, dialogClicks.get())
        onActivity { dialog.get().dismiss() }
    }

    @Test
    fun theActivityPolicyHidesOverlaysWithTheDeclaredPermission() {
        onActivity { activity ->
            DefaultSecureWindowPolicy().apply(activity, allowCapture = false)
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            // Throws without HIDE_OVERLAY_WINDOWS in the merged manifest (API 31+).
            assertTrue(SecureWindows.hideOverlayWindows(activity.window) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        }
    }
}
