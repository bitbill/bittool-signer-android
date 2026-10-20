package com.bittool.signer.feature.settings.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.data.LegalDocumentRepository
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.ui.legal.LegalDocumentView
import com.bittool.signer.feature.settings.LoadingRow
import com.bittool.signer.feature.settings.SectionFrame
import com.bittool.signer.feature.settings.SettingsPage
import com.bittool.signer.feature.settings.rememberResumed

/** Terms of Use / Privacy Policy pushed from Settings › About (inventory row 74). */
@Composable
fun LegalDocumentScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: LegalDocumentViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LegalDocumentContent(state, onBack = rememberResumed(onBack), modifier = modifier)
}

/**
 * The document under its English title (the documents are English in every language, by decision, so the title is
 * not translated either), blocks rendered by `:core:ui`'s [LegalDocumentView] — plain text, no links, selectable. The
 * one translated text is the "missing from the app bundle" paragraph.
 */
@Composable
internal fun LegalDocumentContent(state: LegalDocumentUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    SettingsPage(title = state.document.englishTitle, onBack = onBack, modifier = modifier) { insets ->
        val blocks = state.blocks
        if (blocks == null) {
            LoadingRow(Modifier.padding(insets))
        } else {
            LegalDocumentView(
                blocks = blocks,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = insets.calculateTopPadding())
                    .testTag(state.document.testTag),
                contentPadding = documentPadding(insets),
                missingText = stringResource(R.string.settings_document_missing),
            )
        }
    }
}

/** Test tags of Third-Party Notices. */
object NoticesTags {
    const val LIST = "notices.list"
}

/** Third-Party Notices (inventory row 73). */
@Composable
fun ThirdPartyNoticesScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ThirdPartyNoticesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ThirdPartyNoticesContent(state, onBack = rememberResumed(onBack), modifier = modifier)
}

/**
 * Each notices file under its own heading (the engine's, then the Android app's), every paragraph verbatim in 11 sp
 * monospace, selectable, lazily (iOS `LicensesView`: `LazyVStack`, spacing 10, padding 20). The notices are English
 * and laid out left to right in every language.
 */
@Composable
internal fun ThirdPartyNoticesContent(state: NoticesUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val missing = stringResource(R.string.third_party_notices_are_included_with_the_source)
    SettingsPage(title = stringResource(R.string.third_party_notices), onBack = onBack, modifier = modifier) { insets ->
        val sections = state.sections
        if (sections == null) {
            LoadingRow(Modifier.padding(insets))
            return@SettingsPage
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = insets.calculateTopPadding())
                .testTag(NoticesTags.LIST),
            contentPadding = documentPadding(insets),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            sections.forEachIndexed { sectionIndex, section ->
                if (section.title.isNotEmpty()) {
                    item(key = "title-$sectionIndex", contentType = "title") {
                        Text(
                            text = section.title,
                            style = SignerTheme.typography.headline.ltr(),
                            color = SignerTheme.colors.label,
                            modifier = SectionFrame
                                .padding(top = if (sectionIndex == 0) 0.dp else 16.dp)
                                .semantics { heading() },
                        )
                    }
                }
                items(section.paragraphs.size, key = { "p-$sectionIndex-$it" }, contentType = { "paragraph" }) { index ->
                    val paragraph = section.paragraphs[index]
                    SelectionContainer(SectionFrame) {
                        Text(
                            text = if (paragraph == LegalDocumentRepository.NOTICES_MISSING) missing else paragraph,
                            style = SignerTheme.typography.caption2.monospaced().ltr(),
                            color = SignerTheme.colors.label,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** Side and bottom insets plus the iOS 20 pt page padding above and below the text. */
@Composable
private fun documentPadding(insets: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = insets.calculateStartPadding(direction),
        end = insets.calculateEndPadding(direction),
        top = SignerMetrics.pagePadding,
        bottom = insets.calculateBottomPadding() + SignerMetrics.pagePadding,
    )
}
