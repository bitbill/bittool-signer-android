package com.bittool.signer.feature.settings.about

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.data.LegalDocumentRepository
import com.bittool.signer.core.data.NoticeSection
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.LegalDocumentParser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A bundled legal document page (iOS `LegalDocumentView`). [blocks] is null while it is read. */
@Immutable
data class LegalDocumentUiState(val document: LegalDocument, val blocks: List<LegalBlock>? = null)

/**
 * Terms of Use / Privacy Policy (activity-settings.md §10, inventory row 74): the flavor's bundled text parsed off the
 * main thread by [LegalDocumentRepository]. The route argument `document` is `LegalDocument.name`; an unknown value
 * opens the Terms (the route's own fallback). A file that cannot be read shows the one "missing" paragraph.
 */
@HiltViewModel
class LegalDocumentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LegalDocumentRepository,
) : ViewModel() {
    private val document: LegalDocument =
        LegalDocument.entries.firstOrNull { it.name == savedStateHandle.get<String>(DOCUMENT) } ?: LegalDocument.Terms

    private val state = MutableStateFlow(LegalDocumentUiState(document))
    val uiState: StateFlow<LegalDocumentUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val blocks = try {
                repository.blocks(document)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                listOf(LegalBlock(0, LegalBlock.Kind.Paragraph, LegalDocumentParser.MISSING))
            }
            state.value = LegalDocumentUiState(document, blocks)
        }
    }

    private companion object {
        const val DOCUMENT = "document"
    }
}

/** Third-Party Notices: one section per notices file, null while reading. */
@Immutable
data class NoticesUiState(val sections: List<NoticeSection>? = null)

/**
 * Third-Party Notices (iOS `LicensesView`, activity-settings.md §9, inventory row 73): the engine's notices
 * (byte-identical with iOS) followed by the Android app's own dependency notices, read off the main thread.
 */
@HiltViewModel
class ThirdPartyNoticesViewModel @Inject constructor(private val repository: LegalDocumentRepository) : ViewModel() {
    private val state = MutableStateFlow(NoticesUiState())
    val uiState: StateFlow<NoticesUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val sections = try {
                repository.thirdPartyNotices()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                listOf(NoticeSection("", listOf(LegalDocumentRepository.NOTICES_MISSING)))
            }
            state.value = NoticesUiState(sections)
        }
    }
}
