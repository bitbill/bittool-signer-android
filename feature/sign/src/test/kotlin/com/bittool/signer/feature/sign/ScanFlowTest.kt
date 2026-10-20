package com.bittool.signer.feature.sign

import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.navigation.AppRouter
import com.bittool.signer.core.navigation.Tab
import com.bittool.signer.feature.sign.common.SignatureFileWriter
import com.bittool.signer.feature.sign.result.SignatureResultViewModel
import com.bittool.signer.feature.sign.review.RequestReviewViewModel
import com.bittool.signer.feature.sign.scanner.ScannerEvent
import com.bittool.signer.feature.sign.scanner.ScannerViewModel
import com.bittool.signer.feature.sign.tab.ScanStage
import com.bittool.signer.feature.sign.tab.ScanTabViewModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Port of `BitToolSignerTests/ScanRequestRouterTests.swift` at ViewModel level (ARCHITECTURE §3 W2-sign): the Wallets
 * toolbar asks for the camera once; scanning a request, reviewing it, signing it and tapping Done never opens the
 * camera again (iOS regression #5, `IMPLEMENTATION_STATUS.md:125-131`).
 */
class ScanFlowTest {
    private val store = FakeSignerStore(SignFixtures.walletsState())
    private val router = AppRouter()
    private val noExport = SignatureFileWriter { _, _ -> }

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.tab(): ScanTabViewModel = ScanTabViewModel(store, router).also { vm ->
        backgroundScope.launch { vm.stage.collect {} }
    }

    @Test
    fun theCameraDoesNotReopenAfterASignatureIsFinished() = runTest {
        // The Wallets toolbar "Scan".
        router.startScanning()
        assertEquals(Tab.Scan, router.tab.value)
        val tab = tab()
        runCurrent()
        assertTrue(tab.consumeScanRequest(), "the camera opens")

        // The scanner reads a request; the store reviews and matches it.
        val plan = SignFixtures.plan("task-1" to SignFixtures.walletA)
        store.onReceiveFrame = {
            store.setState { it.copy(review = SignFixtures.bitcoinSend, plan = plan) }
            1.0
        }
        val scanner = ScannerViewModel(store, backgroundScope)
        runCurrent()
        scanner.receive("UR:CRYPTO-PSBT/REQUEST")
        runCurrent()
        assertEquals(ScannerEvent.Completed, scanner.events.first())
        assertEquals(ScanStage.Review, tab.stage.value)
        assertFalse(tab.consumeScanRequest(), "reopened the camera after: request arrives")

        val review = RequestReviewViewModel(store)
        backgroundScope.launch { review.uiState.collect {} }
        runCurrent()
        assertFalse(tab.consumeScanRequest(), "reopened the camera after: review")

        // Sign with the wallet's password: the final task publishes the response.
        store.onSign = { taskId, _, _ ->
            store.setState {
                it.copy(
                    completedTaskIds = it.completedTaskIds + taskId,
                    signatureFrames = SignFixtures.responseFrames,
                    signatureText = SignFixtures.responseFrames.joinToString("\n"),
                    signaturePayload = SignFixtures.psbtPayload,
                )
            }
        }
        review.openAuthorize()
        runCurrent()
        review.sign(SecretText.of("Public-Test-Password1!"))
        runCurrent()
        assertEquals(ScanStage.Signature, tab.stage.value)
        assertFalse(tab.consumeScanRequest(), "reopened the camera after: signature result")

        // Done.
        SignatureResultViewModel(store, noExport).done()
        runCurrent()
        assertEquals(ScanStage.Home, tab.stage.value)
        assertFalse(tab.consumeScanRequest(), "reopened the camera after: Done")

        // Asking again opens it again.
        router.startScanning()
        assertTrue(tab.consumeScanRequest())
    }

    @Test
    fun askingWhileARequestIsReviewedIsConsumedWithoutOpeningTheCamera() = runTest {
        store.setState { it.copy(review = SignFixtures.bitcoinSend) }
        val tab = tab()
        runCurrent()
        router.startScanning()
        assertFalse(tab.consumeScanRequest(), "never over a review")
        store.clearRequest()
        runCurrent()
        assertFalse(tab.consumeScanRequest(), "and not later either, once the review is gone")
    }

    @Test
    fun movingToTheScanTabByHandNeverOpensTheCamera() = runTest {
        val tab = tab()
        router.onTabClicked(Tab.Scan)
        assertFalse(tab.consumeScanRequest())
        router.onTabClicked(Tab.Scan)
        assertFalse(tab.consumeScanRequest(), "a reselection does not open it either")
    }

    @Test
    fun theStageFollowsTheStoreInIosOrder() {
        val base = SignFixtures.walletsState()
        assertEquals(ScanStage.Home, ScanStage.of(base))
        assertEquals(ScanStage.Review, ScanStage.of(base.copy(review = SignFixtures.bitcoinSend)))
        assertEquals(ScanStage.Signature, ScanStage.of(base.copy(review = SignFixtures.bitcoinSend, signatureText = "UR:X")))
        assertEquals(ScanStage.Signature, ScanStage.of(base.copy(signatureFrames = listOf("UR:X"))), "a response first")
    }
}
