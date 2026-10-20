package com.bittool.signer.core.ui.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.model.LegalBlock
import com.bittool.signer.core.model.LegalDocumentParser

/**
 * A bundled legal document (iOS `LegalDocumentView`, `Features/Settings/LegalDocumentView.swift:13-58`;
 * activity-settings §10): the version line (footnote, secondary), headings (headline, 8 dp extra above, marked as
 * headings), bullets ("•" + callout) and paragraphs (callout), 12 dp apart, selectable, within the readable width.
 * `**bold**` spans render bold. No links: the app opens no browser. Lazy, so long documents scroll smoothly.
 *
 * [missingText] replaces the English `LegalDocumentParser.MISSING` block (the repository returns it when a bundled file
 * cannot be read) with its translation, which the settings feature owns. [contentPadding] adds the screen's insets.
 */
@Composable
fun LegalDocumentView(
    blocks: List<LegalBlock>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    missingText: String? = null,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    LazyColumn(
        modifier = modifier,
        state = rememberLazyListState(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(blocks, key = { it.id }) { block ->
            val text = if (block.text == LegalDocumentParser.MISSING && missingText != null) missingText else block.text
            val styled = remember(text) { boldSpans(text) }
            val itemModifier = Modifier.readableWidth().padding(horizontal = SignerMetrics.pagePadding)
            SelectionContainer(itemModifier) {
                when (block.kind) {
                    LegalBlock.Kind.Version -> Text(styled, style = typography.footnote, color = colors.secondaryLabel)
                    LegalBlock.Kind.Heading -> Text(
                        styled,
                        style = typography.headline,
                        color = colors.label,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                    LegalBlock.Kind.Bullet -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", style = typography.callout, color = colors.secondaryLabel)
                        Text(styled, style = typography.callout, color = colors.label)
                    }
                    LegalBlock.Kind.Paragraph -> Text(styled, style = typography.callout, color = colors.label)
                }
            }
        }
    }
}

/** `**bold**` spans as bold; the asterisks are removed. An unmatched `**` stays literal (pure, unit-tested). */
internal fun boldSpans(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        val open = text.indexOf("**", index)
        val close = if (open >= 0) text.indexOf("**", open + 2) else -1
        if (open < 0 || close < 0) {
            append(text.substring(index))
            break
        }
        append(text.substring(index, open))
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(open + 2, close)) }
        index = close + 2
    }
}
