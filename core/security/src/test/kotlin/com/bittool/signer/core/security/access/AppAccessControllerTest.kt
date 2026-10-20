package com.bittool.signer.core.security.access

import com.bittool.signer.core.common.testing.FakeMonotonicClock
import com.bittool.signer.core.common.testing.FakeWallClock
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.security.AppAccessController
import com.bittool.signer.core.security.BiometricCancellationException
import com.bittool.signer.core.security.BiometricException
import com.bittool.signer.core.security.testing.FakeBiometricAuthenticator
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Port of BitToolSignerTests/AppAccessTests.swift (all 8 cases) plus the Android-only rules of the port: the
 * asynchronous preference read fails closed, writes can fail, and authentication is refused while one runs. The fake
 * clock starts at 100 s like the iOS `TestUptime`; times are milliseconds.
 */
class AppAccessControllerTest {

    private class Fixture(
        val access: DefaultAppAccessController,
        val biometric: FakeBiometricAuthenticator,
        val clock: FakeMonotonicClock,
        val preferences: TestPreferences,
        val vault: TestVault,
        val newController: () -> DefaultAppAccessController,
    ) {
        val state get() = access.state.value
    }

    private fun TestScope.makeAccess(
        enabled: Boolean = false,
        preferences: TestPreferences = TestPreferences(appLockEnabled = enabled),
        vault: TestVault = TestVault(),
        biometric: FakeBiometricAuthenticator = FakeBiometricAuthenticator(),
    ): Fixture {
        val clock = FakeMonotonicClock(nowMillis = 100_000L)
        val main = UnconfinedTestDispatcher(testScheduler)
        val create = { DefaultAppAccessController(preferences, vault, biometric, clock, backgroundScope, main) }
        val access = create()
        runCurrent()
        return Fixture(access, biometric, clock, preferences, vault, create)
    }

    // AppAccessTests.swift:7-27
    @Test
    fun appLockDefaultsOffAndEnablingRequiresSuccessfulBiometrics() = runTest {
        val fixture = makeAccess()
        assertFalse(fixture.state.isEnabled)
        assertFalse(fixture.state.isLocked)
        assertEquals(0, fixture.biometric.calls)
        fixture.access.updateWalletCount(1)
        fixture.biometric.shouldFail = true
        assertFailsWith<SignerException> { fixture.access.setEnabled(true) }
        assertFalse(fixture.state.isEnabled)
        assertFalse(fixture.preferences.stored.appLockEnabled)
        fixture.biometric.shouldFail = false
        fixture.access.setEnabled(true)
        assertTrue(fixture.state.isEnabled)
        assertFalse(fixture.state.isLocked)
        assertTrue(fixture.preferences.stored.appLockEnabled)

        val cold = fixture.newController()
        runCurrent()
        assertTrue(cold.state.value.isLocked)
        val refused = assertFailsWith<AppIssue> { cold.setEnabled(false) }
        assertEquals(UiText.Ios("Authenticate app access before changing App Lock."), refused.text)
        assertTrue(cold.state.value.isEnabled)
    }

    // AppAccessTests.swift:29-49
    @Test
    fun backgroundGraceUsesTheExactMonotonic120SecondBoundary() = runTest {
        val fixture = makeAccess(enabled = true)
        fixture.access.updateWalletCount(1)
        fixture.access.authenticateBiometrics()
        fixture.clock.nowMillis = 100_000
        fixture.access.didEnterBackground()
        fixture.clock.nowMillis = 219_000
        fixture.access.willEnterForeground()
        assertFalse(fixture.state.isLocked)
        fixture.access.didEnterBackground()
        fixture.clock.nowMillis = 339_000
        fixture.access.willEnterForeground()
        assertTrue(fixture.state.isLocked)
        fixture.access.authenticateBiometrics()
        fixture.clock.nowMillis = 400_000
        fixture.access.didEnterBackground()
        fixture.clock.nowMillis = 399_000
        fixture.access.willEnterForeground()
        assertTrue(fixture.state.isLocked, "A clock that went backwards fails closed")
    }

