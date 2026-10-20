package com.bittool.signer.feature.settings

import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerPreferences
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.AppLanguage
import com.bittool.signer.core.model.Appearance
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.security.AppAccessState
import com.bittool.signer.core.security.testing.FakeAppAccessController
import com.bittool.signer.feature.settings.language.LanguageController
import com.bittool.signer.feature.settings.language.LanguageViewModel
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** The Settings root (iOS `SettingsTab`, app-shell.md §11.11, AppAccessTests' UI half). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(StandardTestDispatcher())

    private val wallet = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)

    private class FakeLanguages(var language: AppLanguage = AppLanguage.System) : LanguageController {
        val applied = mutableListOf<AppLanguage>()
        override fun current() = language
        override fun apply(language: AppLanguage) {
            applied += language
            this.language = language
        }
        override fun systemLocale(): Locale = Locale.forLanguageTag("en-GB")
    }

    private val store = FakeSignerStore(SignerState(isLoading = false, walletLoadSucceeded = true, wallets = listOf(wallet)))
    private val preferences = FakeSignerPreferences()
    private val languages = FakeLanguages()

    private fun TestScope.settings(access: FakeAppAccessController = FakeAppAccessController()) =
        SettingsViewModel(store, access, preferences, languages, SettingsTestData.buildInfo, AppRouter()).also { viewModel ->
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
        }

    @Test
    fun theVersionReadsLikeIos() = runTest {
        assertEquals("1.0.0 (1)", settings().uiState.value.version)
    }

    @Test
    fun appLockTurnsOnOnlyThroughTheController() = runTest {
        val access = FakeAppAccessController()
        val viewModel = settings(access)
        assertTrue(viewModel.uiState.value.appLockToggleEnabled)
        viewModel.onAppLockChange(true)
        advanceUntilIdle()
        assertEquals(listOf(true), access.setEnabledCalls)
        assertTrue(viewModel.uiState.value.appLockEnabled)
        assertTrue(viewModel.uiState.value.appLockToggleEnabled)
    }

    @Test
    fun aFailedBiometricCheckLeavesTheSwitchOffAndSaysWhy() = runTest {
        // gaps.md G1.18: the fake biometric fails; the switch stays off and the failure text appears under it.
        val access = FakeAppAccessController()
        access.setEnabledFailure = AppIssue("Authentication failed. Try again or use a wallet password.")
        val viewModel = settings(access)
        viewModel.onAppLockChange(true)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.appLockEnabled)
        assertEquals(UiText.Ios("Authentication failed. Try again or use a wallet password."), viewModel.uiState.value.appLockError)
        assertTrue(viewModel.uiState.value.appLockToggleEnabled, "the switch can be tried again")
    }

    @Test
    fun aDismissedPromptIsSilent() = runTest {
        val access = FakeAppAccessController()
        access.setEnabledFailure = CancellationException("dismissed")
        val viewModel = settings(access)
        viewModel.onAppLockChange(true)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.appLockError)
        assertFalse(viewModel.uiState.value.appLockEnabled)
    }

    @Test
    fun oneChangeAtATimeAndTheSwitchIsDisabledMeanwhile() = runTest {
        val gate = CompletableDeferred<Unit>()
        val access = object : com.bittool.signer.core.security.AppAccessController by FakeAppAccessController() {
            var calls = 0
            override suspend fun setEnabled(enabled: Boolean) {
                calls++
                gate.await()
            }
        }
        val viewModel = SettingsViewModel(store, access, preferences, languages, SettingsTestData.buildInfo, AppRouter())
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        viewModel.onAppLockChange(true)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.appLockToggleEnabled)
        viewModel.onAppLockChange(true)
        advanceUntilIdle()
        assertEquals(1, access.calls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.appLockToggleEnabled)
    }

    @Test
    fun withoutWalletsTheSwitchIsDisabled() = runTest {
        store.setState { it.copy(wallets = emptyList()) }
        val access = FakeAppAccessController()
        val viewModel = settings(access)
        assertFalse(viewModel.uiState.value.appLockToggleEnabled)
        viewModel.onAppLockChange(true)
        advanceUntilIdle()
        assertTrue(access.setEnabledCalls.isEmpty())
    }

    @Test
    fun withoutBiometricsALockCannotBeTurnedOnButAnEnabledOneCanBeTurnedOff() = runTest {
        // SettingsTab.swift:30: `!biometry.isAvailable && !access.isEnabled` disables; an enabled lock can always go off.
        val off = settings(FakeAppAccessController(AppAccessState(resolving = false, isEnabled = false, isLocked = false, biometricsAvailable = false)))
        assertFalse(off.uiState.value.appLockToggleEnabled)
        assertFalse(off.uiState.value.biometricsAvailable)

        val access = FakeAppAccessController(AppAccessState(resolving = false, isEnabled = true, isLocked = false, biometricsAvailable = false))
        val on = settings(access)
        assertTrue(on.uiState.value.appLockToggleEnabled)
        on.onAppLockChange(false)
        advanceUntilIdle()
        assertEquals(listOf(false), access.setEnabledCalls)
        assertFalse(on.uiState.value.appLockEnabled)
    }

    @Test
    fun theThemeChoiceIsWrittenToThePreferences() = runTest {
        val viewModel = settings()
        viewModel.onThemeClicked()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showsThemeDialog)
        viewModel.onAppearanceSelected(Appearance.Dark)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showsThemeDialog)
        assertEquals(Appearance.Dark, viewModel.uiState.value.appearance)
        assertEquals(listOf<FakeSignerPreferences.Call>(FakeSignerPreferences.Call.SetAppearance(Appearance.Dark)), preferences.calls)
    }

    @Test
    fun theLanguageRowFollowsThePerAppLocale() = runTest {
        val viewModel = settings()
        assertEquals(AppLanguage.System, viewModel.uiState.value.language)
        // Chosen elsewhere (Android 13+ "App languages"), seen when the page starts again.
        languages.language = AppLanguage.German
        viewModel.refreshLanguage()
        advanceUntilIdle()
        assertEquals(AppLanguage.German, viewModel.uiState.value.language)
    }

    @Test
    fun choosingALanguageAppliesItOnce() = runTest {
        val viewModel = LanguageViewModel(languages)
        assertEquals(AppLanguage.System, viewModel.uiState.value.selected)
        assertEquals(AppLanguage.entries, viewModel.uiState.value.options)
        viewModel.onSelect(AppLanguage.Arabic)
        viewModel.onSelect(AppLanguage.Arabic)
        assertEquals(listOf(AppLanguage.Arabic), languages.applied)
        assertEquals(AppLanguage.Arabic, viewModel.uiState.value.selected)
        viewModel.onSelect(AppLanguage.System)
        assertEquals(listOf(AppLanguage.Arabic, AppLanguage.System), languages.applied)
    }
}
