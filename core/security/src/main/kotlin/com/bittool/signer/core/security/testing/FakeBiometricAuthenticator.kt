package com.bittool.signer.core.security.testing

import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.security.BiometricAuthenticator
import com.bittool.signer.core.security.BiometricCancellationException
import com.bittool.signer.core.security.BiometricException
import com.bittool.signer.core.security.BiometricPromptSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * A scriptable [BiometricAuthenticator] for JVM tests: the port of the iOS `TestBiometric` double
 * (BitToolSignerTests/AppAccessTests.swift:173-196). It can fail, block until completed, and counts calls. Not used by
 * production code; R8 removes it from release builds.
 *
 * Single-threaded use (a `runTest` scheduler, or the main thread).
 */
class FakeBiometricAuthenticator(
    /** What [isAvailable] reports (iOS fixtures use Face ID, so the default is available). */
    var available: Boolean = true,
) : BiometricAuthenticator {

    /** Every [authenticate] call, in order (iOS `calls`). */
    var calls: Int = 0
        private set

    /** The prompts that were requested, in order. */
    val prompts: MutableList<BiometricPromptSpec> = mutableListOf()

    /** How many times [cancel] ran (background transitions, superseded authentications). */
    var cancelCalls: Int = 0
        private set

    /** When non-null, every call throws it (after counting). */
    var failure: Throwable? = null

    /** iOS `shouldFail`: every call fails with [defaultFailure]. */
    var shouldFail: Boolean
        get() = failure != null
        set(value) {
            failure = if (value) defaultFailure() else null
        }

    /** iOS `blockNext`: the next call suspends until [complete], [fail] or [cancel]. */
    var blockNext: Boolean = false

    /**
     * Whether [cancel] ends a blocked call with [BiometricCancellationException] (iOS `TestBiometric.cancel()` resumes
     * with `CancellationError`). Set false to model a prompt whose success arrives after the cancel.
     */
    var cancelEndsBlockedCall: Boolean = true

    private var pending: CompletableDeferred<Unit>? = null
    private val blocked = MutableStateFlow(false)

    /** Whether a call is currently blocked. */
    val isBlocked: Boolean get() = pending != null

    override fun isAvailable(): Boolean = available

    override suspend fun authenticate(prompt: BiometricPromptSpec) {
        calls += 1
        prompts += prompt
        failure?.let { throw it }
        if (!blockNext) return
        blockNext = false
        val gate = CompletableDeferred<Unit>()
        pending = gate
        blocked.value = true
        try {
            gate.await()
        } finally {
            if (pending === gate) {
                pending = null
                blocked.value = false
            }
        }
    }

    /** Suspends until a call is blocked (iOS `waitUntilStarted()`). */
    suspend fun awaitStarted() {
        blocked.first { it }
    }

    /** Lets the blocked call succeed (iOS `complete()`). */
    fun complete() {
        release()?.complete(Unit)
    }

    /** Ends the blocked call with [error]. */
    fun fail(error: Throwable = defaultFailure()) {
        release()?.completeExceptionally(error)
    }

    override fun cancel() {
        cancelCalls += 1
        if (cancelEndsBlockedCall) {
            release()?.completeExceptionally(BiometricCancellationException(BiometricCancellationException.Reason.SystemCanceled))
        }
    }

    private fun release(): CompletableDeferred<Unit>? {
        val gate = pending ?: return null
        pending = null
        blocked.value = false
        return gate
    }

    companion object {
        /** The text of [defaultFailure]. */
        val FAILURE_TEXT: UiText = UiText.Ios("Authentication failed. Try again or use a wallet password.")

        /** The failure [shouldFail] throws: a [BiometricException.Kind.Failed] with [FAILURE_TEXT]. */
        fun defaultFailure(): BiometricException = BiometricException(BiometricException.Kind.Failed, FAILURE_TEXT)
    }
}
