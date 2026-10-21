package com.bittool.signer

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.bittool.signer.appearance.AppearanceController
import com.bittool.signer.core.common.time.MonotonicClock
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.l10n.AppLocales
import com.bittool.signer.core.model.TokenIconSource
import com.bittool.signer.core.security.AppAccessController
import com.bittool.signer.core.security.AppAccessState
import com.bittool.signer.core.security.AppLifecycleMonitor
import com.bittool.signer.core.security.LockWindowController
import com.bittool.signer.core.security.WindowGate
import com.bittool.signer.di.ShellEntryPoint
import com.bittool.signer.lock.LockWindowRoot
import com.bittool.signer.root.LaunchGate
import com.bittool.signer.root.RootViewModel
import com.bittool.signer.root.SignerRoot
import com.bittool.signer.shell.ShellLaunch
import com.bittool.signer.shell.ShellLaunchHooks
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The single activity (app-shell.md §15; ARCHITECTURE §2.1). An `AppCompatActivity`: per-app languages below API 33 and
 * the `FragmentActivity` that `BiometricPrompt` needs.
 *
 * `onCreate`, in this order: the debug UI-test harness reads its launch extras (before Hilt injects anything, gaps.md
 * G3) → the system splash is installed → `FLAG_SECURE`, no Recents screenshot, hidden foreign overlays, autofill and
 * content capture excluded (`SecureWindowPolicy`, never toggled) → the lifecycle monitor attaches (it sees every edge)
 * → the Theme preference is followed → `super.onCreate` → Arabic keeps Latin digits (it needs the AppCompat delegate
 * that `super.onCreate` registers) → edge to edge → the splash is kept until App Lock and the first wallet load are
 * known ([LaunchGate]) → the lock window follows App Lock → content.
 *
 * Lifecycle (iOS scene phases, app-shell.md §10, §15.4): `onStart` runs App Lock's grace decision and shows the lock
 * window in the same callback, so a relock is in the very first frame; `onStop` that is not a configuration change
 * (a per-app language switch recreates the activity) suspends the session and starts App Lock's background clock.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var store: SignerStore
    @Inject lateinit var access: AppAccessController
    @Inject lateinit var lifecycleMonitor: AppLifecycleMonitor
    @Inject lateinit var lockWindow: LockWindowController
    @Inject lateinit var windowGate: WindowGate
    @Inject lateinit var appearance: AppearanceController
    @Inject lateinit var tokenIcons: TokenIconSource
    @Inject lateinit var clock: MonotonicClock

    private val rootViewModel: RootViewModel by viewModels()
    private lateinit var launch: ShellLaunch
    private var exposeTestTags = false

    /** Whether this activity has put up the lock window for the current lock. */
    private var lockShown = false

    /** Whether App Lock is still reading its preference (the lock window then shows the launch view). */
    private val resolving: StateFlow<Boolean> by lazy {
        access.state.map { it.resolving }.stateIn(lifecycleScope, SharingStarted.Eagerly, access.state.value.resolving)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        launch = ShellLaunchHooks.configure(this, intent)
        val splash = installSplashScreen()
        val shell = EntryPointAccessors.fromApplication(applicationContext, ShellEntryPoint::class.java)
        shell.secureWindowPolicy().apply(this, allowCapture = launch.allowCapture)
        shell.lifecycleMonitor().attach(this)
        shell.appearance().start()
        super.onCreate(savedInstanceState)
        // Plain `ar` from Android's own App languages setting → `ar-u-nu-latn` (a re-apply recreates the activity once).
        // After super.onCreate: on API 33+ AppCompat reads and writes the framework list only through a live activity
        // delegate, which super.onCreate registers; before it the list read as empty and nothing happened (audit A11).
        AppLocales.ensureLatinDigits()
        // After AppCompat installed its decor (reading the decor any earlier would install it first).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // enableEdgeToEdge turns the contrast scrim back on for `auto` styles: it painted a strip over the grouped page.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        exposeTestTags = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        val gate = LaunchGate(clock)
        splash.setKeepOnScreenCondition { gate.keepOnScreen(access.state.value, store.state.value) }

        // Not lifecycle-gated: a lock that engages must be up before anything else is drawn.
        lifecycleScope.launch {
            combine(access.state, lifecycleMonitor.isCaptured) { state, _ -> state }.collect { syncLockWindow(it) }
        }
        lifecycleScope.launch {
            // The window resolves its background once: follow the Theme (a system switch: onConfigurationChanged).
            appearance.appearance.collect { applyWindowBackground() }
        }

        setContent {
            SignerRoot(viewModel = rootViewModel, tokenIcons = tokenIcons, exposeTestTags = exposeTestTags)
        }
    }

    override fun onStart() {
        super.onStart()
        rootViewModel.onEnterForeground()
        // In the same callback: a lock decided by the grace rule is shown before the first frame.
        syncLockWindow(access.state.value)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) rootViewModel.onEnterBackground()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // A system dark-mode switch while following the system, or AppCompat applying the in-app Theme.
        applyWindowBackground()
    }

    /**
     * The lock window and the window gate follow App Lock (app-shell.md §15.7): the gate closes in the same frame the
     * lock engages (dialogs and sheets requested meanwhile wait), the lock window is added above every open dialog and
     * sheet; on unlock the window fades and the gate reopens once it is gone.
     */
    private fun syncLockWindow(state: AppAccessState) {
        windowGate.setOpen(!state.showsLock && !lifecycleMonitor.isCaptured.value)
        if (state.showsLock) {
            if (!lockShown || !lockWindow.isShowing.value) {
                lockShown = true
                lockWindow.show(this) {
                    LockWindowRoot(
                        appearance = appearance.appearance,
                        phase = lifecycleMonitor.phase,
                        captured = lifecycleMonitor.isCaptured,
                        resolving = resolving,
                        exposeTestTags = exposeTestTags,
                    )
                }
            }
        } else if (lockShown) {
            lockShown = false
            lockWindow.hide()
        }
    }

    /**
     * The window background in the current appearance: the page colour behind the IME and predictive-back animations
     * (also the FLAG_SECURE snapshot's page). Resolved once per window, so it is re-applied after every switch (Ownbit D18).
     */
    private fun applyWindowBackground() {
        window.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, R.color.launch_background)))
    }
}
