package com.bittool.signer.core.security.access

import com.bittool.signer.core.common.testing.FakeMonotonicClock
import com.bittool.signer.core.crypto.vault.VaultError
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.security.testing.FakeBiometricAuthenticator
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * QA L2 (production-02, Android-only): removing the device's screen lock deletes every wallet's device key and the
 * enrolled fingerprints, and a new lock brings neither back. With App Lock on, the lock screen then had no way in: no
 * biometric, and every wallet password ended in "device key unavailable". App Lock guards public pages only, so when
 * it is *proven* that nothing can open it — biometrics cannot run and every listed wallet's key is provably gone — it
 * gives way: "Continue" turns it off. Anything less than that proof keeps it closed.
 */
class NoUnlockPathTest {
    private class Fixture(
        val access: DefaultAppAccessController,
        val biometric: FakeBiometricAuthenticator,
        val preferences: TestPreferences,
        val vault: TestVault,
        val first: WalletId,
        val second: WalletId,
    ) {
        val state get() = access.state.value
    }

    /** App Lock on and locked after a cold launch, two wallets, no biometric enrolled any more. */
    private fun TestScope.locked(): Fixture {
        val preferences = TestPreferences(appLockEnabled = true)
        val vault = TestVault()
        val first = WalletId.random().also { vault.add(it, "First-Password1") }
        val second = WalletId.random().also { vault.add(it, "Second-Password1") }
        val biometric = FakeBiometricAuthenticator(available = false)
        val access = DefaultAppAccessController(
            preferences,
            vault,
            biometric,
            FakeMonotonicClock(nowMillis = 100_000L),
            backgroundScope,
            UnconfinedTestDispatcher(testScheduler),
        )
        runCurrent()
        access.updateWalletCount(2)
        return Fixture(access, biometric, preferences, vault, first, second).also { assertTrue(it.state.isLocked) }
    }

    @Test
    fun whenEveryWalletKeyIsGoneAndNoBiometricCanRunAppLockGivesWayAndTurnsItselfOff() = runTest {
        val fixture = locked()
        fixture.vault.lostKeys += listOf(fixture.first, fixture.second)
        // The state production-02 found: every password ends in "device key unavailable".
        assertFailsWith<VaultError.DeviceKeyUnavailable> { fixture.access.authenticate(fixture.first, password("First-Password1")) }
        assertTrue(fixture.state.isLocked)

        assertTrue(fixture.access.hasNoUnlockPath())
        assertTrue(fixture.state.isLocked, "asking changes nothing")
        assertTrue(fixture.access.continueWithoutUnlockPath())
        assertFalse(fixture.state.isLocked)
        assertFalse(fixture.state.isEnabled)
        assertEquals(listOf(false), fixture.preferences.appLockWrites, "App Lock is off for good, not just this once")
        assertEquals(0, fixture.biometric.calls, "no prompt")
    }

    @Test
    fun oneWalletThatCanStillBeUnlockedKeepsAppLockClosed() = runTest {
        val fixture = locked()
        fixture.vault.lostKeys += fixture.first
        assertFalse(fixture.access.hasNoUnlockPath())
        assertFalse(fixture.access.continueWithoutUnlockPath())
        assertTrue(fixture.state.isLocked)
        assertTrue(fixture.state.isEnabled)
        assertTrue(fixture.preferences.appLockWrites.isEmpty())
        // That wallet's password is the way in, as always.
        fixture.access.authenticate(fixture.second, password("Second-Password1"))
        assertFalse(fixture.state.isLocked)
        assertTrue(fixture.state.isEnabled)
    }

    @Test
    fun aWrongPasswordNeverLeadsThere() = runTest {
        val fixture = locked()
        assertFailsWith<VaultError.AuthenticationFailed> { fixture.access.authenticate(fixture.first, password("Wrong-Password1")) }
        assertFalse(fixture.access.hasNoUnlockPath())
        assertEquals(1, fixture.vault.lossChecks, "the first wallet whose key is fine ends the look")
        assertFalse(fixture.access.continueWithoutUnlockPath())
        assertTrue(fixture.state.isLocked)
    }

    @Test
    fun anAvailableBiometricIsAWayIn() = runTest {
        val fixture = locked()
        fixture.vault.lostKeys += listOf(fixture.first, fixture.second)
        fixture.biometric.available = true // a fingerprint enrolled again in Android Settings
        assertFalse(fixture.access.hasNoUnlockPath())
        assertFalse(fixture.access.continueWithoutUnlockPath())
        assertTrue(fixture.state.isLocked)
        assertEquals(0, fixture.vault.lossChecks, "biometrics decide first; the keys are not even looked at")
    }

    @Test
    fun anUnreadableWalletListOrNoWalletProvesNothing() = runTest {
        val fixture = locked()
        fixture.vault.lostKeys += listOf(fixture.first, fixture.second)
        fixture.vault.listFailure = VaultError.MalformedVault()
        assertFalse(fixture.access.hasNoUnlockPath())
        assertFalse(fixture.access.continueWithoutUnlockPath())
        assertTrue(fixture.state.isLocked)
        fixture.vault.listFailure = null
        assertTrue(fixture.access.hasNoUnlockPath())
    }

    @Test
    fun theProofIsMadeAgainOnContinueAndAFailedWriteLeavesAppLockOn() = runTest {
        val fixture = locked()
        fixture.vault.lostKeys += listOf(fixture.first, fixture.second)
        assertTrue(fixture.access.hasNoUnlockPath())
        fixture.vault.lostKeys -= fixture.second // whatever the screen said, Continue proves it again
        assertFalse(fixture.access.continueWithoutUnlockPath())
        assertTrue(fixture.state.isLocked)

        fixture.vault.lostKeys += fixture.second
        fixture.preferences.writeFailure = IOException("disk full")
        val failed = assertFailsWith<SignerException> { fixture.access.continueWithoutUnlockPath() }
        assertEquals(UiText.Resource(R.string.security_app_lock_save_failed), failed.text)
        assertTrue(fixture.state.isLocked)
        assertTrue(fixture.state.isEnabled)
        assertFalse(fixture.state.isAuthenticating)
    }

    @Test
    fun nothingIsAskedWhileAppLockIsOffUnlockedOrInTheBackground() = runTest {
        val fixture = locked()
        fixture.vault.lostKeys += listOf(fixture.first, fixture.second)
        fixture.access.didEnterBackground()
        assertFalse(fixture.access.hasNoUnlockPath())
        fixture.access.willEnterForeground()
        assertTrue(fixture.access.hasNoUnlockPath())
        assertTrue(fixture.access.continueWithoutUnlockPath())
        assertFalse(fixture.access.hasNoUnlockPath(), "App Lock is off now")
        assertTrue(fixture.access.continueWithoutUnlockPath(), "nothing left to open")
        assertEquals(listOf(false), fixture.preferences.appLockWrites)
    }
}
