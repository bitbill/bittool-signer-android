package com.bittool.signer.feature.sign.scanner

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.UiText
import com.bittool.signer.feature.sign.SignFixtures
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
 * The scan session's queue semantics, ported from iOS `ScanSession` (`Features/Sign/ScanTab.swift:9-86`; scan-sign.md
 * §7) and the late-result rules of `SessionTests.swift` (background during a scan).
 */
class ScannerViewModelTest {
    private val store = FakeSignerStore()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.scanner(): ScannerViewModel = ScannerViewModel(store, backgroundScope)

    private fun received(): List<String> = store.calls<FakeSignerStore.Call.ReceiveFrame>().map { it.frame }

    @Test
    fun framesSeenWhileTheSessionStartsAreProcessedInOrderOnceItIsReady() = runTest {
        val begin = CompletableDeferred<Unit>()
        store.onBeginScan = { begin.await() }
        store.onReceiveFrame = { 0.2 }
        val vm = scanner()
        vm.receive("UR:BYTES/1-3/A")
        vm.receive("UR:BYTES/2-3/B")
        runCurrent()
        assertEquals(emptyList(), received(), "nothing reaches the engine before the scan session exists")
        begin.complete(Unit)
        runCurrent()
        assertEquals(listOf("UR:BYTES/1-3/A", "UR:BYTES/2-3/B"), received())
        assertEquals(2, vm.uiState.value.received)
    }

    @Test
    fun oneFrameIsWithTheEngineAtATimeAndTheQueueKeepsItsOrder() = runTest {
        val gates = mutableMapOf<String, CompletableDeferred<Double>>()
        store.onReceiveFrame = { frame -> gates.getOrPut(frame) { CompletableDeferred() }.await() }
        val vm = scanner()
        runCurrent()
        vm.receive("A"); vm.receive("B"); vm.receive("C")
        runCurrent()
        assertEquals(listOf("A"), received(), "B and C wait for A")
        assertTrue(vm.uiState.value.processing)
        gates.getValue("A").complete(0.3)
        runCurrent()
        assertEquals(listOf("A", "B"), received())
        gates.getValue("B").complete(0.2)
        runCurrent()
        assertEquals(0.3, vm.uiState.value.progress, "progress never goes back")
        gates.getValue("C").complete(0.6)
        runCurrent()
        assertEquals(listOf("A", "B", "C"), received())
        assertEquals(0.6, vm.uiState.value.progress)
        assertEquals(false, vm.uiState.value.processing)
    }

    @Test
    fun anExactRepeatOfAFrameIsHandedInOnlyOnce() = runTest {
        val vm = scanner()
        runCurrent()
        vm.receive(" \nUR:BYTES/TESTFRAME\n")
        runCurrent()
        vm.receive(" \nUR:BYTES/TESTFRAME\n")
        vm.receive("UR:BYTES/TESTFRAME")
        runCurrent()
        assertEquals(listOf(" \nUR:BYTES/TESTFRAME\n", "UR:BYTES/TESTFRAME"), received(), "frames go to the engine unchanged")
        assertEquals(2, vm.uiState.value.received)
    }

    @Test
    fun aCompletedRequestClosesTheCameraAndEndsTheScanSession() = runTest {
        store.onReceiveFrame = {
            store.setState { it.copy(review = SignFixtures.bitcoinSend) }
            1.0
        }
        val vm = scanner()
        runCurrent()
        vm.receive("UR:CRYPTO-PSBT/OK")
        runCurrent()
        assertEquals(ScannerEvent.Completed, vm.events.first())
        assertEquals(1, store.calls<FakeSignerStore.Call.EndScan>().size)
        assertEquals(1.0, vm.uiState.value.progress)
        vm.receive("UR:CRYPTO-PSBT/LATE")
        runCurrent()
        assertEquals(listOf("UR:CRYPTO-PSBT/OK"), received(), "nothing is read once a review exists")
    }

    @Test
    fun aFailureShowsTheErrorDropsTheQueueAndIgnoresFramesUntilScanAgain() = runTest {
        val first = CompletableDeferred<Double>()
        store.onReceiveFrame = { frame -> if (frame == "A") first.await() else 0.1 }
        val vm = scanner()
        runCurrent()
        vm.receive("A"); vm.receive("B")
        runCurrent()
        first.completeExceptionally(IllegalStateException("Multiple unrelated QR payloads"))
        runCurrent()
        assertEquals(UiText.Verbatim("Multiple unrelated QR payloads"), vm.uiState.value.error, "engine English, verbatim")
        assertEquals(listOf("A"), received(), "the queued frame was dropped")
        vm.receive("C")
        runCurrent()
        assertEquals(listOf("A"), received(), "frames are ignored while the error is shown")

        vm.start()
        runCurrent()
        assertNull(vm.uiState.value.error)
        assertEquals(2, store.calls<FakeSignerStore.Call.BeginScan>().size, "Scan Again opens a new engine scan session")
        vm.receive("A")
        runCurrent()
        assertEquals(listOf("A", "A"), received(), "a new session has its own de-duplication")
    }

