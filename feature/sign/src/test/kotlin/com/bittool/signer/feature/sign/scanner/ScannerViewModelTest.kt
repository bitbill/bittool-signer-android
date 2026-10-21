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

    private fun TestScope.eventsOf(vm: ScannerViewModel): List<ScannerEvent> =
        mutableListOf<ScannerEvent>().also { events -> backgroundScope.launch { vm.events.collect { events += it } } }

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

    /**
     * Audit A09: a file import still running when the camera opened publishes its review under the camera. On iOS the
     * camera is Scan Home's cover and Home gives way to the review, which dismisses it (`ScanTab.swift:96-104,147`);
     * here the camera stayed up, silently ignoring every code it was pointed at.
     */
    @Test
    fun aReviewPublishedFromElsewhereClosesTheCameraOntoIt() = runTest {
        val vm = scanner()
        val events = eventsOf(vm)
        runCurrent()
        store.setState { it.copy(review = SignFixtures.bitcoinSend) }
        runCurrent()
        assertEquals(listOf<ScannerEvent>(ScannerEvent.Completed), events, "the camera closes onto the imported review")
        assertEquals(1, store.calls<FakeSignerStore.Call.EndScan>().size, "and its engine scan session ends")
        vm.receive("UR:CRYPTO-PSBT/OTHER")
        runCurrent()
        assertEquals(emptyList(), received(), "no frame can clear the review")
        assertEquals(listOf<ScannerEvent>(ScannerEvent.Completed), events, "closed once")
    }

    /**
     * Audit A09: a frame still queued behind one with the engine is never read once a review came from elsewhere; the
     * camera closes when the frame with the store comes back (its call is never cancelled by the closing scanner).
     */
    @Test
    fun framesQueuedWhenAReviewArrivesFromElsewhereAreDroppedAndTheCameraClosesOnce() = runTest {
        val first = CompletableDeferred<Double>()
        store.onReceiveFrame = { frame -> if (frame == "A") first.await() else 0.5 }
        val vm = scanner()
        val events = eventsOf(vm)
        runCurrent()
        vm.receive("A"); vm.receive("B")
        runCurrent()
        store.setState { it.copy(review = SignFixtures.bitcoinSend) }
        runCurrent()
        assertEquals(emptyList(), events, "A is still with the store")
        first.complete(0.3)
        runCurrent()
        assertEquals(listOf<ScannerEvent>(ScannerEvent.Completed), events, "closed once A is back")
        assertEquals(listOf("A"), received(), "B would have cleared the imported review")
        assertEquals(1, store.calls<FakeSignerStore.Call.EndScan>().size)
        vm.receive("C")
        runCurrent()
        assertEquals(listOf("A"), received())
        assertEquals(listOf<ScannerEvent>(ScannerEvent.Completed), events, "closed once")
    }

    /**
     * The store publishes the review of the scanner's own last frame inside `receive`, before it matches the wallets
     * (`DefaultSignerStore.prepare`: review, then `resolveSigningPlan`). Closing the camera at the review pops the
     * scanner, which clears this ViewModel with the call still running: the matching was cancelled and the review
     * stayed on "Finding the matching wallet…" with no way to retry. The camera closes once the call is back (on iOS
     * the call is an unstructured task that the closing camera never cancels, `ScanTab.swift:68-81`).
     */
    @Test
    fun theCameraClosesOnItsOwnReviewOnlyOnceTheStoreHasMatchedIt() = runTest {
        val matching = CompletableDeferred<Unit>()
        var matched = false
        store.onReceiveFrame = {
            store.setState { it.copy(review = SignFixtures.bitcoinSend) }
            matching.await()
            matched = true
            1.0
        }
        val viewModels = ViewModelStore()
        val vm = ViewModelProvider.create(
            viewModels,
            viewModelFactory { initializer { ScannerViewModel(store, backgroundScope) } },
        )[ScannerViewModel::class]
        val events = mutableListOf<ScannerEvent>()
        // The scanner route pops on Completed, and its ViewModel is cleared with it.
        backgroundScope.launch { vm.events.collect { events += it; viewModels.clear() } }
        runCurrent()
        vm.receive("UR:CRYPTO-PSBT/LAST")
        runCurrent()
        assertEquals(emptyList(), events, "the camera stays while its request is being matched")
        matching.complete(Unit)
        runCurrent()
        assertTrue(matched, "the matching ran to its end")
        assertEquals(listOf<ScannerEvent>(ScannerEvent.Completed), events)
        assertEquals(1.0, vm.uiState.value.progress)
        assertEquals(1, store.calls<FakeSignerStore.Call.EndScan>().size)
    }

    /**
     * Backgrounded while the store matched the scanner's own review: the store reports the call as cancelled and keeps
     * the review ("Matching interrupted. Try again."). The camera closes onto it rather than staying up over a review
     * and ignoring every code (as before audit A09).
     */
    @Test
    fun aReviewInterruptedWhileItsFrameWasMatchedClosesTheCameraOntoIt() = runTest {
        val matching = CompletableDeferred<Double>()
        store.onReceiveFrame = {
            store.setState { it.copy(review = SignFixtures.bitcoinSend) }
            matching.await()
        }
        val vm = scanner()
        val events = eventsOf(vm)
        runCurrent()
        vm.receive("UR:CRYPTO-PSBT/LAST")
        runCurrent()
        store.suspendSession()
        matching.completeExceptionally(CancellationException("The session changed; the late result is discarded."))
        runCurrent()
        assertEquals(listOf<ScannerEvent>(ScannerEvent.Completed), events)
        assertNull(vm.uiState.value.error, "a cancellation is never shown")
        assertEquals(1, store.calls<FakeSignerStore.Call.EndScan>().size)
    }

    /** A camera that opens as an import publishes its review (the card is not disabled while busy) closes at once. */
    @Test
    fun aCameraOpenedOverAnExistingReviewClosesAtOnce() = runTest {
        store.setState { it.copy(review = SignFixtures.bitcoinSend) }
        val vm = scanner()
        val events = eventsOf(vm)
        runCurrent()
        assertEquals(listOf<ScannerEvent>(ScannerEvent.Completed), events)
        assertEquals(1, store.calls<FakeSignerStore.Call.EndScan>().size)
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
        assertEquals(UiText.Resource(R.string.reading_request), vm.uiState.value.status)
        gate.complete(0.25)
        vm.receive("2")
        runCurrent()
        assertEquals(UiText.Resource(R.string.keep_the_animated_code_in_view_x_parts, listOf(2)), vm.uiState.value.status)
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
