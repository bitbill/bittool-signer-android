import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.feature.sign.scanner.ScannerViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.cancellation.CancellationException

fun main() {
    Dispatchers.setMain(StandardTestDispatcher())
    try {
        runTest {
            val store = FakeSignerStore()
            val first = CompletableDeferred<Double>()
            store.onReceiveFrame = {
                if (store.calls<FakeSignerStore.Call.ReceiveFrame>().size == 1) first.await() else 0.5
            }
            val vm = ScannerViewModel(store, backgroundScope)
            runCurrent()
            vm.receive("STATIC-QR")
            runCurrent()
            store.suspendSession()
            first.completeExceptionally(CancellationException("Background invalidated the request"))
            runCurrent()
            repeat(5) { vm.receive("STATIC-QR"); runCurrent() }
            val calls = store.calls<FakeSignerStore.Call.ReceiveFrame>().map { it.frame }
            check(calls == listOf("STATIC-QR"))
            check(vm.uiState.value.progress == 0.0 && !vm.uiState.value.processing && vm.uiState.value.error == null)
            println("STALL CONFIRMED: repeated static frames=5, store calls=$calls, state=${vm.uiState.value}")
            vm.receive("DIFFERENT-FRAME")
            runCurrent()
            val resumed = store.calls<FakeSignerStore.Call.ReceiveFrame>().map { it.frame }
            check(resumed == listOf("STATIC-QR", "STATIC-QR", "DIFFERENT-FRAME"))
            println("CONTROL: different frame drains queue, store calls=$resumed")
        }
    } finally { Dispatchers.resetMain() }
}