    @Test
    fun theGraceEndsAtExactly120000Milliseconds() = runTest {
        val fixture = makeAccess(enabled = true)
        fixture.access.updateWalletCount(1)
        fixture.access.authenticateBiometrics()
        fixture.access.didEnterBackground()
        fixture.clock.advanceBy(AppAccessController.BACKGROUND_GRACE_MILLIS - 1)
        fixture.access.willEnterForeground()
        assertFalse(fixture.state.isLocked)
        fixture.access.didEnterBackground()
        fixture.clock.advanceBy(AppAccessController.BACKGROUND_GRACE_MILLIS)
        fixture.access.willEnterForeground()
        assertTrue(fixture.state.isLocked)
    }

    // AppAccessTests.swift:51-68
    @Test
    fun alreadyLockedAppStaysLockedAcrossShortBackgroundAndRepeatedForeground() = runTest {
        val fixture = makeAccess(enabled = true)
        fixture.access.updateWalletCount(1)
        fixture.access.didEnterBackground()
        fixture.clock.advanceBy(1_000)
        fixture.access.willEnterForeground()
        fixture.access.willEnterForeground()
        assertTrue(fixture.state.isLocked)
        assertEquals(0, fixture.biometric.calls)
        fixture.access.authenticateBiometrics()
        assertFalse(fixture.state.isLocked)
        // An inactive phase (a system dialog, the biometric prompt itself) never calls didEnterBackground; repeated
        // foreground notifications alone cannot re-lock or start authentication.
        fixture.access.willEnterForeground()
        assertFalse(fixture.state.isLocked)
        assertEquals(1, fixture.biometric.calls)
    }

    // AppAccessTests.swift:70-83 (the iOS double ends a cancelled prompt with CancellationError)
    @Test
    fun backgroundRejectsAnInFlightBiometricCompletion() = runTest {
        val fixture = makeAccess(enabled = true)
        fixture.access.updateWalletCount(1)
        fixture.biometric.blockNext = true
        val authentication = async { fixture.access.authenticateBiometrics() }
        fixture.biometric.awaitStarted()
        fixture.access.didEnterBackground()
        fixture.biometric.complete()
        assertFailsWith<CancellationException>("Backgrounded biometrics must not publish app access.") { authentication.await() }
        assertTrue(fixture.state.isLocked)
        assertFalse(fixture.state.isAuthenticating)
        assertEquals(1, fixture.biometric.cancelCalls)
    }

    @Test
    fun aBiometricSuccessThatArrivesAfterTheBackgroundIsDiscarded() = runTest {
        val fixture = makeAccess(enabled = true)
        fixture.biometric.cancelEndsBlockedCall = false
        fixture.access.updateWalletCount(1)
        fixture.biometric.blockNext = true
        val authentication = async { fixture.access.authenticateBiometrics() }
        fixture.biometric.awaitStarted()
        fixture.access.didEnterBackground()
        fixture.biometric.complete()
        assertFailsWith<CancellationException> { authentication.await() }
        assertTrue(fixture.state.isLocked)
        fixture.clock.advanceBy(1_000)
        fixture.access.willEnterForeground()
        assertTrue(fixture.state.isLocked)
        assertNull(fixture.state.error, "A discarded result is silent")
    }

    // AppAccessTests.swift:85-107
    @Test
    fun anyWalletPasswordCanOpenOnlyTheAppAccessState() = runTest {
        val vault = TestVault()
        val first = WalletId.random()
        val second = WalletId.random()
        vault.add(first, "First-Password1")
        vault.add(second, "Second-Password2")
        val fixture = makeAccess(enabled = true, vault = vault)
        fixture.access.updateWalletCount(2)
        assertFailsWith<VaultError.AuthenticationFailed> {
            fixture.access.authenticate(second, password("First-Password1"))
        }
        assertTrue(fixture.state.isLocked)
        fixture.access.authenticate(second, password("Second-Password2"))
        assertFalse(fixture.state.isLocked)
        assertEquals(0, fixture.biometric.calls)
        // Authentication returns no secret and changes no vault. A later sensitive operation must independently
        // supply the correct password.
        assertFailsWith<VaultError.AuthenticationFailed> { vault.verifyPassword(second, password("")) }
        fixture.access.setEnabled(false)
        assertFalse(fixture.state.isEnabled)
    }

