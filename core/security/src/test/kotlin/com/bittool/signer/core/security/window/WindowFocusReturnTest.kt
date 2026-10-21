package com.bittool.signer.core.security.window

import android.app.Dialog
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [WindowFocusReturn] (QA wallets-12): a window that comes back from under a dialog or the lock window in keyboard mode
 * has nothing focused, instead of the first control Android picks (`ViewRootImpl.leaveTouchMode` →
 * `restoreDefaultFocus`), and its controls take the focus again as soon as it is back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2, Build.VERSION_CODES.BAKLAVA])
class WindowFocusReturnTest {
    private lateinit var activity: ComponentActivity
    private lateinit var first: Button
    private lateinit var second: Button

    private val decor: View get() = activity.window.decorView

    /** The window's focused view (Robolectric's `Activity.getCurrentFocus` does not follow the view tree). */
    private fun focused(): View? = decor.findFocus()

    @Before
    fun setUp() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        activity = controller.get()
        activity.setContentView(
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                first = Button(activity).also(::addView)
                second = Button(activity).also(::addView)
            },
        )
        controller.start().resume().visible()
        idle()
        WindowSignals.setTouchMode(decor, inTouchMode = true)
        WindowSignals.windowFocus(decor, hasFocus = true)
        assertNull(focused(), "In touch mode nothing is focused")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A dialog above the activity takes the window focus; the person then uses the keyboard in it. */
    private fun openDialogAndTypeInIt(): Dialog {
        val dialog = Dialog(activity).apply {
            setContentView(Button(activity))
            show()
        }
        idle()
        WindowSignals.windowFocus(decor, hasFocus = false)
        WindowSignals.leaveTouchModeIn(dialog.window!!.decorView, decor)
        return dialog
    }

    private fun closeDialog(dialog: Dialog) {
        dialog.dismiss()
        idle()
        WindowSignals.windowFocus(decor, hasFocus = true)
    }

    @Test
    fun withoutAHoldTheWindowComesBackWithItsFirstControlFocused() {
        // The platform behaviour the hold exists for: the focus ring lands on the page's first control.
        closeDialog(openDialogAndTypeInIt())
        assertSame(first, focused())
    }

    @Test
    fun aHeldWindowComesBackFromADialogWithNothingFocused() {
        val hold = assertNotNull(WindowFocusReturn.holdIfUnfocused(decor))
        val dialog = openDialogAndTypeInIt()
        assertNull(focused(), "Not even when the display leaves touch mode under the dialog (API 33+)")
        hold.releaseOnWindowFocus()
        assertTrue(hold.isHeld, "Held until the window has the focus back")
        closeDialog(dialog)

        assertNull(focused())
        assertFalse(hold.isHeld)
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, (decor as ViewGroup).descendantFocusability)
        assertTrue(second.requestFocus(), "Keyboard navigation works again at once")
    }

    @Test
    fun aFocusedControlIsNotHeldAndKeepsItsFocus() {
        WindowSignals.setTouchMode(decor, inTouchMode = false)
        assertTrue(second.requestFocus())
        assertNull(WindowFocusReturn.holdIfUnfocused(decor), "Android returns the focus to the control by itself")
        closeDialog(openDialogAndTypeInIt())
        assertSame(second, focused())
    }

    @Test
    fun clearingTheFocusOfAHeldWindowInKeyboardModeFocusesNothingElse() {
        // The lock window's `endEditing`: View.clearFocus() hands the focus to the first control in keyboard mode.
        WindowSignals.setTouchMode(decor, inTouchMode = false)
        assertTrue(second.requestFocus())
        val hold = WindowFocusReturn.hold(decor)
        second.clearFocus()
        assertNull(focused())
        hold.release()
        assertNull(focused())
    }

    @Test
    fun holdsAddUpAndTheLastOneEndsTheBlock() {
        val lock = WindowFocusReturn.hold(decor)
        val dialog = WindowFocusReturn.hold(decor)
        dialog.release()
        assertTrue(lock.isHeld)
        assertFalse(first.requestFocus(), "Still held by the other hold")
        lock.release()
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, (decor as ViewGroup).descendantFocusability)
        WindowSignals.setTouchMode(decor, inTouchMode = false)
        assertTrue(first.requestFocus())
    }

    @Test
    fun aHoldWaitingForTheWindowFocusEndsAfterATimeout() {
        val hold = WindowFocusReturn.hold(decor)
        WindowSignals.windowFocus(decor, hasFocus = false)
        hold.releaseOnWindowFocus()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WindowFocusReturn.RELEASE_TIMEOUT_MILLIS - 1))
        assertTrue(hold.isHeld)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2))
        assertFalse(hold.isHeld, "A window focus that never comes cannot keep the window unfocusable")
    }

    @Test
    fun aWindowThatHasTheFocusAlreadyIsReleasedAtOnce() {
        val hold = WindowFocusReturn.hold(decor)
        assertTrue(decor.hasWindowFocus())
        hold.releaseOnWindowFocus()
        assertFalse(hold.isHeld)
    }
}
