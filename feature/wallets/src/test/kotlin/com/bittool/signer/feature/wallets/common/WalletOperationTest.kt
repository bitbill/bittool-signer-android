package com.bittool.signer.feature.wallets.common

import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.UiText
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The port of iOS `WalletOperation` (`WalletSecuritySheets.swift:6-29`, wallets-pairing.md §8.1). */
class WalletOperationTest {
    @Test
    fun oneOperationAtATimeAndItsErrorIsShown() = runTest(UnconfinedTestDispatcher()) {
        var epoch = 0L
        val operation = WalletOperation(backgroundScope) { epoch }
        val hold = CompletableDeferred<Unit>()
        var runs = 0
        assertTrue(operation.run { runs++; hold.await(); throw AppIssue("The password is incorrect.") })
        assertTrue(operation.state.value.busy)
        assertFalse(operation.run { runs++ }, "ignored while busy")
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, runs)
        assertFalse(operation.state.value.busy)
        assertEquals(UiText.Ios("The password is incorrect."), operation.state.value.error)
    }

    @Test
    fun aFailureAfterTheSecurityEpochChangedIsSilent() = runTest(UnconfinedTestDispatcher()) {
        var epoch = 0L
        val operation = WalletOperation(backgroundScope) { epoch }
        val hold = CompletableDeferred<Unit>()
        operation.run { hold.await(); throw AppIssue("The password is incorrect.") }
        epoch = 1
        hold.complete(Unit)
        advanceUntilIdle()
        assertNull(operation.state.value.error)
        assertFalse(operation.state.value.busy)
    }

    @Test
    fun aCancellationIsNeverAnError() = runTest(UnconfinedTestDispatcher()) {
        val operation = WalletOperation(backgroundScope) { 0L }
        operation.run { throw CancellationException("late result") }
        advanceUntilIdle()
        assertNull(operation.state.value.error)
        assertFalse(operation.state.value.busy)
    }

    @Test
    fun cancelForgetsTheRunningOperation() = runTest(UnconfinedTestDispatcher()) {
        val operation = WalletOperation(backgroundScope) { 0L }
        val hold = CompletableDeferred<Unit>()
        var finished = false
        operation.run {
            try {
                hold.await()
                throw AppIssue("The password is incorrect.")
            } finally {
                finished = true
            }
        }
        operation.cancel()
        assertTrue(finished, "the work's finally (the secret wipe) ran")
        assertFalse(operation.state.value.busy)
        assertNull(operation.state.value.error)
        assertTrue(operation.run { }, "a new run may start at once")
    }
}