    // AppAccessTests.swift:109-119
    @Test
    fun deletingTheLastWalletClearsThePreferenceAndCannotDeadlockWelcome() = runTest {
        val fixture = makeAccess(enabled = true)
        assertTrue(fixture.state.isLocked)
        fixture.access.updateWalletCount(0)
        runCurrent()
        assertFalse(fixture.state.isLocked)
        assertFalse(fixture.state.isEnabled)
        assertFalse(fixture.preferences.stored.appLockEnabled)
        val refused = assertFailsWith<AppIssue> { fixture.access.setEnabled(true) }
        assertEquals(UiText.Ios("Create a wallet before enabling App Lock."), refused.text)
        assertEquals(0, fixture.biometric.calls)
    }

    // AppAccessTests.swift:121-139
    @Test
    fun aSensitiveActionAsksForBiometricsOnlyWhileAppLockIsOn() = runTest {
        val off = makeAccess()
        assertFalse(off.state.isEnabled)
        off.access.confirmSensitiveAction(UiText.Ios("Confirm before deleting this wallet."))
        assertEquals(0, off.biometric.calls, "App Lock is off, so nothing is asked")

        val on = makeAccess(enabled = true)
        on.access.updateWalletCount(1)
        on.access.authenticateBiometrics()
        val unlocking = on.biometric.calls
        on.access.confirmSensitiveAction(UiText.Ios("Confirm before deleting this wallet."))
        assertEquals(unlocking + 1, on.biometric.calls, "App Lock is on, so the check runs")
    }

    // AppAccessTests.swift:141-151
    @Test
    fun turningAppLockOffStopsTheDeleteCheckWithIt() = runTest {
        val fixture = makeAccess(enabled = true)
        fixture.access.updateWalletCount(1)
        fixture.access.authenticateBiometrics()
        fixture.access.setEnabled(false)
        assertFalse(fixture.state.isEnabled)
        val before = fixture.biometric.calls
        fixture.access.confirmSensitiveAction(UiText.Ios("Confirm before deleting this wallet."))
        assertEquals(before, fixture.biometric.calls)
    }

    // Android: the preference is read asynchronously (app-shell.md §15.2).

    @Test
    fun theAppStaysLockedAndResolvingUntilThePreferenceIsRead() = runTest {
        val preferences = TestPreferences(appLockEnabled = false).apply { loadGate = CompletableDeferred() }
        val fixture = makeAccess(preferences = preferences)
        assertTrue(fixture.state.resolving)
        assertTrue(fixture.state.isLocked)
        assertTrue(fixture.state.showsLock)
        val refused = async { runCatching { fixture.access.setEnabled(true) } }
        runCurrent()
        assertFalse(refused.isCompleted, "Guards wait for the stored value")
        preferences.loadGate!!.complete(Unit)
        runCurrent()
        assertFalse(fixture.state.resolving)
        assertFalse(fixture.state.isLocked)
        assertFalse(fixture.state.showsLock)
        // Resolved unlocked with no wallet count yet: enabling is refused for the missing wallet, not for the lock.
        assertEquals(UiText.Ios("Create a wallet before enabling App Lock."), (refused.await().exceptionOrNull() as AppIssue).text)
    }

    @Test
    fun anEnabledLockPromptsOnlyOnceThePreferenceIsRead() = runTest {
        val preferences = TestPreferences(appLockEnabled = true).apply { loadGate = CompletableDeferred() }
        val fixture = makeAccess(preferences = preferences)
        fixture.access.updateWalletCount(1)
        val unlocking = launch { fixture.access.authenticateBiometrics() }
        runCurrent()
        assertEquals(0, fixture.biometric.calls)
        preferences.loadGate!!.complete(Unit)
        runCurrent()
        assertTrue(unlocking.isCompleted)
        assertEquals(1, fixture.biometric.calls)
        assertFalse(fixture.state.isLocked)
    }

