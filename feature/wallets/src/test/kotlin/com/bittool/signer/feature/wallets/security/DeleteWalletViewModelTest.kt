package com.bittool.signer.feature.wallets.security

import app.cash.turbine.test
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.data.testing.FakeSignerStore.Call
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.security.AppAccessState
import com.bittool.signer.core.security.BiometricException
import com.bittool.signer.core.security.testing.FakeAppAccessController
import com.bittool.signer.feature.wallets.MainDispatcherRule
import com.bittool.signer.feature.wallets.WalletFixtures
import com.bittool.signer.feature.wallets.observe
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Delete Wallet (wallets-pairing.md §8.4, iOS `DeleteWalletSheet`): the order biometrics → password, the always-enabled
 * "Delete" with its empty-field message, and the vault deciding on the password.
 */
class DeleteWalletViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val appLockOn = AppAccessState(resolving = false, isEnabled = true, isLocked = false, biometricsAvailable = true)

    private fun viewModel(store: FakeSignerStore, access: FakeAppAccessController = FakeAppAccessController()) =
        DeleteWalletViewModel(store, access, WalletFixtures.args())

    @Test
    fun nothingHappensUntilTheDeletionIsConfirmed() = runTest(main.dispatcher) {
        val access = FakeAppAccessController(appLockOn)
        val vm = viewModel(WalletFixtures.store(), access)
        val state = observe(vm.uiState)
        vm.confirm()
        advanceUntilIdle()
        assertTrue(access.sensitiveActionReasons.isEmpty())
        assertFalse(state.value.askingPassword)
        assertFalse(state.value.canDelete)
    }

    @Test
    fun withAppLockOnBiometricsComeFirstThenThePassword() = runTest(main.dispatcher) {
        val access = FakeAppAccessController(appLockOn)
        val store = WalletFixtures.store()
        val vm = viewModel(store, access)
        val state = observe(vm.uiState)
        assertTrue(state.value.asksForBiometrics, "the footer names the biometric step")
        vm.setConfirmed(true)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf<UiText>(UiText.Ios("Confirm before deleting this wallet.")), access.sensitiveActionReasons)
        assertTrue(state.value.askingPassword, "the password dialog follows the biometric check")
        assertTrue(store.calls<Call.DeleteWallet>().isEmpty(), "the password, not biometrics, authorizes the deletion")
        vm.submit(SecretText.of("Public-Test-Password1!"))
        advanceUntilIdle()
        assertEquals(listOf(Call.DeleteWallet(WalletFixtures.readyId, "Public-Test-Password1!")), store.calls<Call.DeleteWallet>())
    }

    @Test
    fun dismissingTheBiometricPromptStopsTheDeletion() = runTest(main.dispatcher) {
        val access = FakeAppAccessController(appLockOn).apply { sensitiveActionFailure = CancellationException("dismissed") }
        val vm = viewModel(WalletFixtures.store(), access)
        val state = observe(vm.uiState)
        vm.setConfirmed(true)
        vm.confirm()
        advanceUntilIdle()
        assertFalse(state.value.askingPassword)
        assertFalse(state.value.confirming)
        assertNull(state.value.error)
    }

    @Test
    fun biometricsThatCannotRunLeaveTheDecisionToThePassword() = runTest(main.dispatcher) {
        val access = FakeAppAccessController(appLockOn).apply {
            sensitiveActionFailure = BiometricException(BiometricException.Kind.Lockout, UiText.Ios("Authentication failed. Try again or use a wallet password."))
        }
        val vm = viewModel(WalletFixtures.store(), access)
        val state = observe(vm.uiState)
        vm.setConfirmed(true)
        vm.confirm()
        advanceUntilIdle()
        assertTrue(state.value.askingPassword)
        assertNull(state.value.error)
    }

    @Test
    fun withAppLockOffTheFooterOnlyNamesThePassword() = runTest(main.dispatcher) {
        val state = observe(viewModel(WalletFixtures.store()).uiState)
        assertFalse(state.value.asksForBiometrics)
    }

    @Test
    fun anEmptyPasswordIsReportedWithoutTouchingTheVault() = runTest(main.dispatcher) {
        val store = WalletFixtures.store()
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        vm.setConfirmed(true)
        vm.confirm()
        advanceUntilIdle()
        vm.submit(SecretText.empty())
        advanceUntilIdle()
        assertEquals(UiText.Ios("Enter this wallet’s password."), state.value.error)
        assertFalse(state.value.askingPassword)
        assertTrue(store.calls<Call.DeleteWallet>().isEmpty())
    }

    @Test
    fun aWrongPasswordIsReportedAndThePageStays() = runTest(main.dispatcher) {
        val store = WalletFixtures.store().apply { onDeleteWallet = { _, _ -> throw AppIssue("The password is incorrect.") } }
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        vm.pageEvents.test {
            vm.setConfirmed(true)
            vm.confirm()
            advanceUntilIdle()
            vm.submit(SecretText.of("Wrong-Password1!"))
            advanceUntilIdle()
            expectNoEvents()
        }
        assertEquals(UiText.Ios("The password is incorrect."), state.value.error)
        assertTrue(state.value.confirmed, "the confirmation stays so the person can try again")
        assertFalse(state.value.busy)
    }

    @Test
    fun aSuccessfulDeletionClosesThePageAndWipesThePassword() = runTest(main.dispatcher) {
        val store = WalletFixtures.store()
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        val password = SecretText.of("Public-Test-Password1!")
        vm.pageEvents.test {
            vm.setConfirmed(true)
            vm.confirm()
            advanceUntilIdle()
            vm.submit(password)
            advanceUntilIdle()
            assertEquals(SecurityPageEvent.Done, awaitItem())
        }
        assertTrue(password.isEmpty(), "the submitted password is wiped")
        assertTrue(state.value.gone, "the wallet left the list")
    }

    @Test
    fun aSuspensionClosesThePasswordDialogUnlessTheDeletionRuns() = runTest(main.dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val store = WalletFixtures.store()
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        vm.setConfirmed(true)
        vm.confirm()
        advanceUntilIdle()
        assertTrue(state.value.askingPassword)
        store.suspendSession()
        advanceUntilIdle()
        assertFalse(state.value.askingPassword, "background drops the open password prompt")

        store.onDeleteWallet = { _, _ -> hold.await() }
        vm.confirm()
        advanceUntilIdle()
        vm.submit(SecretText.of("Public-Test-Password1!"))
        advanceUntilIdle()
        assertTrue(state.value.busy)
        store.suspendSession()
        advanceUntilIdle()
        assertTrue(state.value.busy, "a running deletion decides by itself")
        hold.complete(Unit)
        advanceUntilIdle()
        assertFalse(state.value.busy)
    }

    @Test
    fun aLateFailureAfterASuspensionIsSilent() = runTest(main.dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val store = WalletFixtures.store().apply {
            onDeleteWallet = { _, _ ->
                hold.await()
                throw AppIssue("The password is incorrect.")
            }
        }
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        vm.setConfirmed(true)
        vm.confirm()
        advanceUntilIdle()
        vm.submit(SecretText.of("Wrong-Password1!"))
        advanceUntilIdle()
        store.suspendSession()
        hold.complete(Unit)
        advanceUntilIdle()
        assertNull(state.value.error, "a result from before the suspension is dropped (iOS WalletOperation epoch check)")
    }

    @Test
    fun cancelForgetsTheConfirmationAndARunningOperation() = runTest(main.dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val store = WalletFixtures.store().apply {
            onDeleteWallet = { _, _ ->
                hold.await()
                throw AppIssue("The password is incorrect.")
            }
        }
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        vm.setConfirmed(true)
        vm.confirm()
        advanceUntilIdle()
        vm.submit(SecretText.of("Wrong-Password1!"))
        advanceUntilIdle()
        vm.cancel()
        hold.complete(Unit)
        advanceUntilIdle()
        assertFalse(state.value.busy)
        assertFalse(state.value.confirmed)
        assertNull(state.value.error)
    }

    // ---- A provably lost device key (QA backlog B9, Android-only) ------------------------------------------------

    private fun lostKeyStore() = WalletFixtures.store().apply { lostDeviceKeys = setOf(WalletFixtures.readyId) }

    @Test
    fun aWalletWithItsDeviceKeyKeepsThePasswordPath() = runTest(main.dispatcher) {
        val store = WalletFixtures.store()
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        assertEquals(listOf(Call.IsDeviceKeyLost(WalletFixtures.readyId)), store.calls<Call.IsDeviceKeyLost>())
        assertFalse(state.value.deviceKeyLost)
        vm.setConfirmed(true)
        vm.confirm()
        advanceUntilIdle()
        assertTrue(state.value.askingPassword)
        assertFalse(state.value.askingRemoval)
        vm.remove()
        advanceUntilIdle()
        assertTrue(store.calls<Call.RemoveWalletWithLostDeviceKey>().isEmpty(), "no removal without the password")
    }

    @Test
    fun aWalletWhoseDeviceKeyIsGoneIsRemovedWithoutAPasswordAfterTheConfirmations() = runTest(main.dispatcher) {
        val store = lostKeyStore()
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        vm.pageEvents.test {
            advanceUntilIdle()
            assertTrue(state.value.deviceKeyLost, "the page explains the lost key and offers the removal")
            vm.confirm()
            advanceUntilIdle()
            assertFalse(state.value.askingRemoval, "the confirmation switch comes first")
            vm.setConfirmed(true)
            vm.confirm()
            advanceUntilIdle()
            assertTrue(state.value.askingRemoval)
            assertFalse(state.value.askingPassword, "no password can unlock this wallet")
            assertTrue(store.calls<Call.RemoveWalletWithLostDeviceKey>().isEmpty(), "the dialog is the last confirmation")
            vm.remove()
            advanceUntilIdle()
            assertEquals(SecurityPageEvent.Done, awaitItem())
        }
        assertEquals(listOf(Call.RemoveWalletWithLostDeviceKey(WalletFixtures.readyId)), store.calls<Call.RemoveWalletWithLostDeviceKey>())
        assertTrue(store.calls<Call.DeleteWallet>().isEmpty())
        assertTrue(state.value.gone)
    }

    @Test
    fun withAppLockOnBiometricsComeFirstForARemovalToo() = runTest(main.dispatcher) {
        val access = FakeAppAccessController(appLockOn)
        val store = lostKeyStore()
        val vm = viewModel(store, access)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        vm.setConfirmed(true)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(listOf<UiText>(UiText.Ios("Confirm before deleting this wallet.")), access.sensitiveActionReasons)
        assertTrue(state.value.askingRemoval)

        access.sensitiveActionFailure = CancellationException("dismissed")
        vm.cancelRemoval()
        vm.confirm()
        advanceUntilIdle()
        assertFalse(state.value.askingRemoval, "dismissing the prompt stops the removal")
        assertTrue(store.calls<Call.RemoveWalletWithLostDeviceKey>().isEmpty())
    }

    @Test
    fun aRemovalTheVaultRefusesShowsWhyAndFallsBackToThePassword() = runTest(main.dispatcher) {
        val store = lostKeyStore().apply {
            onRemoveWalletWithLostDeviceKey = {
                lostDeviceKeys = emptySet()
                throw VaultError.DeviceKeyLossUnconfirmed()
            }
        }
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        vm.pageEvents.test {
            advanceUntilIdle()
            vm.setConfirmed(true)
            vm.confirm()
            advanceUntilIdle()
            vm.remove()
            advanceUntilIdle()
            expectNoEvents()
        }
        assertEquals(VaultError.DeviceKeyLossUnconfirmed().text, state.value.error)
        assertFalse(state.value.deviceKeyLost, "the key may still exist: the password path is back")
        assertFalse(state.value.confirmed, "the password path's own statement is confirmed separately")
        assertFalse(state.value.busy)
        assertTrue(store.state.value.wallets.any { it.id == WalletFixtures.readyId })
    }

    @Test
    fun aDeletionThatFindsTheDeviceKeyGoneOffersTheRemoval() = runTest(main.dispatcher) {
        val store = WalletFixtures.store().apply {
            onDeleteWallet = { id, _ ->
                lostDeviceKeys = setOf(id)
                throw VaultError.DeviceKeyUnavailable()
            }
        }
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        assertFalse(state.value.deviceKeyLost)
        vm.setConfirmed(true)
        vm.confirm()
        advanceUntilIdle()
        vm.submit(SecretText.of("Public-Test-Password1!"))
        advanceUntilIdle()
        assertEquals(UiText.Ios("This wallet’s device key is unavailable. Restore your recovery phrase on this device."), state.value.error)
        assertTrue(state.value.deviceKeyLost)
        assertFalse(state.value.confirmed, "the switch now states something else: it must be turned on again")
        vm.setConfirmed(true)
        vm.confirm()
        advanceUntilIdle()
        assertTrue(state.value.askingRemoval)
    }

    @Test
    fun aSuspensionClosesTheRemovalDialogAndLooksAtTheKeyAgain() = runTest(main.dispatcher) {
        val store = lostKeyStore()
        val vm = viewModel(store)
        val state = observe(vm.uiState)
        advanceUntilIdle()
        vm.setConfirmed(true)
        vm.confirm()
        advanceUntilIdle()
        assertTrue(state.value.askingRemoval)
        store.suspendSession()
        advanceUntilIdle()
        assertFalse(state.value.askingRemoval, "background drops the open removal dialog")
        assertEquals(2, store.calls<Call.IsDeviceKeyLost>().size)
        vm.remove()
        advanceUntilIdle()
        assertTrue(store.calls<Call.RemoveWalletWithLostDeviceKey>().isEmpty(), "a closed dialog confirms nothing")
        vm.confirm()
        advanceUntilIdle()
        vm.remove()
        advanceUntilIdle()
        assertEquals(1, store.calls<Call.RemoveWalletWithLostDeviceKey>().size, "the page itself stays usable")
    }
}