    @Test
    fun aHostErrorIsShownWithItsTranslatableText() = runTest {
        store.onReceiveFrame = { throw AppIssue("The request is incomplete. Import all QR frames.") }
        val vm = scanner()
        runCurrent()
        vm.receive("X")
        runCurrent()
        assertEquals(UiText.Ios("The request is incomplete. Import all QR frames."), vm.uiState.value.error)
    }

    @Test
    fun aStartFailureBecomesTheScannerError() = runTest {
        store.onBeginScan = { throw IllegalStateException("The offline signing engine could not be started.") }
        val vm = scanner()
        runCurrent()
        assertEquals(UiText.Verbatim("The offline signing engine could not be started."), vm.uiState.value.error)
        vm.receive("A")
        runCurrent()
        assertEquals(emptyList(), received())
    }

    @Test
    fun aFrameInterruptedByBackgroundingIsRetriedWithTheNextFrame() = runTest {
        val first = CompletableDeferred<Double>()
        store.onReceiveFrame = { frame -> if (frame == "A" && received().size == 1) first.await() else 0.4 }
        val vm = scanner()
        runCurrent()
        vm.receive("A")
        runCurrent()
        // The app goes to the background mid-call: the store renews the epoch and reports a cancellation.
        store.suspendSession()
        first.completeExceptionally(CancellationException("The session changed; the late result is discarded."))
        runCurrent()
        assertNull(vm.uiState.value.error, "a cancellation is never shown")
        assertEquals(false, vm.uiState.value.processing)
        assertEquals(listOf("A"), received())
        vm.receive("B")
        runCurrent()
        assertEquals(listOf("A", "A", "B"), received(), "the interrupted part goes first, then the new one")
    }

    @Test
    fun aResultForAnEarlierSessionIsDropped() = runTest {
        val first = CompletableDeferred<Double>()
        store.onReceiveFrame = { frame -> if (frame == "OLD") first.await() else 0.1 }
        val vm = scanner()
        runCurrent()
        vm.receive("OLD")
        runCurrent()
        vm.start()
        runCurrent()
        first.complete(0.9)
        runCurrent()
        assertEquals(0.0, vm.uiState.value.progress, "the restarted session ignores the old result")
        assertEquals(false, vm.uiState.value.processing)
    }

    @Test
    fun statusFollowsTheSessionWords() = runTest {
        val gate = CompletableDeferred<Double>()
        store.onReceiveFrame = { frame -> if (frame == "1") gate.await() else 0.5 }
        val vm = scanner()
        runCurrent()
        assertNull(vm.uiState.value.status, "the hint shows before anything is read")
        vm.receive("1")
        runCurrent()
        assertEquals(UiText.Resource(R.string.sign_reading_request), vm.uiState.value.status)
        gate.complete(0.25)
        vm.receive("2")
        runCurrent()
        assertEquals(UiText.Resource(R.string.sign_keep_animated_code_in_view, listOf(2)), vm.uiState.value.status)
    }

    @Test
    fun closingEndsTheSessionOnceAndAClearedScannerNeverEndsALaterOne() = runTest {
        val viewModels = ViewModelStore()
        val vm = ViewModelProvider.create(
            viewModels,
            viewModelFactory { initializer { ScannerViewModel(store, backgroundScope) } },
        )[ScannerViewModel::class]
        runCurrent()
        vm.stop()
        runCurrent()
        assertEquals(1, store.calls<FakeSignerStore.Call.EndScan>().size)
        viewModels.clear()
        runCurrent()
        assertEquals(1, store.calls<FakeSignerStore.Call.EndScan>().size, "Close already ended it")

        val other = ViewModelStore()
        ViewModelProvider.create(other, viewModelFactory { initializer { ScannerViewModel(store, backgroundScope) } })[ScannerViewModel::class]
        runCurrent()
        other.clear()
        runCurrent()
        assertEquals(2, store.calls<FakeSignerStore.Call.EndScan>().size, "a scanner removed without Close still ends its session")
    }
}
