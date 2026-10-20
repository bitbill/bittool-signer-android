package com.bittool.signer.root

import com.bittool.signer.core.common.time.MonotonicClock
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.security.AppAccessState

/**
 * When the system splash lets go (app-shell.md §15.3; Ownbit `LaunchScreenGate`): into the app as fast as possible,
 * with the finished first screen as the first frame after the splash.
 *
 * - App Lock's preference is still being read: keep (fail closed; the lock window may be needed).
 * - App Lock is up: let go at once — the lock screen is the first screen, and the wallet list may finish loading
 *   behind it (the password picker fills in, app-shell.md §11.12).
 * - Otherwise keep while the first wallet-list load runs, so the first frame is Welcome or the tabs.
 * - Never longer than [MAX_WAIT_MILLIS] from the first frame request: then `LaunchView` (identical to the splash)
 *   continues it until loading ends and cross-fades to the stage (iOS `.smooth(duration: 0.35)`).
 *
 * Asked before every frame while it says "keep"; once it lets go it never holds again.
 */
class LaunchGate(private val clock: MonotonicClock) {
    private var firstAskedAt: Long? = null
    private var released = false

    fun keepOnScreen(access: AppAccessState, store: SignerState): Boolean {
        if (released) return false
        val now = clock.elapsedRealtimeMillis()
        val start = firstAskedAt ?: now.also { firstAskedAt = it }
        val keep = keep(access, store, waitedMillis = now - start)
        if (!keep) released = true
        return keep
    }

    companion object {
        /** The longest the splash waits for the lock state and the first load (Ownbit `MAX_FIRST_SCREEN_WAIT_MILLIS`). */
        const val MAX_WAIT_MILLIS = 1_500L

        /** The rule without the clock and the latch. */
        fun keep(access: AppAccessState, store: SignerState, waitedMillis: Long): Boolean = when {
            waitedMillis >= MAX_WAIT_MILLIS -> false
            access.resolving -> true
            access.isLocked -> false
            else -> store.isLoading
        }
    }
}
