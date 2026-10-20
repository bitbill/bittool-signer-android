package com.bittool.signer.feature.setup.backup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.SignerBottomSheet
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.security.AccessibilitySensitive
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.monospacedDigit
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.model.RecoveryCards
import com.bittool.signer.core.security.RecoveryPrivacySession
import com.bittool.signer.feature.setup.flow.RecoveryWords
import com.bittool.signer.feature.setup.ui.LinkButton
import com.bittool.signer.feature.setup.ui.OverlayGuardHost
import com.bittool.signer.feature.setup.ui.SheetTitleBar
import com.bittool.signer.feature.setup.ui.LocalOverlayBlocked
import com.bittool.signer.core.l10n.R as L10n

/**
 * "Recovery Cards" (iOS `RecoveryCardsSheet`, `RecoveryBackupViews.swift:175-216`; setup-backup §8.6): three cards, each
 * leaving out a third of the words, so that any two restore the phrase. A secure sheet (its window is `FLAG_SECURE`
 * itself) with its own privacy session; revealing the cards counts as revealing the backup ([onReveal]).
 */
@Composable
internal fun RecoveryCardsSheet(words: RecoveryWords, hasPassphrase: Boolean, onReveal: () -> Unit, onDismiss: () -> Unit) {
    SignerBottomSheet(onDismissRequest = onDismiss, secure = true, testTag = "cards.sheet") {
        val sheet = this
        OverlayGuardHost(fill = false, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            Column(Modifier.fillMaxWidth()) {
                SheetTitleBar(title = stringResource(L10n.string.recovery_cards), onDone = { sheet.dismiss() }, doneTag = "cards.done")
                RecoveryCardsPage(words, hasPassphrase, rememberPrivacySession(), onReveal)
            }
        }
    }
}

/** The sheet's scrolling page: the explanation, the warnings and the cards (also drawn by the screenshot tests). */
@Composable
internal fun RecoveryCardsPage(words: RecoveryWords, hasPassphrase: Boolean, session: RecoveryPrivacySession, onReveal: () -> Unit) {
    val colors = SignerTheme.colors
    val count = words.size
    val weak = RecoveryCards.singleCardIsWeak(count)
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .readableWidth()
            .padding(horizontal = SignerMetrics.pagePadding)
            .padding(top = 4.dp, bottom = SignerMetrics.pagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(
                if (weak) L10n.string.keep_these_three_cards_in_three_different_places_any_2 else L10n.string.keep_these_three_cards_in_three_different_places_any,
            ),
            style = SignerTheme.typography.subheadline,
            color = colors.secondaryLabel,
        )
        if (weak) {
            val missing = count / RecoveryCards.COUNT
            Callout(stringResource(L10n.string.setup_weak_cards_warning, count, count - missing, count, missing), tone = Tone.Warning)
        }
        if (hasPassphrase) {
            Callout(stringResource(L10n.string.the_cards_contain_the_words_only_back_up_your_bip39), tone = Tone.Warning)
        }
        RecoveryCardsContent(words, session, active = true, onReveal = onReveal)
    }
}

/** iOS `RecoveryCardsContent`: concealed placeholders, or the three cards and "Hide Cards". */
@Composable
internal fun RecoveryCardsContent(words: RecoveryWords, session: RecoveryPrivacySession, active: Boolean, onReveal: () -> Unit) {
    RecoveryPrivacyEffect(session, active, onReveal)
    val revealed by session.isRevealed.collectAsStateWithLifecycle()
    AnimatedContent(
        targetState = revealed,
        transitionSpec = {
            val change = if (targetState) {
                fadeIn(SignerMotion.reveal()) togetherWith fadeOut(SignerMotion.reveal())
            } else {
                fadeIn(SignerMotion.quick()) togetherWith ExitTransition.None
            }
            change using SizeTransform(clip = false) { _, _ -> SignerMotion.reveal() }
        },
        label = "recoveryCards",
    ) { shown ->
        if (shown) {
            AccessibilitySensitive(active = true)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                for (card in 0 until RecoveryCards.COUNT) RecoveryCardView(words, card)
                LinkButton(
                    text = stringResource(L10n.string.hide_cards),
                    leadingIcon = SignerIcons.VisibilityOff,
                    onClick = session::hide,
                    modifier = Modifier.testTag("cards.hide"),
                )
            }
        } else {
            ConcealedCards(count = words.size, onReveal = { session.reveal() })
        }
    }
}

