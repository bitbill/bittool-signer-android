package com.bittool.signer.core.ui

import android.os.Build
import android.os.Looper
import android.view.View
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowViewRootImpl

/**
 * The window-focus and touch-mode notifications the window manager sends a window (Robolectric has no window manager
 * to send them), on the real `ViewRootImpl` code paths: what Android does to a window under a dialog, a sheet or a menu
 * when the person uses a hardware keyboard in that window above (QA wallets-12). Same as `:core:designsystem`'s test
 * helper.
 */
internal object WindowSignals {
    private fun viewRoot(view: View): Any = checkNotNull(view.rootView.parent) { "$view is not attached to a window" }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * A key typed or pressed in [windowAbove]'s window (the window with the focus) takes the display out of touch mode
     * (`ViewRootImpl.ensureTouchMode(false)`, from the input stage); from API 33 every other window of the display is
     * told at once ([others], `touchModeChanged`), before that only when it gets the window focus back.
     */
    fun leaveTouchModeIn(windowAbove: View, vararg others: View) {
        setTouchMode(windowAbove, inTouchMode = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            others.forEach { other ->
                val root = viewRoot(other)
                root.javaClass.getMethod("touchModeChanged", Boolean::class.javaPrimitiveType).invoke(root, false)
            }
        }
        idle()
    }

    /** A touch (true) or a key (false) in [view]'s window, which has the window focus. */
    fun setTouchMode(view: View, inTouchMode: Boolean) {
        val root = viewRoot(view)
        root.javaClass.getDeclaredMethod("ensureTouchMode", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(root, inTouchMode)
        idle()
    }

    /** [view]'s window gains or loses the window focus (with the display's current touch mode up to API 32). */
    fun windowFocus(view: View, hasFocus: Boolean) {
        Shadow.extract<ShadowViewRootImpl>(viewRoot(view)).callWindowFocusChanged(hasFocus)
        idle()
    }

    /** The root view of the window added last (a dialog, a sheet, a popup menu). */
    fun topWindowRoot(): View {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val field = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(global) as List<View>).last()
    }
}
