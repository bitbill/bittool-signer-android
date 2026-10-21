package com.bittool.signer.feature.sign.result

import android.net.Uri
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.signing.SigningReceipt
import com.bittool.signer.feature.sign.SignFixtures
import com.bittool.signer.feature.sign.common.SignatureFileWriter
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The signature result's export and Done (scan-sign.md §15; iOS `SignatureResultView`). */
@RunWith(RobolectricTestRunner::class)
class SignatureResultViewModelTest {
    private val text = SignFixtures.responseFrames.joinToString("\n")
    private val store = FakeSignerStore(
        SignFixtures.walletsState().copy(
            review = SignFixtures.bitcoinSend,
            signatureFrames = SignFixtures.responseFrames,
            signatureText = text,
            signaturePayload = SignFixtures.psbtPayload,
        ),
    )
    private val written = mutableListOf<Pair<Uri, String>>()
    private var failure: Exception? = null
    private val writer = SignatureFileWriter { uri, value ->
        failure?.let { throw it }
        written += uri to value
    }
    private val uri = Uri.parse("content://documents/bittool-signer-signature.txt")

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.result() = SignatureResultViewModel(store, writer).also { vm ->
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
    }

    @Test
    fun theResponseIsExportedAsTheFramesJoinedByNewlines() = runTest {
        val vm = result()
        assertEquals(SignFixtures.responseFrames, vm.uiState.value.frames)
        assertTrue(vm.uiState.value.showsDetails)
        assertTrue(vm.uiState.value.payload!!.isPsbt, "the psbt_base64 note is shown")
        vm.export(uri)
        runCurrent()
        assertEquals(listOf(uri to text), written)
        assertNull(vm.uiState.value.exportError)
        assertEquals("bittool-signer-signature.txt", SignatureResultViewModel.EXPORT_FILE_NAME)
        assertEquals("text/plain", SignatureResultViewModel.EXPORT_MIME_TYPE)
    }

    @Test
    fun aCancelledPickerWritesNothing() = runTest {
        val vm = result()
        vm.export(null)
        runCurrent()
        assertTrue(written.isEmpty())
    }

    @Test
    fun aFailedExportShowsTheSystemMessageOrAFallback() = runTest {
        val vm = result()
        failure = IOException("No space left on device")
        vm.export(uri)
        runCurrent()
        assertEquals(UiText.Verbatim("No space left on device"), vm.uiState.value.exportError)
        failure = IOException()
        vm.export(uri)
        runCurrent()
        assertEquals(UiText.Resource(R.string.sign_file_not_saved), vm.uiState.value.exportError)
        failure = null
        vm.export(uri)
        runCurrent()
        assertNull(vm.uiState.value.exportError, "a later export starts clean")
    }

    @Test
    fun doneDropsTheRequestAndTheExportError() = runTest {
        val vm = result()
        failure = IOException("Denied")
        vm.export(uri)
        runCurrent()
        vm.done()
        runCurrent()
        assertEquals(1, store.calls<FakeSignerStore.Call.ClearRequest>().size)
        assertEquals(false, store.state.value.hasSignedResponse)
        assertNull(vm.uiState.value.exportError)
    }

    /** An Activity record of a signature created on this device, keeping [exportText] as its export text. */
    private fun record(id: String, exportText: String?) = SigningReceipt(
        id = id,
        date = 1_760_000_000_000,
        chain = Chain.Bitcoin,
        title = "Sign Bitcoin transaction",
        requestHash = SignFixtures.REQUEST_HASH,
        resultType = SigningReceipt.RESULT_SIGNATURE,
        responseFrames = exportText?.lines(),
        responseText = exportText,
    )

    /**
     * The picker was opened from [store]'s result page (what it exports is kept in saved state); then Android ended
     * the process. The answer reaches a new ViewModel on [restarted] (a new store) with that saved digest.
     */
    private fun TestScope.restartedWhilePickerOpen(restarted: FakeSignerStore): Pair<SignatureResultViewModel, String?> {
        val exported = collected(SignatureResultViewModel(store, writer)).prepareExport()
        assertEquals(64, exported?.length, "a SHA-256 in hex")
        return collected(SignatureResultViewModel(restarted, writer)) to exported
    }

