@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.model.scanning.QrScanFrameGate
import com.bittool.signer.feature.sign.scanner.ScannerViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlin.coroutines.cancellation.CancellationException

private fun FakeSignerStore.frames() = calls<FakeSignerStore.Call.ReceiveFrame>().map { it.frame }

fun main() {
    Dispatchers.setMain(StandardTestDispatcher())
    try {
        runTest {
            val store = FakeSignerStore()
            val first = CompletableDeferred<Double>()
            store.onReceiveFrame = { if (store.frames().size == 1) first.await() else 0.6 }
            val vm = ScannerViewModel(store, backgroundScope)
            val cameraGate = QrScanFrameGate()
            fun cameraFrame(atMillis: Long) {
                val outcome = cameraGate.receive("STATIC-QR", atMillis)
                if (outcome is QrScanFrameGate.Outcome.Accepted) vm.receive(outcome.text)
            }
            runCurrent()
            cameraFrame(0)
            runCurrent()
            store.suspendSession()
            first.completeExceptionally(CancellationException("Background invalidated request"))
            runCurrent()
            repeat(5) { cameraFrame((it + 1) * 1000L); runCurrent() }
            check(store.frames() == listOf("STATIC-QR", "STATIC-QR")) { "Static retry missing or duplicated: ${store.frames()}" }
            check(vm.uiState.value.progress == 0.6 && vm.uiState.value.received == 1 && vm.uiState.value.error == null)
            println("PASS static-QR retry through camera gate: calls=${store.frames()}, received=${vm.uiState.value.received}")
            vm.stop()
            runCurrent()
        }
        runTest {
            val store = FakeSignerStore()
            val first = CompletableDeferred<Double>()
            val second = CompletableDeferred<Double>()
            store.onReceiveFrame = { if (it == "A") first.await() else second.await() }
            val vm = ScannerViewModel(store, backgroundScope)
            runCurrent()
            vm.receive("A"); vm.receive("B"); repeat(5) { vm.receive("B") }
            runCurrent()
            check(store.frames() == listOf("A"))
            first.complete(0.3)
            runCurrent()
            repeat(5) { vm.receive("B"); runCurrent() }
            check(store.frames() == listOf("A", "B"))
            second.complete(0.8)
            runCurrent()
            check(vm.uiState.value.received == 2 && !vm.uiState.value.processing)
            println("PASS duplicate queued/in-flight frame not replayed: calls=${store.frames()}")
            vm.stop()
            runCurrent()
        }
        runTest {
            val store = FakeSignerStore()
            val initial = CompletableDeferred<Double>()
            val retry = CompletableDeferred<Double>()
            store.onReceiveFrame = { frame ->
                when { store.frames().size == 1 -> initial.await(); frame == "A" -> retry.await(); else -> 0.8 }
            }
            val vm = ScannerViewModel(store, backgroundScope)
            runCurrent()
            vm.receive("A"); vm.receive("B")
            runCurrent()
            store.suspendSession()
            initial.completeExceptionally(CancellationException("Background invalidated request"))
            runCurrent()
            repeat(5) { vm.receive("B"); runCurrent() }
            check(store.frames() == listOf("A", "A"))
            retry.complete(0.5)
            runCurrent()
            check(store.frames() == listOf("A", "A", "B"))
            check(vm.uiState.value.received == 2 && vm.uiState.value.progress == 0.8)
            println("PASS queued duplicate resumes interrupted first frame in FIFO order: calls=${store.frames()}")
            vm.stop()
            runCurrent()
        }
        runTest {
            val store = FakeSignerStore()
            val initial = CompletableDeferred<Double>()
            store.onReceiveFrame = { initial.await() }
            val vm = ScannerViewModel(store, backgroundScope)
            runCurrent()
            vm.receive("A")
            runCurrent()
            store.suspendSession()
            vm.stop()
            initial.completeExceptionally(CancellationException("Background invalidated request"))
            runCurrent()
            repeat(5) { vm.receive("A"); runCurrent() }
            check(store.frames() == listOf("A"))
            check(store.calls<FakeSignerStore.Call.EndScan>().size == 1)
            println("PASS late result cannot restart stopped scanner: calls=${store.frames()}")
        }
    } finally { Dispatchers.resetMain() }
}
