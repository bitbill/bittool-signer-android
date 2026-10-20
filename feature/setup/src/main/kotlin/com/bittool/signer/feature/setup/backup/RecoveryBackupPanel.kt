package com.bittool.signer.feature.setup.backup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.SegmentedControl
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.feature.setup.flow.RecoveryWords
import com.bittool.signer.feature.setup.ui.LinkButton
import com.bittool.signer.core.l10n.R as L10n

/** iOS `BackupFormat`. */
internal enum class BackupFormat { Words, Qr }

/**
 * The recovery phrase backup (iOS `RecoveryBackupPanel`, `Features/Backup/RecoveryBackupViews.swift:5-82`; setup-backup
 * §8.3): the words to write down, a plain-text QR code for moving the wallet to another device of one's own, 2-of-3
 * recovery cards and tips. Everything starts concealed and shows with one tap. Used by the create flow's backup step
 * and by the saved wallet's Recovery Phrase page.
 *
 * - The words and the QR code are revealed separately (two sessions): seeing the words is no reason to put a scannable
 *   copy on screen too. Switching Words ↔ QR Code conceals what is left.
 * - [isCovered] (a sheet the owner shows over the panel, e.g. setup's Security Tips) and the panel's own sheets pause
 *   the visible format's session, which conceals it; only the sheet on top reacts to screenshots.
 * - The owner keys the panel by `securityEpoch`: every suspend rebuilds it (Words again, sheets closed, concealed).
 *
 * @param displayedPassphrase shown under the revealed words (the saved-wallet page only; setup omits it).
 * @param hasPassphrase the wallet has a BIP39 passphrase, which neither the QR code nor the cards contain.
 * @param showsTipsLink setup shows the tips button in its bottom bar instead.
 * @param onReveal the words were shown, in the grid or on the cards.
 */
@Composable
fun RecoveryBackupPanel(
    words: RecoveryWords,
    modifier: Modifier = Modifier,
    displayedPassphrase: String = "",
    hasPassphrase: Boolean = false,
    showsTipsLink: Boolean = true,
    isCovered: Boolean = false,
    onReveal: () -> Unit = {},
) {
    val wordsSession = rememberPrivacySession()
    val qrSession = rememberPrivacySession()
    var format by remember { mutableStateOf(BackupFormat.Words) }
    var showingCards by remember { mutableStateOf(false) }
    var showingTips by remember { mutableStateOf(false) }
    val covered = isCovered || showingCards || showingTips
    val formatLabel = stringResource(L10n.string.backup_format)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SegmentedControl(
            options = listOf(stringResource(L10n.string.words), stringResource(L10n.string.qr_code)),
            selectedIndex = format.ordinal,
            onSelect = { format = BackupFormat.entries[it] },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = formatLabel }.testTag("backup.format"),
        )
        AnimatedContent(
            targetState = format,
            transitionSpec = {
                (fadeIn(SignerMotion.disclosure()) togetherWith fadeOut(SignerMotion.quick())) using
                    SizeTransform(clip = false) { _, _ -> SignerMotion.disclosure() }
            },
            label = "backupFormat",
        ) { shown ->
            when (shown) {
                BackupFormat.Words -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProtectedRecoveryPhrase(
                        words = words,
                        session = wordsSession,
                        active = !covered,
                        passphrase = displayedPassphrase,
                        onReveal = onReveal,
                    )
                    RecoveryCardsButton(onClick = { showingCards = true }, modifier = Modifier.align(Alignment.End))
                }
                BackupFormat.Qr -> RecoveryQrSection(words = words, hasPassphrase = hasPassphrase, session = qrSession, active = !covered)
            }
        }
        if (showsTipsLink) {
            SecurityTipsButton(onClick = { showingTips = true }, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (showingTips) SecurityTipsSheet(onDismiss = { showingTips = false })
    if (showingCards) {
        RecoveryCardsSheet(words = words, hasPassphrase = hasPassphrase, onReveal = onReveal, onDismiss = { showingCards = false })
    }
}

/** "Recovery Cards →" (accessibility hint: "Splits the phrase across three cards. Any two restore it."). */
@Composable
private fun RecoveryCardsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val hint = stringResource(L10n.string.splits_the_phrase_across_three_cards_any_two_restore_it)
    Box(modifier) {
        LinkButton(
            text = stringResource(L10n.string.recovery_cards),
            trailingIcon = SignerIcons.ArrowForward,
            onClick = onClick,
            modifier = Modifier
                .semantics { onClick(label = hint) { onClick(); true } }
                .testTag("backup.cards"),
        )
    }
}