    private fun TestScope.collected(vm: SignatureResultViewModel) = vm.also {
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
    }

    /**
     * Audit A14: the document picker is another app, and Android may end this process while it is open. The signature
     * lived in memory only; the document the person just created is written from the signature's Activity record once
     * the journal is read again, instead of staying empty.
     */
    @Test
    fun anExportAnsweredInANewProcessWritesTheSignatureFromItsActivityRecord() = runTest {
        val restarted = FakeSignerStore(SignerState(isLoading = true))
        val (vm, exported) = restartedWhilePickerOpen(restarted)
        vm.export(uri, exported)
        runCurrent()
        assertTrue(written.isEmpty(), "nothing is written before Activity is read")
        restarted.setState {
            it.copy(isLoading = false, receipts = listOf(record("2", "UR:CRYPTO-PSBT/OTHER-SIGNATURE"), record("1", text)))
        }
        runCurrent()
        assertEquals(listOf(uri to text), written, "the same signature, from its record")
        assertTrue(restarted.calls<FakeSignerStore.Call.SetError>().isEmpty())
    }

    /** Audit A14: without the signature kept in Activity, the person is told the file was not saved. */
    @Test
    fun anExportAnsweredInANewProcessWithoutAKeptSignatureSaysTheFileWasNotSaved() = runTest {
        val restarted = FakeSignerStore(SignerState(isLoading = true))
        val (vm, exported) = restartedWhilePickerOpen(restarted)
        vm.export(uri, exported)
        // The signature was too large to keep (its record has no export text); the other record is another signature.
        restarted.setState {
            it.copy(isLoading = false, receipts = listOf(record("2", "UR:CRYPTO-PSBT/OTHER-SIGNATURE"), record("1", null)))
        }
        runCurrent()
        assertTrue(written.isEmpty())
        assertEquals(
            listOf(FakeSignerStore.Call.SetError(UiText.Resource(R.string.sign_file_not_saved))),
            restarted.calls<FakeSignerStore.Call.SetError>(),
            "said in the app's alert: the result page is gone",
        )
    }

    /** Audit A14: a write that fails in the new process says why in the app's alert. */
    @Test
    fun aFailedWriteInANewProcessIsShownInTheAppsAlert() = runTest {
        val restarted = FakeSignerStore(SignerState(isLoading = false, receipts = listOf(record("1", text))))
        val (vm, exported) = restartedWhilePickerOpen(restarted)
        failure = IOException("No space left on device")
        vm.export(uri, exported)
        runCurrent()
        assertEquals(
            listOf(FakeSignerStore.Call.SetError(UiText.Verbatim("No space left on device"))),
            restarted.calls<FakeSignerStore.Call.SetError>(),
        )
        assertNull(vm.uiState.value.exportError)
    }

    /** A picker cancelled in the new process writes nothing and says nothing. */
    @Test
    fun aPickerCancelledInANewProcessSaysNothing() = runTest {
        val restarted = FakeSignerStore(SignerState(isLoading = false, receipts = listOf(record("1", text))))
        val (vm, exported) = restartedWhilePickerOpen(restarted)
        vm.export(null, exported)
        runCurrent()
        assertTrue(written.isEmpty())
        assertTrue(restarted.calls<FakeSignerStore.Call.SetError>().isEmpty())
    }

    @Test
    fun aPageThatComesBackShowsTheCurrentSignature() = runTest {
        // The result page leaves composition on another tab; coming back it must not start from a frozen value
        // (the previous request's signature, or none).
        val vm = SignatureResultViewModel(store, writer)
        val page = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        page.cancel()
        advanceTimeBy(60_000)
        store.setState { it.copy(signatureFrames = listOf("UR:ETH-SIGNATURE/NEXT"), signatureText = "UR:ETH-SIGNATURE/NEXT") }
        runCurrent()
        assertEquals(listOf("UR:ETH-SIGNATURE/NEXT"), vm.uiState.value.frames)
        assertEquals("UR:ETH-SIGNATURE/NEXT", vm.uiState.value.text)
    }
}
