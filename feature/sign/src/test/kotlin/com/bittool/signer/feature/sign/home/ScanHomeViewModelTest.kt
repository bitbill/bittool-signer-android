package com.bittool.signer.feature.sign.home

import android.net.Uri
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText
import com.bittool.signer.feature.sign.SignFixtures
import com.bittool.signer.feature.sign.common.RequestFileReader
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Scan home, paste and file import (scan-sign.md §3–§5; iOS `ScanHomeView`, `PasteRequestSheet`). */
@RunWith(RobolectricTestRunner::class)
class ScanHomeViewModelTest {
    private val store = FakeSignerStore(SignFixtures.walletsState())
    private var file: suspend (Uri) -> String = { "UR:CRYPTO-PSBT/FILE" }
    private val reader = RequestFileReader { uri -> file(uri) }
    private val uri = Uri.parse("content://documents/request.psbt")

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun home() = ScanHomeViewModel(store, reader)

    private fun ScanHomeViewModel.type(text: String) = pasteText.edit { replace(0, length, text) }

    @Test
    fun reviewRequestStaysDisabledUntilTheTextIsNotBlank() = runTest {
        val vm = home()
        vm.openPaste()
        val paste = assertNotNull(vm.uiState.value.paste)
        assertFalse(vm.canSubmitPaste(paste))
        vm.type(" \n\t ")
        assertFalse(vm.canSubmitPaste(paste), "whitespace and newlines only")
        vm.submitPaste()
        runCurrent()
        assertTrue(store.calls<FakeSignerStore.Call.ImportPayload>().isEmpty())
        vm.type("UR:ETH-SIGN-REQUEST/OSADTPDA")
        assertTrue(vm.canSubmitPaste(paste))
    }

    @Test
    fun aPastedRequestIsImportedExactlyAndTheSheetClosesOnSuccess() = runTest {
        val vm = home()
        runCurrent()
        vm.openPaste()
        vm.type("  UR:ETH-SIGN-REQUEST/1-2/A\nUR:ETH-SIGN-REQUEST/2-2/B\n")
        vm.submitPaste()
        assertEquals(true, vm.uiState.value.paste?.busy)
        runCurrent()
        assertEquals(
            listOf("  UR:ETH-SIGN-REQUEST/1-2/A\nUR:ETH-SIGN-REQUEST/2-2/B\n"),
            store.calls<FakeSignerStore.Call.ImportPayload>().map { it.text },
            "the text goes to the engine unchanged (it trims and splits lines itself)",
        )
        assertNull(vm.uiState.value.paste)
        assertEquals("", vm.pasteText.text.toString())
    }

    @Test
    fun aFailedImportShowsItsMessageInTheSheet() = runTest {
        store.onImportPayload = { throw IllegalStateException("Unsupported signing QR format") }
        val vm = home()
        runCurrent()
        vm.openPaste()
        vm.type("unsupported-qr-test-fixture")
        vm.submitPaste()
        runCurrent()
        val paste = assertNotNull(vm.uiState.value.paste)
        assertEquals(UiText.Verbatim("Unsupported signing QR format"), paste.error)
        assertFalse(paste.busy)
        assertEquals("unsupported-qr-test-fixture", vm.pasteText.text.toString(), "the text stays for another try")
    }

