package com.bittool.signer.core.designsystem.security

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Android-only hardening: gaps.md G4.2 (tapjacking), G5.1 (accessibility-sensitive data), keyboard and Autofill. */
@RunWith(RobolectricTestRunner::class)
class HardeningTest {
    @get:Rule
    val compose = createComposeRule()

    private fun tap(view: View, flags: Int) {
        val down = SystemClock.uptimeMillis()
        fun event(action: Int, time: Long): MotionEvent {
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { x = 40f; y = 40f; pressure = 1f; size = 1f }
            return MotionEvent.obtain(down, time, action, 1, arrayOf(properties), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags)
        }
        compose.runOnIdle {
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, down))
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, down + 40))
        }
        compose.waitForIdle()
    }

    @Test
    fun aTouchThroughAnOverlayIsRefusedAndReportedOnce() {
        var clicks = 0
        var blocked = 0
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            Box(Modifier.size(120.dp).obscuredTouchGuard { blocked++ }.clickable { clicks++ })
        }
        tap(view, MotionEvent.FLAG_WINDOW_IS_OBSCURED)
        assertEquals(0, clicks, "the control must not act on an obscured touch")
        assertEquals(1, blocked)
        tap(view, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        assertEquals(1, clicks, "a keyboard on screen (partially obscured) must not block the control")
        assertEquals(1, blocked)
        tap(view, 0)
        assertEquals(2, clicks, "a clean touch still clicks")
        assertEquals(1, blocked)
    }

    /** Audit BTS-01: a strict guard (the review's Sign button) refuses a partly covered window too. */
    @Test
    fun aStrictGuardAlsoRefusesAPartiallyObscuredTouch() {
        var clicks = 0
        var blocked = 0
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            Box(Modifier.size(120.dp).strictObscuredTouchGuard { blocked++ }.clickable { clicks++ })
        }
        tap(view, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED)
        assertEquals(0, clicks, "an overlay over the amount must not let Sign through")
        assertEquals(1, blocked)
        tap(view, 0)
        assertEquals(1, clicks)
    }

    @Test
    fun revealedSecretsMarkTheViewSensitiveWhileShown() {
        var revealed by mutableStateOf(false)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            AccessibilitySensitive(active = revealed)
        }
        compose.runOnIdle { assertFalse(view.isAccessibilityDataSensitive) }
        revealed = true
        compose.runOnIdle { assertTrue(view.isAccessibilityDataSensitive) }
        revealed = false
        compose.runOnIdle { assertFalse(view.isAccessibilityDataSensitive, "the previous value comes back") }
    }

    @Test
    fun twoSensitiveScreensDoNotUndoEachOther() {
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            if (first) AccessibilitySensitive(active = true)
            if (second) AccessibilitySensitive(active = true)
        }
        compose.runOnIdle { assertEquals(2, SensitiveMarking.holds(view)) }
        first = false
        compose.runOnIdle { assertTrue(view.isAccessibilityDataSensitive, "still held by the second") }
        second = false
        compose.runOnIdle {
            assertFalse(view.isAccessibilityDataSensitive)
            assertEquals(0, SensitiveMarking.holds(view))
        }
    }

    @Test
    fun theDebugHarnessCanTurnSensitiveMarkingOff() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalSensitiveDataMarking provides false) { AccessibilitySensitive(active = true) }
        }
        compose.runOnIdle { assertFalse(view.isAccessibilityDataSensitive) }
    }

    @Test
    fun secretFieldsKeepAutofillAwayUntilTheyLeave() {
        var shown by mutableStateOf(true)
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            if (shown) ExcludeFromAutofill()
        }
        compose.runOnIdle {
            val host = view.parent as View
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, host.importantForAutofill)
            // What the Autofill framework asks of the Compose view itself.
            assertFalse(view.isImportantForAutofill)
        }
        shown = false
        compose.runOnIdle {
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_AUTO, (view.parent as View).importantForAutofill)
            assertTrue(view.isImportantForAutofill)
        }
    }

    @Test
    fun fieldsInsideNoPersonalizedLearningOpenAnIncognitoKeyboard() {
        val state = TextFieldState()
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            NoPersonalizedLearning { BasicTextField(state, Modifier.testTag("field")) }
        }
        compose.onNodeWithTag("field").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val info = EditorInfo()
            assertNotNull(view.onCreateInputConnection(info), "the focused field has an input session")
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        }
    }
}
