package com.bittool.signer.appearance

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.getSystemService
import com.bittool.signer.core.common.coroutines.ApplicationScope
import com.bittool.signer.core.common.coroutines.MainDispatcher
import com.bittool.signer.core.common.harness.UiTestEnvironment
import com.bittool.signer.core.data.SignerPreferences
import com.bittool.signer.core.model.Appearance
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.optionals.getOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Settings › Appearance › Theme applied app-wide (app-shell.md §2.2, §15.2; activity-settings.md §14.4; ARCHITECTURE
 * §2.1): Settings only writes the `appearance` preference; this applies it as AppCompat's default night mode (every
 * activity, the window background, framework-drawn UI) and on API 31+ also as the `UiModeManager` application night
 * mode, which the system persists so even the next cold start's system splash follows the in-app choice (iOS cannot;
 * an allowed improvement). [appearance] drives `SignerTheme` of the main window, the lock window and the privacy cover,
 * so all of them follow a change at once (iOS `PrivacyShieldTests`).
 *
 * The preference lives in DataStore, which cannot be read synchronously, and in a debug UI-test run it is a different
 * file that is only known after `MainActivity` configured the run (gaps.md G3). So:
 * - [applyCached] (Application.onCreate, before any activity) applies the appearance last applied in this process' app
 *   data, a one-value mirror in its own small preferences file (not the signer's storage; never written in a UI-test
 *   run), so no activity starts in the wrong appearance;
 * - [start] (MainActivity.onCreate, after the harness) follows the real preference from then on.
 *
 * Main thread only.
 */
@Singleton
class AppearanceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SignerPreferences,
    @ApplicationScope private val scope: CoroutineScope,
    @MainDispatcher private val main: CoroutineDispatcher,
    private val uiTests: Optional<UiTestEnvironment>,
) {
    private val _appearance = MutableStateFlow(Appearance.System)

    /** The appearance in effect (the mirrored value until the preference has been read). */
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    private val mirror by lazy { context.getSharedPreferences(MIRROR_FILE, Context.MODE_PRIVATE) }
    private var applied: Appearance? = null
    private var started = false

    /** Applies the mirrored appearance (Application.onCreate). */
    @MainThread
    fun applyCached() {
        val cached = Appearance.fromWire(mirror.getString(MIRROR_KEY, null))
        _appearance.value = cached
        applyNightMode(cached)
    }

    /** Starts following the `appearance` preference (its first read happens now). Idempotent. */
    @MainThread
    fun start() {
        if (started) return
        started = true
        scope.launch(main) {
            preferences.values.filterNotNull().map { it.appearance }.distinctUntilChanged().collect(::apply)
        }
    }

    /** Applies [appearance] everywhere and mirrors it for the next process start (not in a UI-test run). */
    @MainThread
    fun apply(appearance: Appearance) {
        _appearance.value = appearance
        applyNightMode(appearance)
        if (uiTests.getOrNull()?.run == null) mirror.edit().putString(MIRROR_KEY, appearance.wire).apply()
    }

    private fun applyNightMode(appearance: Appearance) {
        if (applied == appearance) return
        applied = appearance
        val mode = appearance.appCompatNightMode()
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Api31.setApplicationNightMode(context, appearance)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31 {
        fun setApplicationNightMode(context: Context, appearance: Appearance) {
            context.getSystemService<UiModeManager>()?.setApplicationNightMode(appearance.uiModeNightMode())
        }
    }

    internal companion object {
        const val MIRROR_FILE = "bittool_signer_shell"
        const val MIRROR_KEY = "appearance"
    }
}

/** AppCompat's default night mode for a Theme choice: System follows the device. */
fun Appearance.appCompatNightMode(): Int = when (this) {
    Appearance.System -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    Appearance.Light -> AppCompatDelegate.MODE_NIGHT_NO
    Appearance.Dark -> AppCompatDelegate.MODE_NIGHT_YES
}

/** The `UiModeManager` application night mode (API 31+) for a Theme choice: `MODE_NIGHT_AUTO` follows the device. */
fun Appearance.uiModeNightMode(): Int = when (this) {
    Appearance.System -> UiModeManager.MODE_NIGHT_AUTO
    Appearance.Light -> UiModeManager.MODE_NIGHT_NO
    Appearance.Dark -> UiModeManager.MODE_NIGHT_YES
}
