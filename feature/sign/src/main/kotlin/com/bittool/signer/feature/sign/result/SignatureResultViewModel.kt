package com.bittool.signer.feature.sign.result

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerState
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.pairing.QrPayloadView
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.feature.sign.common.SignatureFileWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The finished signature (iOS `SignatureResultView`, `Features/Sign/SignatureResultView.swift`): the response [frames]
 * (QR), the export [text] (frames joined by newlines), the developer [payload] view, the request [review] it answers,
 * and the last export failure.
 */
@Immutable
data class SignatureResultUiState(
    val frames: List<String> = emptyList(),
    val text: String = "",
    val payload: QrPayloadView? = null,
    val review: TransactionReview? = null,
    val exportError: UiText? = null,
) {
    val showsDetails: Boolean get() = payload != null || text.isNotEmpty()
}

/**
 * The signature result page (inventory rows 54–55; scan-sign.md §15): "Done" drops the request and returns to Scan home
 * (the camera never reopens, `ScanRequestRouterTests`); "Export as File" writes the response text through the system
 * document picker (`bittool-signer-signature.txt`, UTF-8, truncating). A failed export shows the system's message
 * (iOS `error.localizedDescription`).
 */
@HiltViewModel
class SignatureResultViewModel @Inject constructor(
    private val store: SignerStore,
    private val writer: SignatureFileWriter,
) : ViewModel() {

    private val exportError = MutableStateFlow<UiText?>(null)

    /** Always current ([SharingStarted.Eagerly]) for the same reason as `RequestReviewViewModel.uiState`. */
    val uiState: StateFlow<SignatureResultUiState> = combine(store.state, exportError, ::build)
        .stateIn(viewModelScope, SharingStarted.Eagerly, build(store.state.value, exportError.value))

    init {
        viewModelScope.launch {
            // A new result page (after Done the page is rebuilt on iOS) starts without the previous export error.
            store.state.map { it.hasSignedResponse }.distinctUntilChanged().filter { !it }.collect { exportError.value = null }
        }
    }

    private fun build(state: SignerState, error: UiText?) = SignatureResultUiState(
        frames = state.signatureFrames,
        text = state.signatureText,
        payload = state.signaturePayload,
        review = state.review,
        exportError = error,
    )

    /** "Done" (`signature.done`). */
    fun done() {
        exportError.value = null
        store.clearRequest()
    }

    /** The document picker returned [uri] (null: cancelled, nothing happens). */
    fun export(uri: Uri?) {
        if (uri == null) return
        val text = store.state.value.signatureText
        if (text.isEmpty()) return
        exportError.value = null
        viewModelScope.launch {
            try {
                writer.write(uri, text)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) {
                exportError.value = e.localizedMessage?.takeIf { it.isNotBlank() }?.let(UiText::Verbatim)
                    ?: UiText.Resource(R.string.sign_file_not_saved)
            }
        }
    }

    companion object {
        /** iOS `defaultFilename: "bittool-signer-signature"` with the `.plainText` extension. */
        const val EXPORT_FILE_NAME = "bittool-signer-signature.txt"
        const val EXPORT_MIME_TYPE = "text/plain"
    }
}
