package com.bittool.signer.lock

import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.security.AppAccessController
import com.bittool.signer.core.security.AppAccessState
import com.bittool.signer.core.security.BiometricCancellationException
import com.bittool.signer.core.security.testing.FakeAppAccessController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The lock screen (iOS `AppLockView`, Features/Lock/AppLockView.swift:6-128; app-shell.md §11.9; the UI-test flow
 * UIT:774-803, gaps.md G1.17): one automatic prompt per lock once resumed, the password fallback, a wrong password
 * that has to be typed again, late results dropped after a new security epoch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val first = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
    private val second = VaultMetadata(WalletId.random(), "Public fixture 2", 2L)
    private val store = FakeSignerStore(SignerState(isLoading = false, walletLoadSucceeded = true, wallets = listOf(first)))
    private val access = FakeAppAccessController()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.events(viewModel: AppLockViewModel): List<AppLockEvent> {
        val events = mutableListOf<AppLockEvent>()
        backgroundScope.launch { viewModel.fieldEvents.toList(events) }
        return events
    }

    @Test
    fun aLockPromptsOnceWhenTheActivityIsResumed() = runTest(dispatcher) {
        val viewModel = AppLockViewModel(access, store)
        access.biometricFailure = AppIssue("Biometric authentication was cancelled. Use a wallet password to continue.")
        access.lock()
        assertEquals(0, access.biometricUnlockCalls, "never while not resumed")
        viewModel.onResumedChanged(true)
        assertEquals(1, access.biometricUnlockCalls)
        assertEquals(
            UiText.Ios("Biometric authentication was cancelled. Use a wallet password to continue."),
            viewModel.uiState.value.error,
        )
        // A short trip to the background while still locked does not prompt again (iOS: one prompt per lock window).
        viewModel.onResumedChanged(false)
        viewModel.onResumedChanged(true)
        assertEquals(1, access.biometricUnlockCalls)
        // The next lock is a new engagement: a clean screen and one new prompt.
        access.biometricFailure = null
        viewModel.authenticateBiometrics()
        assertFalse(access.state.value.isLocked)
        assertNull(viewModel.uiState.value.error)
        access.lock()
        assertEquals(3, access.biometricUnlockCalls)
    }

    @Test
    fun noPromptWhileAppLockIsStillResolving() = runTest(dispatcher) {
        access.mutableState.value = AppAccessState(resolving = true)
        val viewModel = AppLockViewModel(access, store)
        viewModel.onResumedChanged(true)
        assertEquals(0, access.biometricUnlockCalls)
        access.mutableState.value = AppAccessState(resolving = false, isEnabled = true, isLocked = true, biometricsAvailable = true)
        assertEquals(1, access.biometricUnlockCalls)
    }

    @Test
    fun withoutBiometricsThePasswordFormShowsWithTheOnlyWalletChosen() = runTest(dispatcher) {
        access.mutableState.value = AppAccessState(resolving = false, isEnabled = true, isLocked = true, biometricsAvailable = false)
        val viewModel = AppLockViewModel(access, store)
        viewModel.onResumedChanged(true)
        assertEquals(0, access.biometricUnlockCalls)
        assertTrue(viewModel.uiState.value.usePassword)
        assertEquals(first.id, viewModel.uiState.value.selectedWallet)
        assertEquals("Public fixture 1", viewModel.uiState.value.selectedName)
    }

    @Test
    fun choosingAWalletPasswordInThePromptOpensTheForm() = runTest(dispatcher) {
        val viewModel = AppLockViewModel(access, store)
        access.lock()
        access.biometricFailure = BiometricCancellationException(BiometricCancellationException.Reason.UserCanceled)
        viewModel.onResumedChanged(true)
        assertFalse(viewModel.uiState.value.usePassword, "a dismissal is silent")
        assertNull(viewModel.uiState.value.error)
        access.biometricFailure = BiometricCancellationException(BiometricCancellationException.Reason.NegativeButton)
        viewModel.authenticateBiometrics()
        assertTrue(viewModel.uiState.value.usePassword)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun withSeveralWalletsTheChoiceIsThePersonsAndOnlyNamesAreShown() = runTest(dispatcher) {
        store.setState { it.copy(wallets = listOf(first, second)) }
        val viewModel = AppLockViewModel(access, store)
        access.lock()
        viewModel.usePassword()
        assertNull(viewModel.uiState.value.selectedWallet)
        assertEquals(listOf("Public fixture 1", "Public fixture 2"), viewModel.uiState.value.wallets.map { it.name })
        viewModel.selectWallet(second.id)
        assertEquals("Public fixture 2", viewModel.uiState.value.selectedName)
    }

    @Test
    fun aWrongPasswordShowsTheVaultErrorAndRefocusesTheField() = runTest(dispatcher) {
        val viewModel = AppLockViewModel(access, store)
        val events = events(viewModel)
        access.lock()
        viewModel.usePassword()
        access.passwordFailure = VaultError.AuthenticationFailed()
        val wrong = SecretText.of("Wrong-Password1!")
        viewModel.unlock(wrong)
        assertTrue(wrong.isEmpty(), "the typed password is wiped")
        assertEquals(UiText.Ios("The password is incorrect."), viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.busy)
        assertEquals(AppLockEvent.FocusPassword, events.last())
        assertTrue(access.state.value.isLocked)

        access.passwordFailure = null
        viewModel.unlock(SecretText.of("Public-Test-Password1!"))
        assertEquals(listOf(first.id, first.id), access.passwordUnlocks)
        assertFalse(access.state.value.isLocked)
        // Unlocked: the screen's state is reset for the next lock.
        assertFalse(viewModel.uiState.value.usePassword)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun unlockNeedsAWalletAndAPassword() = runTest(dispatcher) {
        store.setState { it.copy(wallets = listOf(first, second)) }
        val viewModel = AppLockViewModel(access, store)
        access.lock()
        viewModel.usePassword()
        viewModel.unlock(SecretText.of("Public-Test-Password1!"))
        viewModel.selectWallet(first.id)
        viewModel.unlock(SecretText.empty())
        assertEquals(emptyList(), access.passwordUnlocks)
    }

    @Test
    fun useBiometricsInsteadClearsThePassword() = runTest(dispatcher) {
        val viewModel = AppLockViewModel(access, store)
        val events = events(viewModel)
        access.lock()
        viewModel.usePassword()
        viewModel.useBiometrics()
        assertFalse(viewModel.uiState.value.usePassword)
        assertEquals(AppLockEvent.ClearPassword, events.last())
    }

    @Test
    fun aNewSecurityEpochDropsTheAttemptInFlightAndClearsTheField() = runTest(dispatcher) {
        val blocking = BlockingAccess()
        val viewModel = AppLockViewModel(blocking, store)
        val events = events(viewModel)
        blocking.state.value.let { blocking.publish(it.copy(resolving = false, isEnabled = true, isLocked = true, biometricsAvailable = false)) }
        viewModel.selectWallet(first.id)
        viewModel.unlock(SecretText.of("Wrong-Password1!"))
        assertTrue(viewModel.uiState.value.busy)

        store.suspendSession()
        assertFalse(viewModel.uiState.value.busy)
        assertEquals(AppLockEvent.ClearPassword, events.last())

        blocking.pending.completeExceptionally(VaultError.AuthenticationFailed())
        assertNull(viewModel.uiState.value.error, "a late failure of an old attempt is not shown")
    }

    @Test
    fun appLocksOwnErrorShowsWhenTheScreenHasNone() = runTest(dispatcher) {
        val viewModel = AppLockViewModel(access, store)
        access.lock()
        access.mutableState.value = access.state.value.copy(error = UiText.Ios("App authentication is unavailable right now."))
        assertEquals(UiText.Ios("App authentication is unavailable right now."), viewModel.uiState.value.error)
    }

    /** An App Lock whose password check waits for the test (iOS `ControlledSigningEngine`-style gate). */
    private class BlockingAccess : AppAccessController {
        private val mutable = MutableStateFlow(AppAccessState(resolving = false, isEnabled = false, isLocked = false))
        override val state: StateFlow<AppAccessState> = mutable
        val pending = CompletableDeferred<Unit>()

        fun publish(state: AppAccessState) {
            mutable.value = state
        }

        override fun updateWalletCount(count: Int) = Unit
        override suspend fun setEnabled(enabled: Boolean) = Unit
        override suspend fun authenticateBiometrics() = Unit
        override suspend fun authenticate(walletId: WalletId, password: SecretText) = pending.await()
        override suspend fun confirmSensitiveAction(reason: UiText) = Unit
        override fun didEnterBackground() = Unit
        override fun willEnterForeground() = Unit
        override fun clearError() = Unit
    }
}
