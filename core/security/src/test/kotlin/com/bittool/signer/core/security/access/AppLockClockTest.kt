package com.bittool.signer.core.security.access

import android.os.SystemClock
import com.bittool.signer.core.common.time.SystemMonotonicClock
import java.time.Duration
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/**
 * Port of HardeningTests.swift:120 `appLockClockKeepsCountingAcrossSleepableIntervals` for the clock App Lock is
 * given in production (`ClockModule` binds [SystemMonotonicClock] = `SystemClock.elapsedRealtime()`, which keeps
 * counting in deep sleep and ignores wall-clock changes, app-shell.md §15.5).
 */
@RunWith(RobolectricTestRunner::class)
class AppLockClockTest {

    @Test
    fun appLockClockKeepsCountingAcrossSleepableIntervals() {
        val clock = SystemMonotonicClock()
        val first = clock.elapsedRealtimeMillis()
        val second = clock.elapsedRealtimeMillis()
        assertTrue(second >= first)
        assertTrue(first > 0)
    }

    @Test
    fun wallClockChangesDoNotMoveIt() {
        val clock = SystemMonotonicClock()
        val before = clock.elapsedRealtimeMillis()
        SystemClock.setCurrentTimeMillis(0)
        assertTrue(clock.elapsedRealtimeMillis() >= before, "Setting the date back cannot reopen the app")
        ShadowSystemClock.advanceBy(Duration.ofHours(3))
        assertTrue(clock.elapsedRealtimeMillis() - before >= Duration.ofHours(3).toMillis())
    }
}
