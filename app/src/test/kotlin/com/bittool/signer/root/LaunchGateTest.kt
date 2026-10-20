package com.bittool.signer.root

import com.bittool.signer.core.common.testing.FakeMonotonicClock
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.security.AppAccessState
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** When the system splash lets go (app-shell.md §15.3). */
class LaunchGateTest {
    private val resolving = AppAccessState(resolving = true)
    private val unlocked = AppAccessState(resolving = false, isEnabled = false, isLocked = false)
    private val locked = AppAccessState(resolving = false, isEnabled = true, isLocked = true)
    private val loading = SignerState(isLoading = true)
    private val loaded = SignerState(isLoading = false, walletLoadSucceeded = true)

    @Test
    fun theSplashWaitsForAppLockAndTheFirstLoad() {
        assertTrue(LaunchGate.keep(resolving, loaded, waitedMillis = 0))
        assertTrue(LaunchGate.keep(unlocked, loading, waitedMillis = 0))
        assertFalse(LaunchGate.keep(unlocked, loaded, waitedMillis = 0))
    }

    @Test
    fun aLockedLaunchGoesStraightToTheLockScreen() {
        assertFalse(LaunchGate.keep(locked, loading, waitedMillis = 0))
    }

    @Test
    fun theWaitIsCappedAndNeverResumes() {
        val clock = FakeMonotonicClock()
        val gate = LaunchGate(clock)
        assertTrue(gate.keepOnScreen(unlocked, loading))
        clock.advanceBy(LaunchGate.MAX_WAIT_MILLIS - 1)
        assertTrue(gate.keepOnScreen(resolving, loading))
        clock.advanceBy(1)
        assertFalse(gate.keepOnScreen(resolving, loading), "LaunchView continues the splash from here")
        // Once released it never holds again, whatever happens next.
        assertFalse(gate.keepOnScreen(resolving, loading))
    }
}
