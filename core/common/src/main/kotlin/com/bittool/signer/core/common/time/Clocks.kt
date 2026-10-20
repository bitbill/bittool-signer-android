package com.bittool.signer.core.common.time

import android.os.SystemClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monotonic time that keeps counting in deep sleep and ignores wall-clock changes: `SystemClock.elapsedRealtime()`
 * (app-shell.md §15.5). Used for the App Lock 120 s background grace and the QR frame-gate throttle. Never use
 * `uptimeMillis()` (pauses in sleep: the iOS F8 bug) or the wall clock for these.
 */
fun interface MonotonicClock {
    /** Milliseconds since boot, including deep sleep. */
    fun elapsedRealtimeMillis(): Long
}

/** Wall-clock time (epoch milliseconds) for timestamps people see and the vault retry counter's `retryAfter`. */
fun interface WallClock {
    fun currentTimeMillis(): Long
}

/** The device implementation of [MonotonicClock]. */
@Singleton
class SystemMonotonicClock @Inject constructor() : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}

/** The device implementation of [WallClock]. */
@Singleton
class SystemWallClock @Inject constructor() : WallClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ClockModule {
    @Binds abstract fun monotonicClock(clock: SystemMonotonicClock): MonotonicClock

    @Binds abstract fun wallClock(clock: SystemWallClock): WallClock
}
