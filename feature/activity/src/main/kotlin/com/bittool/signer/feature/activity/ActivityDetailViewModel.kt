package com.bittool.signer.feature.activity

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.review.ActivityEssential
import com.bittool.signer.core.model.review.RiskLevel
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.signing.SigningReceipt
import com.bittool.signer.core.model.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The fresh re-review of a stored request (activity-settings.md §2.5). */
@Immutable
sealed interface DecodeState {
    /** The record kept no request (older than the 25 most recent, or too large to store). */
    data object NotStored : DecodeState

    /** "Decoding again…" */
    data object Decoding : DecodeState

    /** The engine decoded it again and the fingerprint matched. */
    data class Decoded(val review: TransactionReview) : DecodeState {
        val essentials: List<ActivityEssential> get() = ActivityEssential.of(review)
    }

    /** Unreadable, not matching, or an engine error: shown verbatim / translated like iOS `localizedDescription`. */
    data class Failed(val message: UiText) : DecodeState
}

/** The "Risk" row (iOS: only when the stored level is present and not `normal`). */
enum class RiskRow { High, WarningsShown }

/** What the Signature page draws (iOS `ActivityDetailView`). */
@Immutable
data class ActivityDetailUiState(
    /** Null until the store finished loading (then the page closes itself if the record is gone). */
    val receipt: SigningReceipt? = null,
    val wallet: WalletLabel? = null,
    /** Upper-case master fingerprint: the stored one, else the wallet's current index. */
    val fingerprint: String? = null,
    val risk: RiskRow? = null,
    val decode: DecodeState = DecodeState.Decoding,
    val showsQr: Boolean = false,
    val exportError: UiText? = null,
) {
    val frames: List<String> get() = receipt?.responseFrames.orEmpty()
    val responseText: String get() = receipt?.responseText.orEmpty()
    val hasOutput: Boolean get() = receipt != null && (receipt.responseText != null || frames.isNotEmpty())
}

sealed interface ActivityDetailEvent {
    /** The record no longer exists (Activity cleared, restored back stack): the page pops itself. */
    data object Close : ActivityDetailEvent
}

/**
 * One signature as it was made (iOS `ActivityDetailView`, activity-settings.md §2.4–§2.7): metadata, the stored request
 * decoded **again** on this device — a fresh engine review whose `requestHash` must equal the stored fingerprint
 * (`SignerStore.reviewStoredRequest`; no password, no matching, never touches a pending request) — and the stored
 * signature (QR again, export, raw text). The decoded review is held here and shared with "Signed Request", which reads
 * this ViewModel from the detail's back-stack entry, so the engine runs once per visit.
 *
 * The receipt id comes from the route argument `receiptId` (both `ActivityDetailRoute` and `SignedRequestRoute` carry
 * it), never a payload.
 */