    @Test
    fun anUnreadablePreferenceFailsClosedAndAWalletPasswordStillOpens() = runTest {
        val vault = TestVault().apply { add(WALLET, "Public-Test-Password1!") }
        val preferences = TestPreferences().apply { loadFailure = IOException("disk") }
        val fixture = makeAccess(preferences = preferences, vault = vault)
        fixture.access.updateWalletCount(1)
        assertFalse(fixture.state.resolving)
        assertTrue(fixture.state.isEnabled)
        assertTrue(fixture.state.isLocked)
        fixture.access.authenticate(WALLET, password("Public-Test-Password1!"))
        assertFalse(fixture.state.isLocked)
    }

    @Test
    fun aLastWalletRemovedBeforeThePreferenceLoadsNeverLocks() = runTest {
        val preferences = TestPreferences(appLockEnabled = true).apply { loadGate = CompletableDeferred() }
        val fixture = makeAccess(preferences = preferences)
        fixture.access.updateWalletCount(0)
        assertTrue(fixture.state.showsLock, "Still resolving")
        preferences.loadGate!!.complete(Unit)
        runCurrent()
        assertFalse(fixture.state.isEnabled)
        assertFalse(fixture.state.isLocked)
        assertFalse(fixture.state.showsLock)
        assertFalse(preferences.stored.appLockEnabled)
    }

    // Vault errors pass through (WalletVault.swift:135-152): wrong password, then the per-wallet retry delay.

    @Test
    fun wrongPasswordsAreShownAndTheVaultsRetryDelayIsPerWallet() = runTest {
        val wallClock = FakeWallClock()
        val vault = TestVault(wallClock).apply {
            add(WALLET, "Public-Test-Password1!")
            add(OTHER_WALLET, "Other-Password2!")
        }
        val fixture = makeAccess(enabled = true, vault = vault)
        fixture.access.updateWalletCount(2)
        repeat(5) {
            assertFailsWith<VaultError.AuthenticationFailed> { fixture.access.authenticate(WALLET, password("Wrong-Password1!")) }
            assertEquals(UiText.Ios("The password is incorrect."), fixture.state.error)
        }
        val throttled = assertFailsWith<VaultError.TemporarilyLocked> {
            fixture.access.authenticate(WALLET, password("Public-Test-Password1!"))
        }
        assertEquals(2, throttled.seconds)
        assertEquals(UiText.Ios("Too many unsuccessful attempts. Try again in %lld seconds.", listOf(2L)), fixture.state.error)
        assertTrue(fixture.state.isLocked)
        // Another wallet's counter is its own: its password opens the app at once.
        fixture.access.authenticate(OTHER_WALLET, password("Other-Password2!"))
        assertFalse(fixture.state.isLocked)
        assertNull(fixture.state.error)
    }

    @Test
    fun aFailedBiometricIsPublishedForTheLockScreenAndCanBeCleared() = runTest {
        val fixture = makeAccess(enabled = true)
        fixture.access.updateWalletCount(1)
        fixture.biometric.failure = BiometricException(
            BiometricException.Kind.Lockout,
            UiText.Resource(R.string.security_biometric_lockout),
        )
        assertFailsWith<BiometricException> { fixture.access.authenticateBiometrics() }
        assertEquals(UiText.Resource(R.string.security_biometric_lockout), fixture.state.error)
        assertFalse(fixture.state.isAuthenticating)
        fixture.access.clearError()
        assertNull(fixture.state.error)
        // A dismissed prompt is silent.
        fixture.biometric.failure = BiometricCancellationException(BiometricCancellationException.Reason.NegativeButton)
        assertFailsWith<BiometricCancellationException> { fixture.access.authenticateBiometrics() }
        assertNull(fixture.state.error)
        assertTrue(fixture.state.isLocked)
    }

