package com.bittool.signer.feature.sign.review

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerRadius
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.contentDirection
import com.bittool.signer.core.designsystem.theme.localizedDirection
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.review.ReviewField
import com.bittool.signer.core.model.review.ReviewPresentation
import com.bittool.signer.core.model.review.ReviewText
import com.bittool.signer.core.ui.address.CopyableAddress
import com.bittool.signer.feature.sign.common.SignTags

/**
 * Every decoded field of the request as items of the review list (iOS `ReviewDetailsCard`,
 * `RequestReviewView.swift:694-764`; scan-sign.md §11.9): the heading "Details", then one item per field, together one
 * card with the rows separated by hairlines. The rows are drawn exactly as `core.ui.review.ReviewDetailsCard` draws
 * them (label through [reviewText] above the value in its `ReviewPresentation.rowStyle`; values verbatim; "Show All" per
 * field, kept across configuration changes and scrolling).
 *
 * The card is drawn piecewise, each row carrying its part of the background (the first row the top corners and padding,
 * the last the bottom ones), so the list composes, measures and draws only the rows on screen. The engine emits a row per
 * Bitcoin input and output: as one item, a batch payout of a few thousand outputs was composed in a single frame and
 * held the main thread for seconds, long enough for "isn't responding" (audit A10). The rows of one card must touch,
 * so the list has no item spacing of its own; the sections keep their 16 dp through [sectionGap].
 */
internal fun LazyListScope.reviewDetails(fields: List<ReviewField>, reviewText: ReviewText, page: Modifier) {
    item(key = DETAILS_HEADING, contentType = DETAILS_HEADING) { DetailsHeading(Modifier.sectionGap().then(page)) }
    if (fields.isEmpty()) {
        item(key = DETAILS_EMPTY, contentType = DETAILS_EMPTY) { DetailsCardPart(first = true, last = true, modifier = page) {} }
        return
    }
    val last = fields.lastIndex
    itemsIndexed(
        items = fields,
        key = { index, _ -> "$FIELD_KEY_PREFIX$index" },
        contentType = { _, field -> ReviewPresentation.rowStyle(field) },
    ) { index, field ->
        DetailsCardPart(first = index == 0, last = index == last, modifier = page.testTag(SignTags.REVIEW_FIELD)) {
            if (index > 0) {
                Box(Modifier.padding(vertical = 10.dp)) { Hairline() }
            }
            DetailRow(field, reviewText)
        }
    }
}

/** The space above every section of the review but the first (the list's former `spacedBy(16.dp)`). */
internal fun Modifier.sectionGap(): Modifier = padding(top = SECTION_GAP)

private val SECTION_GAP = 16.dp

private const val DETAILS_HEADING = "details"
private const val DETAILS_EMPTY = "details-empty"
private const val FIELD_KEY_PREFIX = "field-"

@Composable
private fun DetailsHeading(modifier: Modifier) {
    Box(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.details),
            style = SignerTheme.typography.headline,
            color = SignerTheme.colors.label,
            modifier = Modifier.padding(bottom = 10.dp).semantics { heading() },
        )
    }
}

/**
 * One row's slice of the Details card (`SignerCard`: `card` colour, radius 22, padding 16, no border or shadow): the
 * horizontal padding always, the top corners and padding on the [first] row, the bottom ones on the [last].
 */
@Composable
private fun DetailsCardPart(first: Boolean, last: Boolean, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val top = if (first) SignerRadius.card else 0.dp
    val bottom = if (last) SignerRadius.card else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
    val padding = SignerMetrics.cardPadding
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SignerTheme.colors.card, shape)
            .padding(start = padding, end = padding, top = if (first) padding else 0.dp, bottom = if (last) padding else 0.dp),
        content = content,
    )
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(SignerTheme.colors.opaqueSeparator))
}

/** One field, as `core.ui.review.ReviewDetailsCard` draws it (scan-sign.md §11.9). */
@Composable
private fun DetailRow(field: ReviewField, reviewText: ReviewText) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        val label = reviewText.label(field.label)
        Text(label, style = typography.caption.semibold().localizedDirection(label), color = colors.secondaryLabel)
        when (ReviewPresentation.rowStyle(field)) {
            ReviewPresentation.DetailRowStyle.Address -> CopyableAddress(field.value, style = typography.footnote)
            ReviewPresentation.DetailRowStyle.Identifier -> SelectionContainer {
                Text(field.value, style = typography.footnote.monospaced().ltr(), color = colors.label)
            }
            ReviewPresentation.DetailRowStyle.Collapsible -> {
                var open by rememberSaveable(field.id) { mutableStateOf(false) }
                SelectionContainer(Modifier.animateContentSize(SignerMotion.disclosure())) {
                    Text(
                        text = if (open) field.value else ReviewPresentation.collapsedValue(field.value),
                        style = typography.footnote.monospaced().ltr(),
                        color = colors.label,
                    )
                }
                Text(
                    text = stringResource(if (open) R.string.show_less else R.string.show_all),
                    style = typography.footnote.semibold(),
                    color = colors.accent,
                    modifier = Modifier
                        .heightIn(min = 32.dp)
                        .clickable(role = Role.Button) { open = !open }
                        .padding(vertical = 6.dp),
                )
            }
            ReviewPresentation.DetailRowStyle.Text -> SelectionContainer {
                val technical = ReviewPresentation.looksTechnical(field.value)
                Text(
                    text = field.value,
                    style = if (technical) typography.footnote.monospaced().ltr() else typography.subheadline.contentDirection(),
                    color = colors.label,
                )
            }
        }
    }
}
