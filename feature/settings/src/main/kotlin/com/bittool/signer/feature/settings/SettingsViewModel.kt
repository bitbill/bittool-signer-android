package com.bittool.signer.feature.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.common.BuildInfo
import com.bittool.signer.core.data.PreferenceValues
import com.bittool.signer.core.data.SignerPreferences
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.AppLanguage
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.Tab
import com.bittool.signer.core.security.AppAccessController
import com.bittool.signer.core.security.AppAccessState
import com.bittool.signer.feature.settings.language.LanguageController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Settings tab draws (iOS `SettingsTab`). */
@Immutable
data class SettingsUiState(
    val appLockEnabled: Boolean = false,
    /** iOS `.disabled(changingLock || wallets.isEmpty || (!biometry.isAvailable && !access.isEnabled))`. */
    val appLockToggleEnabled: Boolean = false,
    val biometricsAvailable: Boolean = false,
    /** The last App Lock change failure, under the switch (iOS `lockError`). */
    val appLockError: UiText? = null,
    val appearance: Appearance = Appearance.System,
    val language: AppLanguage = AppLanguage.System,
    /** iOS "1.0.0 (1)". */
    val version: String = "",
    val showsThemeDialog: Boolean = false,
)

/**
 * The Settings root (iOS `SettingsTab`, activity-settings.md §3, app-shell.md §11.11): App Lock through
 * [AppAccessController] (enabling needs a wallet and a biometric check, an enabled lock can always be turned off, one
 * change at a time), the theme preference (`:app` applies it app-wide), the per-app language, and the build version.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: SignerStore,
    private val access: AppAccessController,
    private val preferences: SignerPreferences,
    private val languages: LanguageController,
    buildInfo: BuildInfo,
    router: AppRouter,
) : ViewModel() {

    private data class Local(
        val changingLock: Boolean = false,
        val lockError: UiText? = null,
        val showsThemeDialog: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val language = MutableStateFlow(languages.current())
    private val hasWallets = store.state.map { it.wallets.isNotEmpty() }.distinctUntilChanged()
    private val version = buildInfo.displayVersion

    /**
     * Starts from the values as they are now, not from defaults: the first frame already shows an enabled App Lock as on
     * and the chosen theme (otherwise the switch would visibly flip on, and "System" change to "Dark", as the page
     * opens).
     */
    val uiState: StateFlow<SettingsUiState> = combine(
        access.state,
        hasWallets,
        preferences.values,
        language,
        local,
    ) { access, hasWallets, prefs, language, local ->
        build(access, hasWallets, prefs, language, local)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        build(access.state.value, store.state.value.wallets.isNotEmpty(), preferences.values.value, language.value, local.value),
    )

    private fun build(access: AppAccessState, hasWallets: Boolean, prefs: PreferenceValues?, language: AppLanguage, local: Local) =
        SettingsUiState(
            appLockEnabled = access.isEnabled,
            appLockToggleEnabled = !local.changingLock && hasWallets && (access.biometricsAvailable || access.isEnabled),
            biometricsAvailable = access.biometricsAvailable,
            appLockError = local.lockError,
            appearance = prefs?.appearance ?: Appearance.System,
            language = language,
            version = version,
            showsThemeDialog = local.showsThemeDialog,
        )

    /** Emits when the Settings tab is tapped again while shown (the page scrolls back to the top). */
    val scrollToTop: Flow<Unit> = router.reselections.filter { it == Tab.Settings }.map { }

    /**
     * The App Lock switch (iOS `changeAppLock`, `SettingsTab.swift:103-111`): ignored while a change is in flight;
     * clears the previous error; a dismissed prompt is silent and leaves the switch as it was (it reflects
     * `AppAccessState.isEnabled`), any other failure appears under the switch.
     */
    fun onAppLockChange(enabled: Boolean) {
        val lock = access.state.value
        val allowed = store.state.value.wallets.isNotEmpty() && (lock.biometricsAvailable || lock.isEnabled)
        if (local.value.changingLock || !allowed || enabled == lock.isEnabled) return
        local.update { it.copy(changingLock = true, lockError = null) }
        viewModelScope.launch {
            try {
                access.setEnabled(enabled)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) {
                local.update { it.copy(lockError = e.toUiText()) }
            } finally {
                local.update { it.copy(changingLock = false) }
            }
        }
    }

    fun onThemeClicked() = local.update { it.copy(showsThemeDialog = true) }

    fun onThemeDismissed() = local.update { it.copy(showsThemeDialog = false) }

    /** Writes the `appearance` preference; `:app` applies it to every window (night mode, lock window, cover). */
    fun onAppearanceSelected(appearance: Appearance) {
        local.update { it.copy(showsThemeDialog = false) }
        viewModelScope.launch {
            try {
                preferences.setAppearance(appearance)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed DataStore write keeps the previous theme, which the row keeps showing.
            }
        }
    }

    /** Re-reads the per-app language (the activity was recreated, or Android's "App languages" changed it). */
    fun refreshLanguage() {
        language.value = languages.current()
    }
}