@HiltViewModel
class ActivityDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: SignerStore,
    private val writer: TextDocumentWriter,
) : ViewModel() {

    val receiptId: String = checkNotNull(savedStateHandle.get<String>(RECEIPT_ID)) { "receiptId argument missing" }

    private val decode = MutableStateFlow<DecodeState>(DecodeState.Decoding)
    private val local = MutableStateFlow(Local())

    private val events = Channel<ActivityDetailEvent>(Channel.BUFFERED)
    val detailEvents: Flow<ActivityDetailEvent> = events.receiveAsFlow()

    val uiState: StateFlow<ActivityDetailUiState> = combine(store.state, decode, local) { state, decoded, local ->
        build(state, decoded, local)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(store.state.value, decode.value, local.value))

    /** The re-review in flight (the first one, or a retry from [onAppear]). */
    private var decodeJob: Job? = null

    init {
        decodeJob = viewModelScope.launch {
            val loaded = store.state.first { !it.isLoading }
            loaded.receipts.firstOrNull { it.id == receiptId }?.let { decodeAgain(it) }
        }
        viewModelScope.launch {
            store.state
                .map { !it.isLoading && it.receipts.none { r -> r.id == receiptId } }
                .distinctUntilChanged()
                .collect { gone -> if (gone) events.send(ActivityDetailEvent.Close) }
        }
    }

    /**
     * The page appears again (iOS `.task(id:)` runs on every appearance): a re-review that failed is tried once more.
     * The failure stays on screen meanwhile, as on iOS, and is replaced only by the decoded request or a new failure.
     * A decoded request is kept ("Signed Request" shares it) and nothing runs twice at once.
     */
    fun onAppear() {
        if (decodeJob?.isActive == true || decode.value !is DecodeState.Failed) return
        val receipt = currentReceipt() ?: return
        decodeJob = viewModelScope.launch { decodeAgain(receipt, showProgress = false) }
    }

    /** "Show QR Code": only a record that kept its signature frames has a code to show again. */
    fun onShowQr() {
        if (currentReceipt()?.responseFrames?.isNotEmpty() == true) local.update { it.copy(showsQr = true) }
    }

    fun onDismissQr() = local.update { it.copy(showsQr = false) }

    /** "Export as File" found no document picker on this device (no app handles `CREATE_DOCUMENT`): say so in place. */
    fun onExportUnavailable() = local.update { it.copy(exportError = UiText.Resource(R.string.activity_export_failed)) }

    /**
     * The file picker returned [uri] (null: cancelled, nothing happens). Writes the stored response text; a failure is
     * shown under the buttons, with the provider's own reason when it gives one (iOS `error.localizedDescription`).
     *
     * The picker is another app: while it is open Android may end this process, and the answer then reaches a new
     * ViewModel before the store has read the journal again. The text is taken once the store has loaded, so the
     * document the person just created is written rather than left empty; a record that is gone by then (or kept no
     * signature) is reported as not saved.
     */
    fun onExport(uri: Uri?) {
        uri ?: return
        local.update { it.copy(exportError = null) }
        viewModelScope.launch {
            val text = store.state.first { !it.isLoading }.receipts.firstOrNull { it.id == receiptId }
                ?.responseText?.takeIf { it.isNotEmpty() }
            if (text == null) {
                local.update { it.copy(exportError = UiText.Resource(R.string.activity_export_failed)) }
                return@launch
            }
            try {
                writer.write(uri, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason = e.localizedMessage?.takeIf { it.isNotBlank() }
                local.update { it.copy(exportError = reason?.let(UiText::Verbatim) ?: UiText.Resource(R.string.activity_export_failed)) }
            }
        }
    }

    private suspend fun decodeAgain(receipt: SigningReceipt, showProgress: Boolean = true) {
        val json = receipt.requestJson
        if (json == null) {
            decode.value = DecodeState.NotStored
            return
        }
        if (showProgress) decode.value = DecodeState.Decoding
        decode.value = try {
            DecodeState.Decoded(store.reviewStoredRequest(json, receipt.requestHash))
        } catch (e: CancellationException) {
            // Leaving the page cancels the review (iOS swallows `CancellationError`); nothing is shown.
            throw e
        } catch (e: Exception) {
            DecodeState.Failed(e.toUiText() ?: UiText.Verbatim(e.javaClass.simpleName))
        }
    }

    private fun currentReceipt(): SigningReceipt? = store.state.value.receipts.firstOrNull { it.id == receiptId }

    private fun build(state: SignerState, decoded: DecodeState, local: Local): ActivityDetailUiState {
        val receipt = state.receipts.firstOrNull { it.id == receiptId } ?: return ActivityDetailUiState(decode = decoded)
        val fingerprint = receipt.masterFingerprint ?: receipt.walletIdOrNull?.let { state.publicMetadata(it)?.masterFingerprint }
        return ActivityDetailUiState(
            receipt = receipt,
            wallet = WalletLabel.of(receipt, state),
            fingerprint = fingerprint?.uppercase(Locale.ROOT),
            risk = when (receipt.riskLevel) {
                null, RiskLevel.Normal.wire -> null
                RiskLevel.High.wire -> RiskRow.High
                else -> RiskRow.WarningsShown
            },
            // A review decoded on this visit stays: a newer signature can push the record past the 25 most recent while
            // "Signed Request" shows it, and the record then loses its request (activity-settings.md §2.1). iOS pages
            // keep the values they were opened with; here the page would otherwise wait for a decode that never comes.
            // (The Signature page hides Input once the record has no request.)
            decode = when {
                decoded is DecodeState.Decoded -> decoded
                receipt.requestJson == null -> DecodeState.NotStored
                else -> decoded
            },
            showsQr = local.showsQr && receipt.responseFrames?.isNotEmpty() == true,
            exportError = local.exportError,
        )
    }

    private data class Local(val showsQr: Boolean = false, val exportError: UiText? = null)

    companion object {
        /** The route argument shared by `ActivityDetailRoute` and `SignedRequestRoute`. */
        const val RECEIPT_ID = "receiptId"
    }
}