/**
 * One card (iOS `RecoveryCardView`, `RecoveryBackupViews.swift:250-311`): "Card n of 3", "Leaves out words …", and
 * every position — this card's words, `xxxx` for the third it leaves out — in the shared font, on a card with a 1 dp
 * accent border. Cells are labelled "Word n, word" / "Word n, not on this card" (`cards.card.<c>.word.<p>`).
 */
@Composable
internal fun RecoveryCardView(words: RecoveryWords, card: Int, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val omitted = RecoveryCards.omittedPositions(card, words.size).joinToString(", ") { (it + 1).toString() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.card, SignerShapes.card)
            .border(1.dp, colors.accent.copy(alpha = 0.35f), SignerShapes.card)
            .padding(12.dp)
            .testTag("cards.card.$card"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(L10n.string.card_x_of_x, card + 1, RecoveryCards.COUNT), style = typography.headline, color = colors.label)
            Text(stringResource(L10n.string.leaves_out_words_x, omitted), style = typography.caption, color = colors.secondaryLabel)
        }
        WordGrid(count = words.size, columns = WordGridColumns.Cards, measuredWords = words.list + LEFT_OUT) { position, layout ->
            val shown = RecoveryCards.shows(position, card)
            val label = if (shown) {
                stringResource(L10n.string.word_x_x, position + 1, words.list[position])
            } else {
                stringResource(L10n.string.word_x_not_on_this_card, position + 1)
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (shown) colors.raised else colors.raised.copy(alpha = 0.45f), CardCellShape)
                    .padding(horizontal = 8.dp, vertical = 10.dp)
                    .testTag("cards.card.$card.word.$position")
                    .clearAndSetSemantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(L10n.string.x_3, position + 1),
                    style = typography.caption2.monospacedDigit(),
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 20.dp),
                )
                val wordStyle = layout.wordStyle(typography.subheadline)
                if (shown) {
                    BasicText(
                        text = words.list[position],
                        style = wordStyle.copy(color = colors.label),
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = layout.wordSize * 0.9f, maxFontSize = layout.wordSize),
                        // Next to its number in both directions (see RecoveryWordGrid).
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Text(LEFT_OUT, style = wordStyle.copy(fontWeight = FontWeight.Normal), color = colors.tertiaryLabel, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Three card-shaped placeholders without recovery material (iOS `ConcealedCards`): "Card n of 3" and up to six blurred
 * bars each, under the reveal prompt. One button: "Show recovery cards", `cards.reveal`; overlay touches are refused.
 */
@Composable
private fun ConcealedCards(count: Int, onReveal: () -> Unit) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val description = concealedDescription(L10n.string.show_recovery_cards)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .obscuredTouchGuard(LocalOverlayBlocked.current)
            .clip(SignerShapes.card)
            .clickable(role = Role.Button, indication = ripple(), interactionSource = null, onClick = onReveal)
            .semantics { contentDescription = description }
            .testTag("cards.reveal"),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth().clearAndSetSemantics {}.blur(2.5.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (card in 0 until RecoveryCards.COUNT) {
                Column(
                    Modifier.fillMaxWidth().background(colors.card, PlaceholderShape).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(L10n.string.card_x_of_x, card + 1, RecoveryCards.COUNT),
                        style = typography.subheadline.semibold(),
                        color = colors.secondaryLabel,
                    )
                    val bars = minOf(count, 6)
                    for (rowStart in 0 until bars step 3) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (column in 0 until 3) {
                                val visible = rowStart + column < bars
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(26.dp)
                                        .then(if (visible) Modifier.background(colors.raised, BarShape) else Modifier),
                                )
                            }
                        }
                    }
                }
            }
        }
        RevealPrompt()
    }
}

private const val LEFT_OUT = "xxxx"
private val CardCellShape = RoundedCornerShape(11.dp)
private val PlaceholderShape = RoundedCornerShape(18.dp)
private val BarShape = RoundedCornerShape(8.dp)
