package com.bittool.signer.core.common

import com.bittool.signer.core.common.coroutines.rethrowIfCancellation
import com.bittool.signer.core.common.coroutines.runCatchingCancellable
import com.bittool.signer.core.common.log.Log
import com.bittool.signer.core.common.testing.FakeMonotonicClock
import com.bittool.signer.core.common.testing.FakeWallClock
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class CommonTest {
    @Test
    fun cancellationIsNeverCaught() {
        assertFailsWith<CancellationException> { runCatchingCancellable { throw CancellationException("late") } }
        val failure = runCatchingCancellable { error("boom") }
        assertTrue(failure.isFailure)
        assertEquals(5, runCatchingCancellable { 5 }.getOrThrow())
        assertFailsWith<CancellationException> { CancellationException().rethrowIfCancellation() }
        val other = IllegalStateException()
        assertEquals(other, other.rethrowIfCancellation())
    }

    @Test
    fun theLoggerBuildsNothingWhileDisabled() {
        val lines = mutableListOf<String>()
        Log.sink = Log.Sink { _, _, message, _ -> lines += message }
        var built = 0
        Log.enabled = false
        Log.d("t") { built++; "hidden" }
        assertEquals(0, built)
        Log.enabled = true
        Log.w("t") { "shown" }
        Log.enabled = false
        assertEquals(listOf("shown"), lines)
    }

    @Test
    fun fakeClocksAdvance() {
        val clock = FakeMonotonicClock(100_000)
        clock.advanceBy(119_000)
        assertEquals(219_000, clock.elapsedRealtimeMillis())
        clock.advanceBy(-1)
        assertEquals(218_999, clock.elapsedRealtimeMillis())
        val wall = FakeWallClock(5)
        wall.advanceBy(5)
        assertEquals(10, wall.currentTimeMillis())
    }

    @Test
    fun buildInfoShowsTheIosStyleVersion() {
        val info = BuildInfo("com.bittool.signer", "1.0.0", 1_000_003, Distribution.GooglePlay, isDebug = false, repositoryUrl = "x")
        assertEquals("1.0.0 (3)", info.displayVersion)
        assertEquals("1.0.0 (0)", info.copy(versionName = "1.0.0-debug", versionCode = 1_000_000).displayVersion)
        assertEquals(Distribution.DirectApk, Distribution.fromFlavor("apk"))
        assertEquals(Distribution.GooglePlay, Distribution.fromFlavor("play"))
    }
}
