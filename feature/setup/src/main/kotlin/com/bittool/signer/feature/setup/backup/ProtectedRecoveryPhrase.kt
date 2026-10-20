package com.bittool.signer.feature.setup.backup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.AccessibilitySensitive
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.monospacedDigit
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.security.RecoveryPrivacySession
import com.bittool.signer.feature.setup.flow.RecoveryWords
import com.bittool.signer.feature.setup.ui.LinkButton
import com.bittool.signer.feature.setup.ui.LocalOverlayBlocked
import com.bittool.signer.core.l10n.R as L10n

/**
 * A recovery phrase that starts concealed (iOS `ProtectedRecoveryPhrase`, `RecoveryPhraseViews.swift:7-50`; setup-backup
 * §8.4). While concealed the words are **not composed at all** — absent from the layout and the semantics tree, not
 * blurred — and a tap on the placeholder shows them at once (the tap is the consent, no confirmation). They are hidden
 * again by [session] on pause, background, capture and screenshots, when the display leaves or is covered ([active]),
 * and when [words] or [passphrase] change. Hiding removes them in the same frame (no fade-out over real words).
 *
 * [passphrase] (the saved-wallet page only) is shown under the words with "BIP39 passphrase · back up separately".
 */
@Composable
internal fun ProtectedRecoveryPhrase(
    words: RecoveryWords,
    session: RecoveryPrivacySession,
    active: Boolean,
    modifier: Modifier = Modifier,
    passphrase: String = "",
    onReveal: () -> Unit = {},
) {
    RecoveryPrivacyEffect(session, active, onReveal)
    val revealed by session.isRevealed.collectAsStateWithLifecycle()
    // iOS `onChange(of: words)` / `onChange(of: passphrase)`: new material is never shown without a new tap.
    val shownFor = remember { ShownMaterial(words, passphrase) }
    LaunchedEffect(words, passphrase) {
        if (shownFor.words != words || shownFor.passphrase != passphrase) {
            session.hide()
            shownFor.words = words
            shownFor.passphrase = passphrase
        }
    }
    AnimatedContent(
        targetState = revealed,
        modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            val change = if (targetState) {
                fadeIn(SignerMotion.reveal()) togetherWith fadeOut(SignerMotion.reveal())
            } else {
                // Concealing never fades real words out: they leave in this frame.
                fadeIn(SignerMotion.quick()) togetherWith ExitTransition.None
            }
            change using SizeTransform(clip = false) { _, _ -> SignerMotion.reveal() }
        },
        label = "protectedPhrase",
    ) { shown ->
        if (shown) {
            RevealedPhrase(words, passphrase, onHide = session::hide)
        } else {
            ConcealedPhrase(count = words.size, onReveal = { session.reveal() })
        }
    }
}

private class ShownMaterial(var words: RecoveryWords, var passphrase: String)

@Composable
private fun RevealedPhrase(words: RecoveryWords, passphrase: String, onHide: () -> Unit) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    // Only accessibility tools (TalkBack, Switch Access) may read revealed words (gaps G5.1).
    AccessibilitySensitive(active = true)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        RecoveryWordGrid(words)
        if (passphrase.isNotEmpty()) {
            SignerCard(padding = PaddingValues(14.dp), radius = SignerTheme.radius.callout) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(SignerIcons.Key, contentDescription = null, tint = colors.secondaryLabel, modifier = Modifier.size(14.dp))
                    Text(stringResource(L10n.string.bip39_passphrase_back_up_separately), style = typography.caption.semibold(), color = colors.secondaryLabel)
                }
                Text(
                    text = passphrase,
                    style = typography.body.monospaced().ltr(),
                    color = colors.label,
                    modifier = Modifier.padding(top = 6.dp).testTag("recovery.passphrase"),
                )
            }
        }
        LinkButton(
            text = stringResource(L10n.string.hide_recovery_phrase),
            leadingIcon = SignerIcons.VisibilityOff,
            onClick = onHide,
            modifier = Modifier.testTag("recovery.hide"),
        )
    }
}

/**
 * The revealed words (iOS `RecoveryWordGrid`, `RecoveryPhraseViews.swift:141-175`): one cell per word — the index
 * (caption2, tabular, at least 18 dp, end-aligned) and the word verbatim in the shared font — on `raised`, radius 12,
 * inside a card. Each cell is one accessibility element, tagged `recovery.word.<index>`.
 */
@Composable
internal fun RecoveryWordGrid(words: RecoveryWords, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Box(modifier.fillMaxWidth().background(colors.card, SignerShapes.card).padding(12.dp)) {
        WordGrid(count = words.size, columns = WordGridColumns.Words, measuredWords = words.list) { index, layout ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.raised, CellShape)
                    .padding(horizontal = 10.dp, vertical = 11.dp)
                    .semantics(mergeDescendants = true) {}
                    .testTag("recovery.word.$index"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(L10n.string.x, index + 1),
                    style = typography.caption2.monospacedDigit(),
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.widthIn(min = 18.dp),
                )
                val style = layout.wordStyle(typography.subheadline).copy(color = colors.label)
                BasicText(
                    text = words.list[index],
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    // Safety net only: the shared size already fits every word.
                    autoSize = TextAutoSize.StepBased(minFontSize = layout.wordSize * 0.9f, maxFontSize = layout.wordSize),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Placeholder with the phrase's shape and no recovery material (iOS `ConcealedPhrase`, `RecoveryPhraseViews.swift:
 * 103-139`): one cell per word with its number and a bar, columns from the reference word "wwwwwwww" (never the real
 * words), blurred, under the "Tap to Reveal" prompt. One button: "Show recovery phrase", `recovery.reveal`; touches
 * through another app's overlay are refused.
 */
@Composable
internal fun ConcealedPhrase(count: Int, onReveal: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val description = concealedDescription(L10n.string.show_recovery_phrase_2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .obscuredTouchGuard(LocalOverlayBlocked.current)
            .clip(SignerShapes.card)
            .clickable(role = Role.Button, indication = ripple(), interactionSource = null, onClick = onReveal)
            .semantics { contentDescription = description }
            .testTag("recovery.reveal")
            .background(colors.card)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        WordGrid(
            count = count,
            columns = WordGridColumns.Words,
            measuredWords = listOf(WordGridColumns.REFERENCE_WORD),
            modifier = Modifier.clearAndSetSemantics {}.blur(2.dp),
        ) { index, _ ->
            Row(
                modifier = Modifier.fillMaxSize().background(colors.raised, CellShape).padding(horizontal = 10.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(L10n.string.x, index + 1),
                    style = typography.caption2.monospacedDigit(),
                    color = colors.tertiaryLabel,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 18.dp),
                )
                Box(Modifier.weight(1f).height(10.dp).background(colors.fill, BarShape))
            }
        }
        RevealPrompt()
    }
}

private val CellShape = RoundedCornerShape(12.dp)
private val BarShape = RoundedCornerShape(3.dp)
