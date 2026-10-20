package com.bittool.signer.core.security

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** gaps.md G4.2 (obscured touches) and G5.1 (accessibility-sensitive data) on platform views. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class SecureWindowsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun event(action: Int, flags: Int = 0): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 10f; y = 10f })
        return MotionEvent.obtain(now, now, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, flags)
    }

    @Test
    fun fullyAndPartiallyObscuredTouchesAreRecognised() {
        assertFalse(SecureWindows.isObscured(event(MotionEvent.ACTION_DOWN)))
        assertTrue(SecureWindows.isObscured(event(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_OBSCURED)))
        assertTrue(SecureWindows.isObscured(event(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)))
    }

    @Test
    fun anObscuredGestureNeverReachesTheViewAndReportsOnce() {
        val view = RecordingView(context)
        var blocked = 0
        SecureWindows.refuseObscuredTouches(view) { blocked += 1 }

        assertTrue(view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, MotionEvent.FLAG_WINDOW_IS_OBSCURED)))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP))
        assertEquals(emptyList(), view.received, "The view never sees the obscured gesture, so it cannot click")
        assertEquals(1, blocked)

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN))
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), view.received, "A clean gesture goes through")
        assertEquals(1, blocked)
    }

    private class RecordingView(context: Context) : View(context) {
        val received = mutableListOf<Int>()

        override fun onTouchEvent(event: MotionEvent): Boolean {
            received += event.actionMasked
            return true
        }
    }

    @Test
    fun accessibilitySensitivityIsCountedAndRestored() {
        val view = View(context)
        assertFalse(view.isAccessibilityDataSensitive)
        val outer = SecureWindows.markAccessibilityDataSensitive(view)
        val inner = SecureWindows.markAccessibilityDataSensitive(view)
        assertTrue(view.isAccessibilityDataSensitive)
        inner.close()
        assertTrue(view.isAccessibilityDataSensitive, "Still marked by the outer holder")
        inner.close() // closing twice is harmless
        assertTrue(view.isAccessibilityDataSensitive)
        outer.close()
        assertFalse(view.isAccessibilityDataSensitive)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S_V2])
    fun belowApi34TheSensitivityMarkDoesNothing() {
        val view = View(context)
        SecureWindows.markAccessibilityDataSensitive(view).close()
    }
}