    @Test
    fun anotherAuthenticationWhileOneRunsOrInTheBackgroundIsRefused() = runTest {
        val vault = TestVault().apply { add(WALLET, "Public-Test-Password1!") }
        val fixture = makeAccess(enabled = true, vault = vault)
        fixture.access.updateWalletCount(1)
        fixture.biometric.blockNext = true
        val biometric = async { fixture.access.authenticateBiometrics() }
        fixture.biometric.awaitStarted()
        assertTrue(fixture.state.isAuthenticating)
        val busy = assertFailsWith<AppIssue> { fixture.access.authenticate(WALLET, password("Public-Test-Password1!")) }
        assertEquals(UiText.Ios("App authentication is unavailable right now."), busy.text)
        assertEquals(0, vault.verifications, "A refused attempt never reaches the vault counter")
        fixture.biometric.complete()
        biometric.await()
        assertFalse(fixture.state.isLocked)

        fixture.clock.advanceBy(1_000)
        fixture.access.didEnterBackground()
        fixture.access.setEnabled(true) // already on: no-op even in the background
        val background = assertFailsWith<AppIssue> { fixture.access.setEnabled(false) }
        assertEquals(UiText.Ios("Authenticate app access before changing App Lock."), background.text)
    }

    @Test
    fun cancellingTheCallerLeavesTheAppLocked() = runTest {
        val fixture = makeAccess(enabled = true)
        fixture.access.updateWalletCount(1)
        fixture.biometric.blockNext = true
        val unlocking = launch(start = CoroutineStart.UNDISPATCHED) { fixture.access.authenticateBiometrics() }
        fixture.biometric.awaitStarted()
        unlocking.cancel()
        runCurrent()
        assertTrue(fixture.state.isLocked)
        assertFalse(fixture.state.isAuthenticating)
        // A new attempt is not refused as "busy".
        fixture.access.authenticateBiometrics()
        assertFalse(fixture.state.isLocked)
    }

    @Test
    fun enablingIsAbandonedWhenTheLastWalletGoesDuringThePrompt() = runTest {
        val fixture = makeAccess()
        fixture.access.updateWalletCount(1)
        fixture.biometric.blockNext = true
        val enabling = async { fixture.access.setEnabled(true) }
        fixture.biometric.awaitStarted()
        fixture.biometric.cancelEndsBlockedCall = false
        fixture.access.updateWalletCount(0)
        fixture.biometric.complete()
        assertFailsWith<CancellationException> { enabling.await() }
        runCurrent()
        assertFalse(fixture.state.isEnabled)
        assertFalse(fixture.preferences.stored.appLockEnabled)
    }

    @Test
    fun aFailedPreferenceWriteLeavesAppLockUnchanged() = runTest {
        val fixture = makeAccess()
        fixture.access.updateWalletCount(1)
        fixture.preferences.writeFailure = IOException("disk full")
        val failure = assertFailsWith<SignerException> { fixture.access.setEnabled(true) }
        assertEquals(UiText.Resource(R.string.security_app_lock_save_failed), failure.text)
        assertFalse(fixture.state.isEnabled)
        assertFalse(fixture.state.isLocked)
        fixture.preferences.writeFailure = null
        fixture.access.setEnabled(true)
        assertTrue(fixture.state.isEnabled)
        assertEquals(listOf(true), fixture.preferences.appLockWrites)
    }

    @Test
    fun preferenceWritesKeepTheirCallOrder() = runTest {
        val fixture = makeAccess()
        fixture.access.updateWalletCount(1)
        val gate = CompletableDeferred<Unit>()
        fixture.preferences.writeGate = gate
        val enabling = async { fixture.access.setEnabled(true) }
        runCurrent()
        // The last wallet is deleted while `true` is being written: `false` must land last.
        fixture.access.updateWalletCount(0)
        gate.complete(Unit)
        assertFailsWith<CancellationException> { enabling.await() }
        runCurrent()
        assertEquals(listOf(true, false), fixture.preferences.appLockWrites)
        assertFalse(fixture.preferences.stored.appLockEnabled)
        assertFalse(fixture.state.isEnabled)
    }

