package com.bittool.signer.feature.sign.home

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.SignerStore
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.toUiText
import com.bittool.signer.core.model.util.trimWhitespacesAndNewlines
import com.bittool.signer.feature.sign.common.RequestFileReader
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The paste sheet while it is presented (iOS `PasteRequestSheet` state, `Features/Sign/ScanTab.swift:263-325`).
 * [closing]: the request was reviewed, so the sheet slides away (iOS `dismiss()`); it reports back through
 * [ScanHomeViewModel.dismissPaste] once it has left the screen.
 */
@Immutable
data class PasteUiState(val busy: Boolean = false, val error: UiText? = null, val closing: Boolean = false)

/**
 * Scan home (iOS `ScanHomeView` state, `ScanTab.swift:108-251`): [busy] while a file import is in flight (the option
 * tiles are disabled), the import [error] (danger callout until the next scan-card tap or submission), and the paste
 * sheet when [paste] is non-null.
 */
@Immutable
data class ScanHomeUiState(
    val busy: Boolean = false,
    val error: UiText? = null,
    val paste: PasteUiState? = null,
)

/**
 * Scan home, the paste sheet and file import (inventory rows 46, 49, 50; scan-sign.md §3–§5).
 *
 * Every submission follows the iOS rules: ignored while busy, the error cleared, the security epoch captured; a failure
 * is shown only when the epoch is unchanged and it is not a cancellation (a late result after backgrounding is
 * silent), and busy ends with the call. A new security epoch ends busy at once (iOS `.onChange(of: securityEpoch)`).
 * Home is rebuilt on iOS after a review or signature, so its error goes and the paste sheet slides away
 * ([PasteUiState.closing]) as soon as a request is reviewed. The pasted text lives in [pasteText] (a request is public
 * data; kept across rotation, never saved).
 */
@HiltViewModel
class ScanHomeViewModel @Inject constructor(
    private val store: SignerStore,
    private val fileReader: RequestFileReader,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScanHomeUiState())
    val uiState: StateFlow<ScanHomeUiState> = _uiState.asStateFlow()

    /** The paste sheet's editor ("signing.payload"). */
    val pasteText = TextFieldState()

    private var importOperation = 0L
    private var pasteOperation = 0L

    init {
        viewModelScope.launch {
            store.state.map { it.securityEpoch }.distinctUntilChanged().drop(1).collect {
                _uiState.update { s -> s.copy(busy = false, paste = s.paste?.copy(busy = false)) }
            }
        }
        viewModelScope.launch {
            store.state.map { it.review != null || it.hasSignedResponse }.distinctUntilChanged().drop(1).collect { reviewed ->
                if (reviewed) {
                    // The request is under review: Home goes and its sheet slides away with it (iOS rebuilds both
                    // after Reject / Done). The text stays until the sheet is off the screen.
                    pasteOperation++
                    _uiState.update { it.copy(error = null, paste = it.paste?.copy(busy = false, closing = true)) }
                } else if (_uiState.value.paste?.closing == true) {
                    // Home is back (Reject, Done) while a sheet never finished leaving (the page went first).
                    dismissPaste()
                }
            }
        }
    }

    /** The scan card (or a request from elsewhere) opens the camera: iOS `error = nil; scanning = true`. */
    fun onOpenScanner() {
        _uiState.update { it.copy(error = null) }
    }

    /** "Paste Request": a fresh, empty sheet. */
    fun openPaste() {
        if (_uiState.value.busy) return
        pasteOperation++
        pasteText.clearText()
        _uiState.update { it.copy(paste = PasteUiState()) }
    }

    /** "Cancel", a swipe or Back: the sheet and its text go away; an import already sent finishes in the store. */
    fun dismissPaste() {
        pasteOperation++
        pasteText.clearText()
        _uiState.update { it.copy(paste = null) }
    }

    /** "Paste from Clipboard": replaces the text when the clipboard holds text, otherwise nothing happens. */
    fun pasteClipboard(text: CharSequence?) {
        if (text == null) return
        pasteText.edit { replace(0, length, text) }
    }

    /** Whether "Review Request" can be pressed: not busy, not leaving, and the text is not blank. */
    fun canSubmitPaste(state: PasteUiState): Boolean =
        !state.busy && !state.closing && pasteText.text.toString().trimWhitespacesAndNewlines().isNotEmpty()

    /** "Review Request" (`signing.decode`, iOS `PasteRequestSheet.submit`). */
    fun submitPaste() {
        val paste = _uiState.value.paste ?: return
        if (!canSubmitPaste(paste)) return
        val text = pasteText.text.toString()
        val operation = ++pasteOperation
        val epoch = store.state.value.securityEpoch
        _uiState.update { it.copy(paste = it.paste?.copy(busy = true, error = null)) }
        viewModelScope.launch {
            try {
                store.importPayload(text)
                // iOS `payload = ""; dismiss()`: the sheet slides away, its text goes when it is off the screen.
                if (operation == pasteOperation) _uiState.update { it.copy(paste = it.paste?.copy(closing = true)) }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) {
                if (operation == pasteOperation && epoch == store.state.value.securityEpoch) {
                    _uiState.update { it.copy(paste = it.paste?.copy(error = e.toUiText())) }
                }
            } finally {
                if (operation == pasteOperation) _uiState.update { it.copy(paste = it.paste?.copy(busy = false)) }
            }
        }
    }

    /**
     * "Import File" picked [uri] (null: the picker was cancelled, nothing happens). The file is read off the main
     * thread ([RequestFileReader]: 512,000-byte limit, binary PSBT → Base64, else strict UTF-8), then imported like
     * pasted text. The tiles stay disabled throughout.
     */
    fun importFile(uri: Uri?) {
        if (uri == null || _uiState.value.busy) return
        val operation = ++importOperation
        val epoch = store.state.value.securityEpoch
        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val text = fileReader.read(uri)
                // iOS reads synchronously; here a background transition during the read ends the import silently.
                if (operation != importOperation || epoch != store.state.value.securityEpoch) return@launch
                store.importPayload(text)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) {
                if (operation == importOperation && epoch == store.state.value.securityEpoch) {
                    _uiState.update { it.copy(error = e.toUiText()) }
                }
            } finally {
                if (operation == importOperation) _uiState.update { it.copy(busy = false) }
            }
        }
    }
}
