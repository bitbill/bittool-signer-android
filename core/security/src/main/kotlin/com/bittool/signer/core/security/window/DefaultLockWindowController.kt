package com.bittool.signer.core.security.window

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.bittool.signer.core.common.log.Log
import com.bittool.signer.core.security.LockWindowController
import com.bittool.signer.core.security.SecureWindows
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a lock window is on screen, including its fade-out; [DefaultWindowGate] keeps new windows back until it is
 * gone (they would otherwise be added above it, or appear over the fading lock screen).
 */
internal interface LockWindowPresence {
    val isPresent: Boolean

    /** [listener] runs synchronously on the main thread whenever [isPresent] changes. */
    fun addPresenceListener(listener: () -> Unit)

    /** For a gate without a lock window (tests, previews). */
    object None : LockWindowPresence {
        override val isPresent: Boolean = false
        override fun addPresenceListener(listener: () -> Unit) = Unit
    }
}

/**
 * The App Lock window (app-shell.md §11.10, §15.7; Ownbit `LockWindow.kt`), the counterpart of the iOS
 * `AppLockWindowPresenter` window at level `.alert + 1` (AppLockView.swift:133-186): "A separate authentication window
 * covers the existing navigation and every presented sheet. It preserves their state without exposing them while
 * locked."
 *
 * It is an application window of the activity (a non-floating [ComponentDialog], `TYPE_APPLICATION` with the activity
 * token), so it has a real [Window] for `FLAG_SECURE` and `setHideOverlayWindows(true)` (G4.1), and being added after
 * every open dialog or bottom sheet it covers them while they keep their state:
 *
 * - the whole screen, edge to edge (`MATCH_PARENT`, decor not fitting system windows, cutout `ALWAYS`/`SHORT_EDGES`,
 *   transparent bars, bar icons following the app's day/night configuration): the same frame as the activity window;
 *   the IME resizes it (`ADJUST_RESIZE`), and it is hidden when the window takes focus (`STATE_ALWAYS_HIDDEN`);
 * - `FLAG_SECURE` unless the process runs the debug screenshot tour ([DefaultSecureWindowPolicy.captureAllowed]);
 * - no window animation and no dim: it appears in the same frame; on [hide] the content fades out over
 *   [FADE_OUT_MILLIS] (an allowed Android polish, §15.7: content is being revealed to someone who just authenticated),
 *   honouring the animator duration scale;
 * - Back (key or predictive gesture) moves the task to the back and never unlocks;
 * - its content runs under the activity's lifecycle and `ViewModelStore` (Hilt view models, "is the activity RESUMED"
 *   for the auto-prompt) but saves no state: secrets never reach a `Bundle`;
 * - while it is shown the activity window is hidden from accessibility services, so the lock screen is the only
 *   surface TalkBack can traverse (iOS `accessibilityViewIsModal`); before it is shown the activity loses focus and
 *   the IME (iOS `source.endEditing(true)`);
 * - autofill and content capture never see it.
 *
 * Each lock engagement gets a new window and so a new composition (iOS creates a new window per lock, which is what
 * makes the lock screen auto-prompt once per lock). A destroyed activity takes its window with it at once. Main thread
 * only.
 */
@Singleton
internal class DefaultLockWindowController @Inject constructor(
    private val windowPolicy: DefaultSecureWindowPolicy,
) : LockWindowController, LockWindowPresence {

    private val _isShowing = MutableStateFlow(false)
    override val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    private var current: LockWindow? = null
    private val presenceListeners = mutableListOf<() -> Unit>()

    /** What Back does on the lock window (tests replace it). */
    @VisibleForTesting
    internal var onBack: (Activity) -> Unit = { it.moveTaskToBack(true) }

    override val isPresent: Boolean get() = current != null

    override fun addPresenceListener(listener: () -> Unit) {
        presenceListeners += listener
    }

    @MainThread
    override fun show(activity: Activity, content: @Composable () -> Unit) {
        val existing = current
        if (existing != null && existing.activity === activity && !existing.isFadingOut) {
            existing.content = content
            _isShowing.value = true
            return
        }
        // A window of another activity, or one fading out after the last unlock: a new lock gets a new window.
        existing?.dismissNow()
        val host = requireNotNull(activity as? ComponentActivity) { "The lock window needs a ComponentActivity host" }
        if (host.isFinishing || host.isDestroyed) return
        val window = LockWindow(
            activity = host,
            secure = !windowPolicy.captureAllowed,
            initialContent = content,
            onBack = { onBack(host) },
            onRemoved = ::onRemoved,
        )
        current = window
        if (!window.show()) {
            current = null
            return
        }
        _isShowing.value = true
        notifyPresence()
    }

    @MainThread
    override fun hide() {
        val window = current ?: return
        _isShowing.value = false
        window.fadeOutAndDismiss()
    }

    private fun onRemoved(window: LockWindow) {
        if (current !== window) return
        current = null
        _isShowing.value = false
        notifyPresence()
    }

    private fun notifyPresence() {
        presenceListeners.toList().forEach { it() }
    }

    internal companion object {
        /** The unlock fade-out (iOS hides at once; Ownbit fades its copy over ≈ 0.35 s). */
        const val FADE_OUT_MILLIS = 300L
    }
}