    @Test
    fun aFailureAfterBackgroundingIsSilentAndBusyEndsAtOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        store.onImportPayload = { gate.await() }
        val vm = home()
        runCurrent()
        vm.openPaste()
        vm.type("UR:BYTES/X")
        vm.submitPaste()
        runCurrent()
        assertEquals(true, vm.uiState.value.paste?.busy)
        store.suspendSession()
        runCurrent()
        assertEquals(false, vm.uiState.value.paste?.busy, "a new security epoch ends busy")
        gate.completeExceptionally(IllegalStateException("late"))
        runCurrent()
        assertNull(vm.uiState.value.paste?.error, "a late failure is not shown")
    }

    @Test
    fun cancellingDiscardsTheTextAndALaterResult() = runTest {
        val gate = CompletableDeferred<Unit>()
        store.onImportPayload = { gate.await() }
        val vm = home()
        runCurrent()
        vm.openPaste()
        vm.type("UR:BYTES/X")
        vm.submitPaste()
        runCurrent()
        vm.dismissPaste()
        assertNull(vm.uiState.value.paste)
        assertEquals("", vm.pasteText.text.toString())
        gate.completeExceptionally(IllegalStateException("late"))
        runCurrent()
        assertNull(vm.uiState.value.paste)
        vm.openPaste()
        assertEquals(PasteUiState(), vm.uiState.value.paste, "a new sheet starts empty")
    }

    @Test
    fun pasteFromClipboardReplacesTheTextAndIgnoresAnEmptyClipboard() = runTest {
        val vm = home()
        vm.openPaste()
        vm.type("old")
        vm.pasteClipboard(null)
        assertEquals("old", vm.pasteText.text.toString())
        vm.pasteClipboard("UR:CRYPTO-PSBT/NEW")
        assertEquals("UR:CRYPTO-PSBT/NEW", vm.pasteText.text.toString())
    }

    @Test
    fun aReviewedRequestClosesTheSheetAndClearsHome() = runTest {
        store.onImportPayload = { store.setState { it.copy(review = SignFixtures.bitcoinSend) } }
        file = { throw AppIssue("This file is larger than 512 KB.") }
        val vm = home()
        runCurrent()
        vm.importFile(uri)
        runCurrent()
        assertEquals(UiText.Ios("This file is larger than 512 KB."), vm.uiState.value.error)
        vm.openPaste()
        vm.type("UR:BYTES/OK")
        vm.submitPaste()
        runCurrent()
        assertNull(vm.uiState.value.paste)
        assertNull(vm.uiState.value.error, "Home is rebuilt after the review on iOS")
    }

    @Test
    fun anImportedFileIsSubmittedAndTheTilesAreDisabledMeanwhile() = runTest {
        val gate = CompletableDeferred<String>()
        file = { gate.await() }
        val vm = home()
        runCurrent()
        vm.importFile(uri)
        assertTrue(vm.uiState.value.busy, "Paste Request and Import File are disabled")
        vm.importFile(uri)
        vm.openPaste()
        assertNull(vm.uiState.value.paste, "nothing else starts while busy")
        gate.complete("cHNidP8BAHECAAAAAQ==")
        runCurrent()
        assertEquals(listOf("cHNidP8BAHECAAAAAQ=="), store.calls<FakeSignerStore.Call.ImportPayload>().map { it.text })
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun aCancelledPickerDoesNothingAndUnreadableFilesExplainThemselves() = runTest {
        val vm = home()
        runCurrent()
        vm.importFile(null)
        assertFalse(vm.uiState.value.busy)
        file = { throw SignerException(UiText.Resource(R.string.sign_file_unreadable)) }
        vm.importFile(uri)
        runCurrent()
        assertEquals(UiText.Resource(R.string.sign_file_unreadable), vm.uiState.value.error)
        assertTrue(store.calls<FakeSignerStore.Call.ImportPayload>().isEmpty())
    }

    @Test
    fun openingTheCameraClearsTheImportError() = runTest {
        file = { throw AppIssue("Choose a PSBT file or a text file containing a UR request.") }
        val vm = home()
        runCurrent()
        vm.importFile(uri)
        runCurrent()
        assertNotNull(vm.uiState.value.error)
        vm.onOpenScanner()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun aCancellationIsNeverShown() = runTest {
        store.onImportPayload = { throw CancellationException("The session changed; the late result is discarded.") }
        val vm = home()
        runCurrent()
        vm.importFile(uri)
        runCurrent()
        assertNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.busy)
    }
}
