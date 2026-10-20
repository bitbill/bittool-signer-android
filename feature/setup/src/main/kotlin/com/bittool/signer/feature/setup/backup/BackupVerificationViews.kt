package com.bittool.signer.feature.setup.backup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.monospacedDigit
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.model.BackupVerification
import com.bittool.signer.core.l10n.R as L10n

/**
 * The fill-in check's grid (iOS `BackupVerificationGrid`, `RecoveryBackupViews.swift:422-521`; setup-backup §8.8): every
 * position, masked (`••••••`) except the blanks. Tapping an empty blank makes it the next to fill ([onSelect]); tapping a
 * filled one takes its word out ([onClear]). The word size comes from the chip words and the mask only, so the hidden
 * words never influence the layout. [onCellPositioned] reports each blank's coordinates so the page can centre the
 * active one.
 */
@Composable
internal fun BackupVerificationGrid(
    verification: BackupVerification,
    onSelect: (Int) -> Unit,
    onClear: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onCellPositioned: (position: Int, coordinates: LayoutCoordinates) -> Unit = { _, _ -> },
) {
    val colors = SignerTheme.colors
    val haptics = rememberSignerHaptics()
    val measured = verification.chips.map { it.word } + MASK
    Box(modifier.fillMaxWidth().background(colors.card, SignerShapes.card).padding(12.dp)) {
        WordGrid(count = verification.wordCount, columns = WordGridColumns.Verification, measuredWords = measured) { position, layout ->
            val cellModifier = Modifier.fillMaxSize().onGloballyPositioned { onCellPositioned(position, it) }
            if (verification.isBlank(position)) {
                Blank(
                    position = position,
                    word = verification.word(position),
                    active = verification.activePosition == position,
                    layout = layout,
                    onTap = { filled ->
                        if (filled) {
                            haptics.selection()
                            onClear(position)
                        } else {
                            onSelect(position)
                        }
                    },
                    modifier = cellModifier,
                )
            } else {
                Masked(position, layout, cellModifier)
            }
        }
    }
}

@Composable
private fun Masked(position: Int, layout: WordGridLayout, modifier: Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val label = stringResource(L10n.string.word_x_hidden, position + 1)
    Row(
        modifier = modifier
            .heightIn(min = 42.dp)
            .background(colors.raised, CellShape)
            .padding(horizontal = 8.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(L10n.string.x_3, position + 1), style = typography.caption.monospacedDigit(), color = colors.secondaryLabel, maxLines = 1)
        Text(MASK, style = layout.wordStyle(typography.subheadline), color = colors.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun Blank(
    position: Int,
    word: String?,
    active: Boolean,
    layout: WordGridLayout,
    onTap: (filled: Boolean) -> Unit,
    modifier: Modifier,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val reduceMotion = SignerTheme.reduceMotion
    val background by animateColorAsState(
        if (active) colors.accent.copy(alpha = 0.10f) else colors.page,
        SignerMotion.snappy(0.18, reduceMotion),
        label = "blankBackground",
    )
    val borderColor by animateColorAsState(
        when {
            active -> colors.accent
            word == null -> colors.secondaryLabel.copy(alpha = colors.secondaryLabel.alpha * 0.45f)
            else -> colors.accent.copy(alpha = 0.40f)
        },
        SignerMotion.snappy(0.18, reduceMotion),
        label = "blankBorder",
    )
    val borderWidth by animateDpAsState(if (active) 2.dp else 1.dp, SignerMotion.snappy(0.18, reduceMotion), label = "blankBorderWidth")
    val label = stringResource(L10n.string.word_x, position + 1)
    val value = word ?: stringResource(if (active) L10n.string.setup_slot_empty_next else L10n.string.setup_slot_empty)
    val hint = stringResource(if (word == null) L10n.string.fills_this_blank_next else L10n.string.removes_this_word)
    Row(
        modifier = modifier
            .heightIn(min = maxOf(42.dp, SignerMetrics.minTouchTarget))
            .clip(CellShape)
            .clickable(onClickLabel = hint, role = Role.Button, indication = ripple(), interactionSource = null) { onTap(word != null) }
            .background(background)
            .border(borderWidth, borderColor, CellShape)
            .padding(horizontal = 8.dp)
            // The tag sits outside the cleared node so UiAutomator and the tests still find it.
            .testTag("verification.slot.$position")
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = value
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(L10n.string.x_3, position + 1), style = typography.caption.monospacedDigit(), color = colors.secondaryLabel, maxLines = 1)
        if (word != null) {
            BasicText(
                text = word,
                style = layout.wordStyle(typography.subheadline).copy(color = colors.label),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = layout.wordSize * 0.9f, maxFontSize = layout.wordSize),
            )
        }
    }
}

/**
 * The check's word part (iOS `BackupVerificationChips`, `RecoveryBackupViews.swift:493-520`): the missing words in their
 * shuffled order, wrapped into centred rows, kept in the bottom bar so they stay reachable while the grid scrolls. A
 * chip is disabled once placed (drawn at 30 %) or while no blank is active; placing one plays the selection haptic.
 * The chips are real phrase words: the screen marks itself accessibility-sensitive while they are shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupVerificationChips(verification: BackupVerification, onPlace: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val haptics = rememberSignerHaptics()
    val placedLabel = stringResource(L10n.string.placed)
    val availableLabel = stringResource(L10n.string.available)
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        for (chip in verification.chips) {
            val placed = verification.isPlaced(chip)
            val enabled = !placed && verification.activePosition != null
            val alpha by animateFloatAsState(if (placed) 0.3f else 1f, SignerMotion.quick(), label = "chipAlpha")
            val border by animateColorAsState(colors.accent.copy(alpha = if (placed) 0.15f else 0.60f), SignerMotion.quick(), label = "chipBorder")
            Box(
                modifier = Modifier
                    .alpha(alpha)
                    .heightIn(min = SignerMetrics.minTouchTarget)
                    .clip(ChipShape)
                    .background(colors.card)
                    .border(1.dp, border, ChipShape)
                    .clickable(enabled = enabled, role = Role.Button, indication = ripple(), interactionSource = null) {
                        haptics.selection()
                        onPlace(chip.id)
                    }
                    .semantics { stateDescription = if (placed) placedLabel else availableLabel }
                    .testTag("verification.chip.${chip.id}")
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(chip.word, style = typography.body.monospaced().semibold().ltr(), color = colors.label, maxLines = 1)
            }
        }
    }
}

/** The mask of hidden positions (6 × U+2022); never translated. */
internal const val MASK = "••••••"

private val CellShape = RoundedCornerShape(12.dp)
private val ChipShape = RoundedCornerShape(13.dp)
