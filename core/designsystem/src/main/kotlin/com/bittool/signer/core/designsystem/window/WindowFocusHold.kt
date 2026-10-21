package com.bittool.signer.core.designsystem.window

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView

/**
 * Keyboard focus around the windows the design system opens (`SignerAlertDialog`, `SignerBottomSheet`,
 * `SignerDropdownMenu`) and around clearing the focus (QA wallets-12, regression-03, backlog B30).
 *
 * In keyboard (non-touch) mode Android gives a window that has no focused view its first focusable view: when the
 * window gets the window focus back after a dialog, a sheet or a menu was used with the keyboard, when the display
 * leaves touch mode while another window has the focus (API 33+), and when a focused view clears its focus
 * (`View.clearFocus()` asks the root for a new focus, `rootViewRequestFocus`). That view is the page's first control,
 * usually its back arrow: a focus ring where the person was not working. iOS leaves nothing focused in all three cases.
 *
 * A [Hold] keeps the views of one window from taking the focus while it lasts, so the window comes back with nothing
 * focused; the next arrow or Tab key focuses the first control as usual. A window whose view has the focus keeps it
 * ([holdIfUnfocused] does not hold it): Android returns the focus there, to the control that opened the dialog. Holds
 * of one window add up; the window takes focus again when the last one ends. Main thread only.
 *
 * The same contract as `:core:security`'s `WindowFocusReturn` (the App Lock window and the Wallets dialogs), which this
 * module cannot call (`:core:security` depends on it). The two never touch the same view: that one blocks the window's
 * root (its `DecorView`), this one the root's child that holds the content, so holds of both on one window combine
 * (either keeps the window unfocusable) and each restores only what it changed, in whatever order they end.
 */
object WindowFocusHold {
    /**
     * How long a hold that waits for the window focus ([Hold.releaseOnWindowFocus]) lasts at most: the window focus
     * normally returns within a frame or two of the window above going away.
     */
    @VisibleForTesting
    internal const val RELEASE_TIMEOUT_MILLIS = 1_000L

    /** The block of every held window, by the view group it blocks; an entry ends with the window's last hold. */
    private val blocks = mutableMapOf<ViewGroup, FocusBlock>()

    /**
     * Holds [view]'s window: from now on none of its views takes the focus until the hold ends. A focused view keeps
     * the focus until the caller clears it; with the hold in place that clearing hands the focus to nobody.
     */
    @MainThread
    fun hold(view: View): Hold {
        val target = blockTarget(view) ?: return Hold(null)
        val block = blocks.getOrPut(target) { FocusBlock(target) { blocks.remove(target) } }
        return Hold(block).also(block::add)
    }

    /**
     * [hold] for a window above [view]'s that opens now (a dialog, a sheet, a menu), but only when nothing in [view]'s
     * window has the focus; otherwise null: the focused control keeps the focus and gets it back when the window above
     * goes.
     */
    @MainThread
    fun holdIfUnfocused(view: View): Hold? {
        if (!view.isAttachedToWindow || view.rootView.findFocus() != null) return null
        return hold(view)
    }

    /**
     * Runs [clear] (a `FocusManager.clearFocus()`, a `View.clearFocus()`) while [view]'s window is held, so the focus
     * it takes goes nowhere instead of to the window's first control in keyboard mode. In touch mode Android hands the
     * focus to nobody anyway and this changes nothing.
     */
    @MainThread
    inline fun <T> clearingFocus(view: View, clear: () -> T): T {
        val hold = hold(view)
        try {
            return clear()
        } finally {
            hold.release()
        }
    }

    /**
     * The view group a hold of [view]'s window blocks: the root's child that contains [view] (in an activity or dialog
     * window, the `DecorView`'s content child), never the root itself, which `:core:security` blocks. Only when [view]
     * hangs right under the root (or is it) the root is blocked: blocking [view]'s own descendants would not keep
     * [view] from taking the focus.
     */
    private fun blockTarget(view: View): ViewGroup? {
        val root = view.rootView as? ViewGroup ?: return null
        var child: View = view
        while (true) {
            val parent = child.parent as? View ?: break
            if (parent === root) break
            child = parent
        }
        return if (child !== view && child !== root && child.parent === root && child is ViewGroup) child else root
    }

    /** One hold of one window; see [WindowFocusHold]. */
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
        private val target: ViewGroup,
        private val onEnded: () -> Unit,
    ) : ViewTreeObserver.OnWindowFocusChangeListener {
        private val previousFocusability = target.descendantFocusability
        private val active = mutableSetOf<Hold>()
        private val leaving = mutableSetOf<Hold>()
        private val handler = Handler(Looper.getMainLooper())
        private val timeout = Runnable { leaving.toList().forEach(::remove) }
        private var observer: ViewTreeObserver? = null

        init {
            target.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            observer = target.viewTreeObserver.also { it.addOnWindowFocusChangeListener(this) }
        }

        fun holds(hold: Hold): Boolean = hold in active

        fun add(hold: Hold) {
            active += hold
        }

        fun releaseOnWindowFocus(hold: Hold) {
            if (hold !in active || !leaving.add(hold)) return
            if (target.hasWindowFocus()) {
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
            target.descendantFocusability = previousFocusability
            onEnded()
        }
    }
}

/**
 * For a window that floats above the caller's (a dialog, a bottom sheet, a focusable popup such as a `DropdownMenu`):
 * call it in the caller's composition next to the window, outside the window's content, and only while the window is
 * shown (it holds from its first composition). While the window is up, and until the caller's window has the window
 * focus back after it, nothing in the caller's window takes the keyboard focus, unless something there already had it
 * ([WindowFocusHold.holdIfUnfocused]). So a dialog confirmed, a sheet closed or a menu item chosen with the keyboard
 * leaves no focus ring on the page's first control (QA wallets-12, backlog B30). `SignerAlertDialog`,
 * `SignerBottomSheet` and `SignerDropdownMenu` call it themselves; a feature-owned `Dialog` / `Popup` calls it too.
 */
@Composable
fun WindowFocusHoldEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val hold = WindowFocusHold.holdIfUnfocused(view)
        onDispose { hold?.releaseOnWindowFocus() }
    }
}

/**
 * `FocusManager.clearFocus()` that leaves nothing focused, also in keyboard mode, where Android would otherwise hand
 * the focus to the window's first control at once ([WindowFocusHold.clearingFocus]; iOS `focused = false`, QA
 * regression-03). For a field's Done / Enter action and other "stop editing" moments.
 */
@Composable
fun rememberClearFocus(): () -> Unit {
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    return remember(view, focusManager) { { clearFocusQuietly(view, focusManager) } }
}

/** [rememberClearFocus] outside composition. */
@MainThread
fun clearFocusQuietly(view: View, focusManager: FocusManager) {
    WindowFocusHold.clearingFocus(view) { focusManager.clearFocus() }
}