/** One lock window on one activity; see [DefaultLockWindowController]. */
private class LockWindow(
    val activity: ComponentActivity,
    secure: Boolean,
    initialContent: @Composable () -> Unit,
    onBack: () -> Unit,
    private val onRemoved: (LockWindow) -> Unit,
) {
    var content: @Composable () -> Unit by mutableStateOf(initialContent)

    var isFadingOut: Boolean = false
        private set

    private var attached = false
    private var hiddenAccessibility: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dismissAfterFade = Runnable { dismissNow() }
    private val destroyObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) dismissNow()
    }

    private val composeView = ComposeView(activity).apply {
        // Its own key for the (discarded) saveable state, distinct from every other compose view of the activity.
        id = View.generateViewId()
        setViewTreeLifecycleOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    private val dialog = ComponentDialog(activity, android.R.style.Theme_Translucent_NoTitleBar).apply {
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        setTitle(activity.applicationInfo.loadLabel(activity.packageManager))
        onBackPressedDispatcher.addCallback(this) { onBack() }
        setContentView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setOnDismissListener { removed() }
    }

    init {
        val window = checkNotNull(dialog.window)
        configure(window, secure)
        composeView.setContent { LockWindowContent(window) { content() } }
    }

    fun show(): Boolean {
        releaseActivityFocus()
        try {
            dialog.show()
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, e) { "Lock window refused: activity not running" }
            return false
        } catch (e: IllegalStateException) {
            Log.w(TAG, e) { "Lock window refused" }
            return false
        }
        attached = true
        activity.lifecycle.addObserver(destroyObserver)
        hideActivityFromAccessibility()
        return true
    }

    /**
     * Starts the unlock fade-out. The window is removed on a timer rather than on the animation's end, so it also goes
     * away when animators are paused (the app went to the background mid-fade); with animations off it goes at once.
     */
    fun fadeOutAndDismiss() {
        if (!attached || isFadingOut) return
        isFadingOut = true
        restoreActivityAccessibility()
        composeView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        composeView.clearFocus()
        dialog.window?.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.ime()) }
        val duration = if (ValueAnimator.areAnimatorsEnabled()) {
            (DefaultLockWindowController.FADE_OUT_MILLIS * ValueAnimator.getDurationScale()).toLong()
        } else {
            0L
        }
        if (duration <= 0L) {
            dismissNow()
            return
        }
        composeView.animate().alpha(0f).setDuration(DefaultLockWindowController.FADE_OUT_MILLIS).setInterpolator(FAST_OUT_SLOW_IN).start()
        mainHandler.postDelayed(dismissAfterFade, duration)
    }

    fun dismissNow() {
        if (!attached) return
        try {
            dialog.dismiss()
        } catch (e: IllegalArgumentException) {
            // Already removed together with the activity's windows.
        }
        removed()
    }

    /** Runs once, whether the window went through [dismissNow] or was dismissed by anything else. */
    private fun removed() {
        if (!attached) return
        attached = false
        mainHandler.removeCallbacks(dismissAfterFade)
        composeView.animate().cancel()
        activity.lifecycle.removeObserver(destroyObserver)
        restoreActivityAccessibility()
        onRemoved(this)
    }

    /** iOS `source.endEditing(true)` before the lock window becomes key (AppLockView.swift:164). */
    private fun releaseActivityFocus() {
        val window = activity.window
        activity.currentFocus?.clearFocus()
        window.peekDecorView()?.let { WindowCompat.getInsetsController(window, it).hide(WindowInsetsCompat.Type.ime()) }
    }

    private fun hideActivityFromAccessibility() {
        val decor = activity.window.peekDecorView() ?: return
        if (hiddenAccessibility == null) hiddenAccessibility = decor.importantForAccessibility
        decor.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private fun restoreActivityAccessibility() {
        val previous = hiddenAccessibility ?: return
        hiddenAccessibility = null
        activity.window.peekDecorView()?.importantForAccessibility = previous
    }

    private companion object {
        const val TAG = "LockWindow"
        val FAST_OUT_SLOW_IN = PathInterpolator(0.4f, 0f, 0.2f, 1f)

        @Suppress("DEPRECATION")
        fun configure(window: Window, secure: Boolean) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window.setWindowAnimations(0)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            SecureWindows.hideOverlayWindows(window)
            SecureWindows.excludeFromAutofill(window.decorView)
            SecureWindows.excludeFromContentCapture(window.decorView)
        }
    }
}

/** The lock screen content plus bar icons that follow the app's day/night configuration (AppCompat night mode). */
@Composable
private fun LockWindowContent(window: Window, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    content()
}
