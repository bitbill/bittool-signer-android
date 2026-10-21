package com.bittool.signer.lock

import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.security.testing.FakeAppAccessController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * QA L2 (production-02, Android-only): with App Lock on, removing the screen lock left the person locked out (no
 * fingerprint any more, every wallet password "device key unavailable", nothing said what to do). The lock screen now
 * asks App Lock whether any unlock path is left whenever no biometric can run, and when none is, explains why and
 * offers "Continue Without App Lock". A wrong password never leads there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoUnlockPathLockTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val wallet = VaultMetadata(WalletId.random(), "Public fixture 1", 1L)
    private val store = FakeSignerStore(SignerState(isLoading = false, walletLoadSucceeded = true, wallets = listOf(wallet)))
    private val access = FakeAppAccessController()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        access.mutableState.update { it.copy(biometricsAvailable = false) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aLockWithNoWayInExplainsItAndContinuesWithoutAppLock() = runTest(dispatcher) {
        access.noUnlockPath = true
        val viewModel = AppLockViewModel(access, store)
        access.lock()
        viewModel.onResumedChanged(true)
        assertTrue(viewModel.uiState.value.noUnlockPath, "said at once: no password needs to be tried first")
        assertEquals(0, access.biometricUnlockCalls, "no prompt that cannot run")

        viewModel.continueWithoutAppLock()
        assertEquals(1, access.continueCalls)
        assertFalse(access.state.value.isLocked)
        assertFalse(access.state.value.isEnabled)
        assertFalse(viewModel.uiState.value.noUnlockPath, "the lock is gone")
    }

    @Test
    fun anOrdinaryLockKeepsItsWaysInAndAWrongPasswordProvesNothing() = runTest(dispatcher) {
        val viewModel = AppLockViewModel(access, store)
        access.lock()
        viewModel.onResumedChanged(true)
        assertFalse(viewModel.uiState.value.noUnlockPath)
        assertTrue(viewModel.uiState.value.usePassword, "no biometric: the password form, as before")
        val checks = access.noUnlockPathChecks

        access.passwordFailure = VaultError.AuthenticationFailed()
        viewModel.unlock(SecretText.of("Wrong-Password1"))
        assertEquals(checks, access.noUnlockPathChecks, "a wrong password is not a reason to look")
        assertFalse(viewModel.uiState.value.noUnlockPath)
        viewModel.continueWithoutAppLock()
        assertEquals(0, access.continueCalls, "there is nothing to continue from")
    }

    @Test
    fun aPasswordWhoseDeviceKeyIsUnavailableHasThePathsLookedAtAgain() = runTest(dispatcher) {
        val viewModel = AppLockViewModel(access, store)
        access.lock()
        viewModel.onResumedChanged(true)
        assertFalse(viewModel.uiState.value.noUnlockPath)

        access.noUnlockPath = true // e.g. the screen lock was set again meanwhile and the keys are provably gone
        access.passwordFailure = VaultError.DeviceKeyUnavailable()
        viewModel.unlock(SecretText.of("Public-Test-Password1!"))
        assertTrue(viewModel.uiState.value.noUnlockPath)
    }

    @Test
    fun aBiometricThatBecomesAvailableIsTheWayInAgain() = runTest(dispatcher) {
        access.noUnlockPath = true
        val viewModel = AppLockViewModel(access, store)
        access.lock()
        viewModel.onResumedChanged(true)
        assertTrue(viewModel.uiState.value.noUnlockPath)
        // A fingerprint enrolled again in Android Settings.
        access.mutableState.update { it.copy(biometricsAvailable = true) }
        assertFalse(viewModel.uiState.value.noUnlockPath)
    }

    @Test
    fun aContinueTheProofNoLongerHoldsForGoesBackToTheOrdinaryWays() = runTest(dispatcher) {
        access.noUnlockPath = true
        val viewModel = AppLockViewModel(access, store)
        access.lock()
        viewModel.onResumedChanged(true)
        access.noUnlockPath = false
        viewModel.continueWithoutAppLock()
        assertTrue(access.state.value.isLocked, "App Lock proves it again and stays")
        assertFalse(viewModel.uiState.value.noUnlockPath)
        assertFalse(viewModel.uiState.value.continuing)
    }

    @Test
    fun aFailedContinueShowsItsMessage() = runTest(dispatcher) {
        access.noUnlockPath = true
        val viewModel = AppLockViewModel(access, store)
        access.lock()
        viewModel.onResumedChanged(true)
        access.continueFailure = SignerException(UiText.Resource(R.string.security_app_lock_save_failed))
        viewModel.continueWithoutAppLock()
        assertEquals(UiText.Resource(R.string.security_app_lock_save_failed), viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.noUnlockPath, "the explanation stays, so the person can try again")
        assertTrue(access.state.value.isLocked)
    }
}
