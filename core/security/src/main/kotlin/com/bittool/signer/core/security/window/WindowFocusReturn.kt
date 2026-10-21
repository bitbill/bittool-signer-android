package com.bittool.signer.core.security.window

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keyboard focus around the windows that open above the activity window: the App Lock window, dialogs, bottom sheets
 * and focusable popups such as a `DropdownMenu` (QA wallets-12).
 *
 * In keyboard (non-touch) mode Android gives a window that has no focused view the first focusable one when it gets
 * the window focus back, or (API 33+) when the display leaves touch mode while another window has the focus
 * (`ViewRootImpl.leaveTouchMode` → `restoreDefaultFocus`). After an unlock or a dialog confirmed with the Enter key that
 * is the page's first control: "Scan" on the Wallets tab, whose Material tooltip then stays up, or the back arrow after
 * "Rename Wallet". Neither is what the person was doing, and iOS leaves nothing focused there.
 *
 * A [Hold] keeps the views of one window (its decor's descendants) from taking the focus while it lasts, so such a
 * window comes back with nothing focused; the next arrow or Tab key then focuses the first control as usual (Android
 * does that for a window without a focused view). A window that has a focused view keeps it: Android returns the focus
 * there by itself, which is where it belongs (the control that opened the dialog). Holds of one window add up; the
 * window takes focus again when the last one ends. Main thread only.
 */
object WindowFocusReturn {
    /**
     * How long a hold that waits for the window focus ([Hold.releaseOnWindowFocus]) lasts at most: the window focus
     * normally returns within a frame or two of the window above going away.
     */
    @VisibleForTesting
    internal const val RELEASE_TIMEOUT_MILLIS = 1_000L

    /** The block of every held window, by its root view; an entry ends with the window's last hold. */
    private val blocks = mutableMapOf<ViewGroup, FocusBlock>()

    /**
     * Holds [view]'s window: from now on none of its views takes the focus until the hold ends. A focused view loses
     * the focus only when the caller clears it (the lock window does, iOS `endEditing(true)`); with the hold in place
     * that clearing no longer hands the focus to the first control in keyboard mode.
     */
    @MainThread
    fun hold(view: View): Hold {
        val root = view.rootView as? ViewGroup ?: return Hold(null)
        val block = blocks.getOrPut(root) { FocusBlock(root) { blocks.remove(root) } }
        return Hold(block).also(block::add)
    }

    /**
     * [hold] for a window above [view]'s that opens now (a dialog, a sheet), but only when nothing in [view]'s window
     * has the focus; otherwise null: the focused control keeps the focus and gets it back when the window above goes.
     */
    @MainThread
    fun holdIfUnfocused(view: View): Hold? {
        val root = view.rootView ?: return null
        if (!view.isAttachedToWindow || root.findFocus() != null) return null
        return hold(view)
    }

    /** One hold of one window; see [WindowFocusReturn]. */
    class Hold internal constructor(private val block: FocusBlock?) {
        /** Whether the hold still keeps its window from taking the focus. */
        val isHeld: Boolean get() = block?.holds(this) == true

        /**
         * Ends the hold once its window has the window focus back (after Android's own focus assignment, which then
         * finds nothing to focus), at the latest after [RELEASE_TIMEOUT_MILLIS]; at once when the window already has
         * the window focus. For the moment the window above goes away.
         */
        @MainThread
        fun releaseOnWindowFocus() {
            block?.releaseOnWindowFocus(this)
        }

        /** Ends the hold now. */
        @MainThread
        fun release() {
            block?.remove(this)
        }
    }

    /** The holds of one window, and the blocked focus they share. */
    internal class FocusBlock(
        private val root: ViewGroup,
        private val onEnded: () -> Unit,
    ) : ViewTreeObserver.OnWindowFocusChangeListener {
        private val previousFocusability = root.descendantFocusability
        private val active = mutableSetOf<Hold>()
        private val leaving = mutableSetOf<Hold>()
        private val handler = Handler(Looper.getMainLooper())
        private val timeout = Runnable { leaving.toList().forEach(::remove) }
        private var observer: ViewTreeObserver? = null

        init {
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            observer = root.viewTreeObserver.also { it.addOnWindowFocusChangeListener(this) }
        }

        fun holds(hold: Hold): Boolean = hold in active

        fun add(hold: Hold) {
            active += hold
        }

        fun releaseOnWindowFocus(hold: Hold) {
            if (hold !in active || !leaving.add(hold)) return
            if (root.hasWindowFocus()) {
                remove(hold)
                return
            }
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, RELEASE_TIMEOUT_MILLIS)
        }

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            if (hasFocus) leaving.toList().forEach(::remove)
        }

        fun remove(hold: Hold) {
            leaving -= hold
            if (!active.remove(hold) || active.isNotEmpty()) return
            handler.removeCallbacks(timeout)
            observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(this)
            observer = null
            root.descendantFocusability = previousFocusability
            onEnded()
        }
    }
}

/**
 * For a dialog, bottom sheet or focusable popup (a `DropdownMenu`) composed in the activity's composition: call it
 * next to the window, outside its content, and only while the window is wanted (it holds from its first composition).
 * While the window is up, and until the activity window has the focus back after it, nothing in the activity window
 * takes the keyboard focus, unless something there already had it ([WindowFocusReturn.holdIfUnfocused]). So confirming
 * the dialog or choosing a menu item with the Enter key does not leave a focus ring on the page's first control (QA
 * wallets-12).
 */
@Composable
fun WindowFocusReturnEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val hold = WindowFocusReturn.holdIfUnfocused(view)
        onDispose { hold?.releaseOnWindowFocus() }
    }
}