    @Test
    fun thePromptsUseTheAndroidAppLockTexts() = runTest {
        val fixture = makeAccess()
        fixture.access.updateWalletCount(1)
        fixture.access.setEnabled(true)
        fixture.clock.advanceBy(1_000)
        fixture.access.didEnterBackground()
        fixture.clock.advanceBy(AppAccessController.BACKGROUND_GRACE_MILLIS)
        fixture.access.willEnterForeground()
        fixture.access.authenticateBiometrics()
        fixture.access.confirmSensitiveAction(UiText.Ios("Confirm before deleting this wallet."))
        val (enable, unlock, confirm) = fixture.biometric.prompts
        assertEquals(UiText.Resource(R.string.security_prompt_lock_title), enable.title)
        assertEquals(UiText.Resource(R.string.security_prompt_description), enable.description)
        assertEquals(UiText.Ios("Cancel"), enable.negativeButton)
        assertEquals(UiText.Resource(R.string.security_prompt_unlock_title), unlock.title)
        assertEquals(UiText.Resource(R.string.security_prompt_description), unlock.description)
        assertEquals(UiText.Ios("Use a Wallet Password"), unlock.negativeButton)
        assertEquals(UiText.Ios("Confirm before deleting this wallet."), confirm.title)
        assertNull(confirm.description)
        assertEquals(UiText.Ios("Cancel"), confirm.negativeButton)
    }

    @Test
    fun aDismissedSensitiveCheckCancelsAndAFailureLeavesThePasswordToDecide() = runTest {
        val fixture = makeAccess(enabled = true)
        fixture.access.updateWalletCount(1)
        fixture.access.authenticateBiometrics()
        fixture.biometric.failure = BiometricCancellationException(BiometricCancellationException.Reason.NegativeButton)
        assertFailsWith<CancellationException> { fixture.access.confirmSensitiveAction(UiText.Ios("Confirm before deleting this wallet.")) }
        fixture.biometric.failure = FakeBiometricAuthenticator.defaultFailure()
        assertFailsWith<BiometricException> { fixture.access.confirmSensitiveAction(UiText.Ios("Confirm before deleting this wallet.")) }
        assertFalse(fixture.state.isLocked, "The check never changes app access")
        assertNull(fixture.state.error, "The delete sheet shows its own outcome")

        fixture.biometric.available = false
        fixture.access.didEnterBackground()
        fixture.access.willEnterForeground()
        val calls = fixture.biometric.calls
        fixture.access.confirmSensitiveAction(UiText.Ios("Confirm before deleting this wallet."))
        assertEquals(calls, fixture.biometric.calls, "No prompt without available biometrics")
    }

    @Test
    fun theForegroundRefreshesBiometricAvailability() = runTest {
        val fixture = makeAccess()
        assertTrue(fixture.state.biometricsAvailable)
        fixture.biometric.available = false
        fixture.access.willEnterForeground() // no background visit: nothing to refresh
        assertTrue(fixture.state.biometricsAvailable)
        fixture.access.didEnterBackground()
        fixture.access.willEnterForeground()
        assertFalse(fixture.state.biometricsAvailable)
    }

    // HardeningTests.swift:120 (App Lock clock): hours of deep sleep count, so the phone reopens locked.
    @Test
    fun hoursAsleepInTheBackgroundLock() = runTest {
        val fixture = makeAccess(enabled = true)
        fixture.access.updateWalletCount(1)
        fixture.access.authenticateBiometrics()
        fixture.access.didEnterBackground()
        fixture.clock.advanceBy(3 * 60 * 60 * 1_000L)
        fixture.access.willEnterForeground()
        assertTrue(fixture.state.isLocked)
    }

    private companion object {
        val WALLET: WalletId = WalletId.parse("0b175160-9a7e-4c1d-8000-000000000001")!!
        val OTHER_WALLET: WalletId = WalletId.parse("0b175160-9a7e-4c1d-8000-000000000002")!!
    }
}
