package com.bittool.signer.feature.activity

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.testing.FakeSignerStore
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.AppIssue
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.signing.SigningReceipt
import com.bittool.signer.feature.activity.ActivityTestData.receipt
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Signature page (iOS `ActivityDetailView`, activity-settings.md §2.4–§2.5): the fresh re-review of the stored
 * request, the metadata rows and the export. Robolectric only for `android.net.Uri`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ActivityDetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val id = "00000000-0000-4000-8000-0000000000aa"

    private fun store(vararg receipts: SigningReceipt, loading: Boolean = false) = FakeSignerStore(
        SignerState(
            isLoading = loading,
            walletLoadSucceeded = !loading,
            wallets = listOf(ActivityTestData.wallet),
            receipts = receipts.toList(),
        ),
    )

    private fun viewModel(store: FakeSignerStore, writer: TextDocumentWriter = TextDocumentWriter { _, _ -> }) =
        ActivityDetailViewModel(SavedStateHandle(mapOf(ActivityDetailViewModel.RECEIPT_ID to id)), store, writer)

    @Test
    fun theStoredRequestIsReviewedAgainOnceWithItsFingerprint() = runTest {
        val store = store(receipt(id))
        store.onReviewStoredRequest = { _, _ -> ActivityTestData.approvalReview }
        val viewModel = viewModel(store)
        val state = viewModel.uiState.first { it.decode is DecodeState.Decoded }
        val call = store.calls<FakeSignerStore.Call.ReviewStoredRequest>().single()
        assertEquals(ActivityTestData.REQUEST_JSON, call.json)
        assertEquals(ActivityTestData.HASH, call.expectedRequestHash)
        // The shared review: "Signed Request" reads it from this ViewModel, no second engine call.
        assertEquals(ActivityTestData.approvalReview, (state.decode as DecodeState.Decoded).review)
        viewModel.uiState.first()
        assertEquals(1, store.calls<FakeSignerStore.Call.ReviewStoredRequest>().size)
    }

    @Test
    fun theDecodedEssentialsNameTheActionAmountAndCounterparty() = runTest {
        // ActivityRecordTests.theDecodedEssentialsNameTheActionAmountAndCounterparty (model part on the page's state).
        val decoded = DecodeState.Decoded(ActivityTestData.approvalReview)
        assertEquals(listOf("action", "amount", "counterparty", "fee"), decoded.essentials.map { it.id })
        assertEquals("Unlimited USDT", decoded.essentials[1].value)
        assertTrue(decoded.essentials[2].value.startsWith("0x0000") && "…" in decoded.essentials[2].value)
        assertEquals("Spender", decoded.essentials[2].label)
        assertEquals("0.0012 ETH", decoded.essentials[3].value)
        assertEquals(listOf("Send bitcoin"), DecodeState.Decoded(ActivityTestData.plainReview).essentials.map { it.value })
    }

    @Test
    fun aMismatchingRequestShowsTheStoreMessage() = runTest {
        // AppStore.swift:581-591: the fingerprint must still match; the store's message is shown as is.
        val store = store(receipt(id))
        store.onReviewStoredRequest = { _, _ -> throw AppIssue("The stored request does not match this signature's fingerprint.") }
        val state = viewModel(store).uiState.first { it.decode is DecodeState.Failed }
        assertEquals(
            UiText.Ios("The stored request does not match this signature's fingerprint."),
            (state.decode as DecodeState.Failed).message,
        )
    }

    @Test
    fun aFailedReReviewIsTriedAgainWhenThePageAppearsAgain() = runTest {
        // iOS `.task(id:)` runs on every appearance while nothing is decoded (ActivityTab.swift:168, 269-275): coming
        // back to the page (another tab and back) tries a failed re-review once more. A decoded one is never repeated.
        val store = store(receipt(id))
        var attempts = 0
        store.onReviewStoredRequest = { _, _ ->
            attempts++
            if (attempts == 1) throw AppIssue("The stored request could not be read.") else ActivityTestData.plainReview
        }
        val viewModel = viewModel(store)
        assertIs<DecodeState.Failed>(viewModel.uiState.first { it.decode !is DecodeState.Decoding }.decode)
        viewModel.onAppear()
        assertIs<DecodeState.Decoded>(viewModel.uiState.first { it.decode is DecodeState.Decoded }.decode)
        viewModel.onAppear()
        viewModel.uiState.first()
        assertEquals(2, store.calls<FakeSignerStore.Call.ReviewStoredRequest>().size)
    }

    @Test
    fun withoutADocumentPickerTheExportSaysTheFileCouldNotBeSaved() = runTest {
        val store = store(receipt(id))
        store.onReviewStoredRequest = { _, _ -> ActivityTestData.plainReview }
        val viewModel = viewModel(store)
        viewModel.onExportUnavailable()
        assertEquals(UiText.Resource(R.string.activity_export_failed), viewModel.uiState.first { it.exportError != null }.exportError)
    }

    @Test
    fun aRecordWithoutPayloadsHasNothingToDecode() = runTest {
        // Only the 25 most recent records keep their request (ActivityTab.swift:157-162).
        val store = store(receipt(id, requestJson = null, frames = null, text = null))
        val state = viewModel(store).uiState.first { it.receipt != null }
        assertEquals(DecodeState.NotStored, state.decode)
        assertTrue(!state.receipt!!.hasPayloads)
        assertTrue(!state.hasOutput)
        assertTrue(store.calls<FakeSignerStore.Call.ReviewStoredRequest>().isEmpty())
    }

    /**
     * "Signed Request" is open on the Activity tab while a new signature is made on the Scan tab; the record is now past
     * the 25 most recent and loses its request. The decoded review stays (iOS keeps the value the page was opened with)
     * instead of the page turning into a spinner that never ends.
     */
    @Test
    fun aDecodedRequestStaysWhenTheRecordLaterLosesItsPayloads() = runTest {
        val store = store(receipt(id))
        store.onReviewStoredRequest = { _, _ -> ActivityTestData.approvalReview }
        val viewModel = viewModel(store)
        viewModel.uiState.first { it.decode is DecodeState.Decoded }
        viewModel.uiState.test {
            assertIs<DecodeState.Decoded>(awaitItem().decode)
            store.setState { it.copy(receipts = listOf(receipt(id).withoutPayloads())) }
            val shed = awaitItem()
            assertTrue(shed.receipt?.hasPayloads == false)
            assertEquals(DecodeState.Decoded(ActivityTestData.approvalReview), shed.decode)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, store.calls<FakeSignerStore.Call.ReviewStoredRequest>().size)
    }

    @Test
    fun decodingWaitsForTheStoreToLoad() = runTest {
        val store = store(loading = true)
        store.onReviewStoredRequest = { _, _ -> ActivityTestData.plainReview }
        val viewModel = viewModel(store)
        viewModel.detailEvents.test {
            expectNoEvents()
            store.setState { it.copy(isLoading = false, receipts = listOf(receipt(id))) }
            expectNoEvents()
        }
        assertIs<DecodeState.Decoded>(viewModel.uiState.first { it.decode is DecodeState.Decoded }.decode)
    }

    @Test
    fun thePageClosesWhenItsRecordIsGone() = runTest {
        // A cleared journal or a restored back stack: destinations whose id no longer exists pop themselves.
        val store = store(receipt(id))
        store.onReviewStoredRequest = { _, _ -> ActivityTestData.plainReview }
        val viewModel = viewModel(store)
        viewModel.detailEvents.test {
            store.setState { it.copy(receipts = emptyList()) }
            assertEquals(ActivityDetailEvent.Close, awaitItem())
        }
    }

    @Test
    fun metadataRowsFollowTheRecord() = runTest {
        val store = store(receipt(id, riskLevel = "high", masterFingerprint = "73c5da0a"))
        store.onReviewStoredRequest = { _, _ -> ActivityTestData.plainReview }
        var state = viewModel(store).uiState.first { it.receipt != null }
        assertEquals("73C5DA0A", state.fingerprint)
        assertEquals(RiskRow.High, state.risk)
        assertEquals(WalletLabel.Named("Public fixture 1"), state.wallet)

        store.setState { it.copy(receipts = listOf(receipt(id, riskLevel = "warning")), wallets = emptyList()) }
        state = viewModel(store).uiState.first { it.receipt?.riskLevel == "warning" }
        assertEquals(RiskRow.WarningsShown, state.risk)
        assertEquals(WalletLabel.Deleted, state.wallet)

        store.setState { it.copy(receipts = listOf(receipt(id, riskLevel = "normal"))) }
        assertNull(viewModel(store).uiState.first { it.receipt?.riskLevel == "normal" }.risk)
    }

    @Test
    fun theSignatureQrIsShownOnlyWhenFramesWereKept() = runTest {
        val store = store(receipt(id, frames = null))
        store.onReviewStoredRequest = { _, _ -> ActivityTestData.plainReview }
        val viewModel = viewModel(store)
        viewModel.uiState.test {
            assertTrue(!awaitItem().showsQr)
            viewModel.onShowQr()
            expectNoEvents()
            assertTrue(!viewModel.uiState.value.showsQr)
            store.setState { it.copy(receipts = listOf(receipt(id))) }
            assertTrue(!awaitItem().showsQr, "an earlier tap without frames does not open it later")
            viewModel.onShowQr()
            assertTrue(awaitItem().showsQr, "with frames the sheet opens")
            viewModel.onDismissQr()
            assertTrue(!awaitItem().showsQr)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun exportWritesTheStoredSignatureText() = runTest {
        val written = CompletableDeferred<Pair<Uri, String>>()
        val store = store(receipt(id))
        store.onReviewStoredRequest = { _, _ -> ActivityTestData.plainReview }
        val viewModel = viewModel(store) { uri, text -> written.complete(uri to text) }
        val uri = Uri.parse("content://com.example.documents/document/1")
        viewModel.onExport(uri)
        assertEquals(uri to receipt(id).responseText, written.await())
        assertNull(viewModel.uiState.first().exportError)
    }

    /**
     * Android may end the process while the system file picker is open; its answer then reaches a new ViewModel while
     * the store is still reading the journal. The stored signature is written once the record is loaded, instead of
     * being dropped and the document the person just created left empty.
     */
    @Test
    fun anExportAnsweredAfterTheProcessWasRestartedWaitsForTheRecord() = runTest {
        val written = CompletableDeferred<Pair<Uri, String>>()
        val store = store(loading = true)
        store.onReviewStoredRequest = { _, _ -> ActivityTestData.plainReview }
        val viewModel = viewModel(store) { uri, text -> written.complete(uri to text) }
        val uri = Uri.parse("content://com.example.documents/document/1")
        viewModel.onExport(uri)
        assertTrue(!written.isCompleted)
        store.setState { it.copy(isLoading = false, walletLoadSucceeded = true, receipts = listOf(receipt(id))) }
        assertEquals(uri to receipt(id).responseText, withTimeout(5_000) { written.await() })
        assertNull(viewModel.uiState.value.exportError)
    }

    @Test
    fun anExportWhoseSignatureAgedOutMeanwhileSaysTheFileWasNotSaved() = runTest {
        // Past the 25 most recent records only the summary remains (activity-settings.md §2.1).
        var writes = 0
        val store = store(receipt(id).withoutPayloads())
        val viewModel = viewModel(store) { _, _ -> writes++ }
        viewModel.onExport(Uri.parse("content://x/1"))
        assertEquals(0, writes)
        assertEquals(UiText.Resource(R.string.activity_export_failed), withTimeout(5_000) { viewModel.uiState.first { it.exportError != null } }.exportError)
    }

    @Test
    fun aCancelledPickerWritesNothing() = runTest {
        var writes = 0
        val store = store(receipt(id))
        store.onReviewStoredRequest = { _, _ -> ActivityTestData.plainReview }
        viewModel(store) { _, _ -> writes++ }.onExport(null)
        assertEquals(0, writes)
    }

    @Test
    fun anExportFailureIsShownUnderTheButtons() = runTest {
        val store = store(receipt(id))
        store.onReviewStoredRequest = { _, _ -> ActivityTestData.plainReview }
        val withReason = viewModel(store) { _, _ -> throw IOException("No space left on device") }
        withReason.onExport(Uri.parse("content://x/1"))
        assertEquals(UiText.Verbatim("No space left on device"), withReason.uiState.first { it.exportError != null }.exportError)

        val withoutReason = viewModel(store) { _, _ -> throw IOException() }
        withoutReason.onExport(Uri.parse("content://x/1"))
        assertEquals(UiText.Resource(R.string.activity_export_failed), withoutReason.uiState.first { it.exportError != null }.exportError)
    }
}
