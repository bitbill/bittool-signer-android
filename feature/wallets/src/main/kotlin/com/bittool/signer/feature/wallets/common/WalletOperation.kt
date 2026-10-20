package com.bittool.signer.feature.wallets.common

import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.toUiText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One password-authorised wallet operation at a time (iOS `WalletOperation`, `WalletSecuritySheets.swift:6-29`;
 * wallets-pairing.md §8.1): "Late results are dropped if the sheet closed, the app was backgrounded or another attempt
 * started."
 *
 * - [run] is ignored while [State.busy]; each run gets a token and remembers the security epoch it started under.
 * - A failure is shown only while its token is current, the epoch is unchanged and it is not a cancellation (late
 *   results after background, App Lock or capture are silent).
 * - `busy` is reset only by the current token; [cancel] invalidates the token, cancels the work and clears both.
 *
 * Main thread only (ViewModel scope). Owns no secret: the work lambda captures the caller's `SecretText` and the caller
 * wipes it.
 */
internal class WalletOperation(
    private val scope: CoroutineScope,
    private val currentEpoch: () -> Long,
) {
    data class State(val busy: Boolean = false, val error: UiText? = null)

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    val busy: Boolean get() = mutableState.value.busy

    private var token = 0L
    private var job: Job? = null

    /** Starts [work] unless an operation is in flight; returns whether it started. */
    fun run(work: suspend () -> Unit): Boolean {
        if (mutableState.value.busy) return false
        val id = ++token
        val epoch = currentEpoch()
        mutableState.value = State(busy = true, error = null)
        // Undispatched: the work (and the `try/finally` that wipes its secrets) is entered before this returns, so a
        // `cancel()` right after `run` can never skip the wipe.
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                work()
            } catch (failure: Throwable) {
                val text = failure.toUiText()
                if (token == id && epoch == currentEpoch() && text != null) {
                    mutableState.update { it.copy(error = text) }
                }
                // A real cancellation of this coroutine still propagates (structured concurrency); a store-side
                // `CancellationException` (late result) ends here silently because the coroutine is done either way.
                if (failure is CancellationException) throw failure
            } finally {
                if (token == id) mutableState.update { it.copy(busy = false) }
            }
        }
        return true
    }

    /** Shows [text] (or clears it) outside of a run, e.g. "Enter this wallet’s password." for an empty field. */
    fun setError(text: UiText?) {
        mutableState.update { it.copy(error = text) }
    }

    /** iOS `cancel()`: the current run is forgotten, its late result never shows, busy and error are cleared. */
    fun cancel() {
        token++
        job?.cancel()
        job = null
        mutableState.value = State()
    }
}
