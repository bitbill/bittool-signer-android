package com.bittool.signer.feature.sign.result

import android.net.Uri
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
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
