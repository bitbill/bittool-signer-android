package com.bittool.signer.core.common.testing

import com.bittool.signer.core.common.time.MonotonicClock
import com.bittool.signer.core.common.time.WallClock

/**
 * A controllable [MonotonicClock] for JVM tests (the iOS AppAccessTests start at 100 s). Not used by production code;
 * R8 removes it from release builds.
 */
class FakeMonotonicClock(var nowMillis: Long = 100_000L) : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = nowMillis

    /** Moves time forward (or backward, to test clock regressions). */
    fun advanceBy(millis: Long) {
        nowMillis += millis
    }
}

/** A controllable [WallClock] for JVM tests. */
class FakeWallClock(var nowMillis: Long = 1_760_000_000_000L) : WallClock {
    override fun currentTimeMillis(): Long = nowMillis

    fun advanceBy(millis: Long) {
        nowMillis += millis
    }
}
