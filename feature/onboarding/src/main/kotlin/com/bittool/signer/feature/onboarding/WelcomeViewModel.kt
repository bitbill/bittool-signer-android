package com.bittool.signer.feature.onboarding

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.common.coroutines.runCatchingCancellable
import com.bittool.signer.core.data.LegalDocumentRepository
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocument
import com.bittool.signer.core.model.LegalDocumentParser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The legal document sheet of Welcome (iOS `WelcomeView.legal` + `LegalDocumentSheet`, WelcomeView.swift:54,
 * Features/Settings/LegalDocumentView.swift:105-120): "Terms of Use" and "Privacy Policy" open the bundled text
 * (the per-flavor Android texts, activity-settings.md §10) in a sheet with "Done". The app opens no browser.
 */
@Immutable
data class LegalSheet(
    val document: LegalDocument,
    /** The parsed blocks, or null while they are read (off the main thread; a few milliseconds). */
    val blocks: List<LegalBlock>?,
)

/** State holder of [WelcomeScreen]: only the legal sheet. Starting setup is `:app`'s (`AppRouter.startSetup`). */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    private val legalDocuments: LegalDocumentRepository,
) : ViewModel() {

    private val _legalSheet = MutableStateFlow<LegalSheet?>(null)

    /** The document shown in the sheet, or null when no sheet is up. */
    val legalSheet: StateFlow<LegalSheet?> = _legalSheet.asStateFlow()

    private var loading: Job? = null

    /** Opens [document]; a document that cannot be read shows the parser's "missing" paragraph, like iOS. */
    fun showLegal(document: LegalDocument) {
        loading?.cancel()
        _legalSheet.value = LegalSheet(document, blocks = null)
        loading = viewModelScope.launch {
            val blocks = runCatchingCancellable { legalDocuments.blocks(document) }
                .getOrElse { listOf(LegalBlock(0, LegalBlock.Kind.Paragraph, LegalDocumentParser.MISSING)) }
            _legalSheet.update { current -> if (current?.document == document) current.copy(blocks = blocks) else current }
        }
    }

    /** "Done", a swipe down or Back. */
    fun dismissLegal() {
        loading?.cancel()
        loading = null
        _legalSheet.value = null
    }
}
