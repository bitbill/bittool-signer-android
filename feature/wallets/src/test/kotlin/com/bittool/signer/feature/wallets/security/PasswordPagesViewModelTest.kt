package com.bittool.signer.feature.wallets.security

import app.cash.turbine.test
import com.bittool.signer.core.data.testing.FakeSignerStore.Call
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.UiText
import com.bittool.signer.feature.wallets.MainDispatcherRule
import com.bittool.signer.feature.wallets.WalletFixtures
import com.bittool.signer.feature.wallets.observe
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Change Password and Repair Wallet (wallets-pairing.md §8.3, §8.5). Passwords are the public e2e test values. */
class PasswordPagesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = "Public-Test-Password1!"
    private val updated = "Updated-Test-Password2!"

    @Test
    fun theChangeButtonNeedsTheCurrentPasswordAValidNewOneAndAMatchingConfirmation() {
        assertTrue(ChangePasswordViewModel.isValid(fixture, updated, updated))
        assertFalse(ChangePasswordViewModel.isValid("", updated, updated), "current password empty")
        assertFalse(ChangePasswordViewModel.isValid(fixture, "short1!", "short1!"), "fewer than 8 characters")
        assertFalse(ChangePasswordViewModel.isValid(fixture, "alllowercase", "alllowercase"), "fewer than 3 categories")
        assertFalse(ChangePasswordViewModel.isValid(fixture, updated, "Updated-Test-Password3!"), "confirmation differs")
    }

    @Test
    fun changingThePasswordReEncryptsAndClosesThePage() = runTest(main.dispatcher) {
        val store = WalletFixtures.store()
        val vm = ChangePasswordViewModel(store, WalletFixtures.args())
        observe(vm.uiState)
        val current = SecretText.of(fixture)
        val new = SecretText.of(updated)
        val confirmation = SecretText.of(updated)
        vm.pageEvents.test {
            vm.submit(current, new, confirmation)
            advanceUntilIdle()
            assertEquals(SecurityPageEvent.Done, awaitItem())
        }
        assertEquals(listOf(Call.ChangePassword(WalletFixtures.readyId, fixture, updated)), store.calls<Call.ChangePassword>())
        assertTrue(current.isEmpty() && new.isEmpty() && confirmation.isEmpty(), "every secret is wiped")
    }

    @Test
    fun anIncorrectCurrentPasswordIsReported() = runTest(main.dispatcher) {
        val store = WalletFixtures.store().apply { onChangePassword = { _, _, _ -> throw AppIssue("The password is incorrect.") } }
        val vm = ChangePasswordViewModel(store, WalletFixtures.args())
        val state = observe(vm.uiState)
        vm.pageEvents.test {
            vm.submit(SecretText.of("Wrong-Password1!"), SecretText.of(updated), SecretText.of(updated))
            advanceUntilIdle()
            expectNoEvents()
        }
        assertEquals(UiText.Ios("The password is incorrect."), state.value.error)
        assertFalse(state.value.busy)
    }

    @Test
    fun anInvalidSubmissionNeverReachesTheVault() = runTest(main.dispatcher) {
        val store = WalletFixtures.store()
        val vm = ChangePasswordViewModel(store, WalletFixtures.args())
        observe(vm.uiState)
        val new = SecretText.of(updated)
        vm.submit(SecretText.of(fixture), new, SecretText.of("Mismatch-Password1!"))
        advanceUntilIdle()
        assertTrue(store.calls<Call.ChangePassword>().isEmpty())
        assertTrue(new.isEmpty(), "refused secrets are wiped too")
    }

    @Test
    fun aSecondSubmissionWhileBusyIsIgnored() = runTest(main.dispatcher) {
        val hold = CompletableDeferred<Unit>()
        val store = WalletFixtures.store().apply { onChangePassword = { _, _, _ -> hold.await() } }
        val vm = ChangePasswordViewModel(store, WalletFixtures.args())
        val state = observe(vm.uiState)
        vm.submit(SecretText.of(fixture), SecretText.of(updated), SecretText.of(updated))
        advanceUntilIdle()
        assertTrue(state.value.busy)
        vm.submit(SecretText.of(fixture), SecretText.of(updated), SecretText.of(updated))
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, store.calls<Call.ChangePassword>().size)
    }

    @Test
    fun theSecurityEpochIsPublishedSoTheFieldsCanClear() = runTest(main.dispatcher) {
        val store = WalletFixtures.store()
        val state = observe(ChangePasswordViewModel(store, WalletFixtures.args()).uiState)
        advanceUntilIdle()
        val before = state.value.securityEpoch
        store.suspendSession()
        advanceUntilIdle()
        assertEquals(before + 1, state.value.securityEpoch)
    }

    @Test
    fun repairRebuildsThePublicDataWithThePasswordOnce() = runTest(main.dispatcher) {
        val store = WalletFixtures.store(WalletFixtures.state(repair = true))
        val vm = RepairWalletViewModel(store, WalletFixtures.args(WalletFixtures.repairId))
        val state = observe(vm.uiState)
        assertFalse(state.value.identity!!.ready)
        val password = SecretText.of(fixture)
        vm.pageEvents.test {
            vm.submit(password)
            advanceUntilIdle()
            assertEquals(SecurityPageEvent.Done, awaitItem())
        }
        assertEquals(listOf(Call.CompleteSetup(WalletFixtures.repairId, fixture)), store.calls<Call.CompleteSetup>())
        assertTrue(password.isEmpty())
    }

    @Test
    fun repairReportsTheVaultError() = runTest(main.dispatcher) {
        val store = WalletFixtures.store(WalletFixtures.state(repair = true)).apply {
            onCompleteSetup = { _, _ -> throw AppIssue("Too many unsuccessful attempts. Try again in %lld seconds.", 32L) }
        }
        val vm = RepairWalletViewModel(store, WalletFixtures.args(WalletFixtures.repairId))
        val state = observe(vm.uiState)
        vm.submit(SecretText.of("Wrong-Password1!"))
        advanceUntilIdle()
        assertEquals(UiText.Ios("Too many unsuccessful attempts. Try again in %lld seconds.", listOf(32L)), state.value.error)
        vm.submit(SecretText.empty())
        advanceUntilIdle()
        assertEquals(1, store.calls<Call.CompleteSetup>().size, "an empty field never reaches the vault")
        vm.cancel()
        assertNull(state.value.error)
    }
}
