package com.bittool.signer.core.designsystem.window

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
 * [WindowFocusHold] (QA wallets-12, regression-03, backlog B30): a window that comes back from under a dialog in
 * keyboard mode, or whose focused view clears its focus, has nothing focused instead of the first control Android picks
 * (`ViewRootImpl.leaveTouchMode` → `restoreDefaultFocus`, `View.rootViewRequestFocus`), and its controls take the focus
 * again as soon as the hold ends. It never touches the window root, which `:core:security`'s `WindowFocusReturn`
 * blocks, so the two end in any order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S_V2, Build.VERSION_CODES.BAKLAVA])
class WindowFocusHoldTest {
    private lateinit var activity: ComponentActivity
    private lateinit var first: Button
    private lateinit var second: Button

    private val decor: ViewGroup get() = activity.window.decorView as ViewGroup

    /** The decor's child that holds the content: what a hold blocks. */
    private val contentRoot: ViewGroup get() = decor.getChildAt(0) as ViewGroup

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
        val hold = assertNotNull(WindowFocusHold.holdIfUnfocused(first))
        val dialog = openDialogAndTypeInIt()
        assertNull(focused(), "Not even when the display leaves touch mode under the dialog (API 33+)")
        hold.releaseOnWindowFocus()
        assertTrue(hold.isHeld, "Held until the window has the focus back")
        closeDialog(dialog)

        assertNull(focused())
        assertFalse(hold.isHeld)
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, decor.descendantFocusability, "The root is never changed")
        assertTrue(second.requestFocus(), "Keyboard navigation works again at once")
    }

    @Test
    fun aFocusedControlIsNotHeldAndKeepsItsFocus() {
        WindowSignals.setTouchMode(decor, inTouchMode = false)
        assertTrue(second.requestFocus())
        assertNull(WindowFocusHold.holdIfUnfocused(first), "Android returns the focus to the control by itself")
        closeDialog(openDialogAndTypeInIt())
        assertSame(second, focused())
    }

    @Test
    fun clearingTheFocusInKeyboardModeFocusesNothingElse() {
        // QA regression-03: View.clearFocus() hands the focus to the window's first control in keyboard mode.
        WindowSignals.setTouchMode(decor, inTouchMode = false)
        assertTrue(second.requestFocus())
        second.clearFocus()
        assertSame(first, focused(), "The platform behaviour")

        assertTrue(second.requestFocus())
        WindowFocusHold.clearingFocus(second) { second.clearFocus() }
        assertNull(focused())
        assertTrue(first.requestFocus(), "Not held afterwards")
    }

    @Test
    fun holdsAddUpAndTheLastOneEndsTheBlock() {
        val before = contentRoot.descendantFocusability
        val sheet = WindowFocusHold.hold(first)
        val dialog = WindowFocusHold.hold(second)
        dialog.release()
        assertTrue(sheet.isHeld)
        assertFalse(first.requestFocus(), "Still held by the other hold")
        sheet.release()
        assertEquals(before, contentRoot.descendantFocusability)
        WindowSignals.setTouchMode(decor, inTouchMode = false)
        assertTrue(first.requestFocus())
    }

    /**
     * `:core:security`'s `WindowFocusReturn` (the lock window, the Wallets dialogs) blocks the decor and later puts
     * back what it found there; a hold of this module blocks the decor's content child. Ending in either order leaves the
     * window focusable, and neither end lets the window take the focus while the other still holds.
     */
    @Test
    fun aHoldAndARootBlockOfTheSecurityModuleEndInAnyOrder() {
        first.isFocusableInTouchMode = true
        val contentBefore = contentRoot.descendantFocusability
        fun securityBlock(): () -> Unit {
            val previous = decor.descendantFocusability
            decor.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            return { decor.descendantFocusability = previous }
        }

        val endSecurity = securityBlock()
        val hold = WindowFocusHold.hold(first)
        endSecurity()
        assertFalse(first.requestFocus(), "Still held by the design system")
        hold.release()
        assertTrue(first.requestFocus())
        first.clearFocus()

        val hold2 = WindowFocusHold.hold(first)
        val endSecurity2 = securityBlock()
        hold2.release()
        assertFalse(first.requestFocus(), "Still blocked by the security module")
        endSecurity2()
        assertTrue(first.requestFocus())
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, decor.descendantFocusability)
        assertEquals(contentBefore, contentRoot.descendantFocusability)
    }

    @Test
    fun aHoldWaitingForTheWindowFocusEndsAfterATimeout() {
        val hold = WindowFocusHold.hold(first)
        WindowSignals.windowFocus(decor, hasFocus = false)
        hold.releaseOnWindowFocus()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WindowFocusHold.RELEASE_TIMEOUT_MILLIS - 1))
        assertTrue(hold.isHeld)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2))
        assertFalse(hold.isHeld, "A window focus that never comes cannot keep the window unfocusable")
    }

    @Test
    fun aWindowThatHasTheFocusAlreadyIsReleasedAtOnce() {
        val hold = WindowFocusHold.hold(first)
        assertTrue(decor.hasWindowFocus())
        hold.releaseOnWindowFocus()
        assertFalse(hold.isHeld)
    }
}
