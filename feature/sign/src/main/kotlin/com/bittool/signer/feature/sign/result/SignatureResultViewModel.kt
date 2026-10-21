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
import com.bittool.signer.core.model.util.Hex
import com.bittool.signer.feature.sign.common.SignatureFileWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.security.MessageDigest
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
import kotlinx.coroutines.flow.first
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
 *
 * The picker is another app, and Android may end this process while it is open. The signature lived in memory only, so
 * the page is gone when the picker answers a new process; the document the person just created is then written from
 * the signature's Activity record (named by the digest [prepareExport] gave the picker's saved state), and without one
 * the app's alert says "The file could not be saved." instead of leaving an empty file without a word. The picker is
 * registered by the Scan tab root ([rememberSignatureExport]), which is there whatever page the tab shows (audit A14).
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

    /**
     * "Export as File" found no document picker to open (no app handles `CREATE_DOCUMENT`: some work profiles, stripped
     * builds): said in place, as a failed save is, instead of the app stopping.
     */
    fun onExportUnavailable() {
        exportError.value = UiText.Resource(R.string.sign_file_not_saved)
    }

    /**
     * "Export as File" is about to open the document picker: which signature it exports, as the SHA-256 (hex) of its
     * text, for the caller to keep in saved state with the picker (it outlives this process; the signature is public,
     * but only its digest is kept). Null when no signature is shown.
     */
    fun prepareExport(): String? = store.state.value.signatureText.takeIf { it.isNotEmpty() }?.let(::digest)

    /**
     * The document picker returned [uri] (null: cancelled, nothing happens): writes the signature shown, or, when the
     * store has none (a new process after the picker), the same signature from its Activity record once the store has
     * read Activity again: the newest record whose export text has the digest [exported] ([prepareExport] when the
     * picker opened; a signature too large to keep, or whose record could not be saved, has none). Without either, or
     * when the write fails, the person is told the file was not saved: under the buttons while the page is shown, else
     * in the app's alert.
     */
    fun export(uri: Uri?, exported: String? = null) {
        if (uri == null) return
        val shown = store.state.value.signatureText
        exportError.value = null
        viewModelScope.launch {
            val text = shown.ifEmpty { storedSignature(exported) }
            if (text == null) {
                notSaved(UiText.Resource(R.string.sign_file_not_saved))
                return@launch
            }
            try {
                writer.write(uri, text)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) {
                val reason = e.localizedMessage?.takeIf { it.isNotBlank() }
                notSaved(reason?.let(UiText::Verbatim) ?: UiText.Resource(R.string.sign_file_not_saved))
            }
        }
    }

    /** The newest Activity export text whose SHA-256 is [wanted], once the store has loaded; null when there is none. */
    private suspend fun storedSignature(wanted: String?): String? {
        if (wanted == null) return null
        val loaded = store.state.first { !it.isLoading }
        return loaded.receipts.firstNotNullOfOrNull { record -> record.responseText?.takeIf { it.isNotEmpty() && digest(it) == wanted } }
    }

    /** A failed export, said where the person is: on the result page while it is shown, else in the app's alert. */
    private fun notSaved(text: UiText) {
        if (store.state.value.hasSignedResponse) exportError.value = text else store.setError(text)
    }

    companion object {
        /** iOS `defaultFilename: "bittool-signer-signature"` with the `.plainText` extension. */
        const val EXPORT_FILE_NAME = "bittool-signer-signature.txt"
        const val EXPORT_MIME_TYPE = "text/plain"

        private fun digest(text: String): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()))
    }
}
