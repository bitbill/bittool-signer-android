package com.bittool.signer.feature.setup.backup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.AccessibilitySensitive
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.medium
import com.bittool.signer.core.scanner.QrCodeView
import com.bittool.signer.core.security.RecoveryPrivacySession
import com.bittool.signer.core.ui.qr.QrCardFrame
import com.bittool.signer.feature.setup.flow.RecoveryWords
import com.bittool.signer.feature.setup.ui.BodyText
import com.bittool.signer.feature.setup.ui.LinkButton
import com.bittool.signer.feature.setup.ui.LocalOverlayBlocked
import com.bittool.signer.core.l10n.R as L10n

/**
 * The phrase as a plain-text QR code (iOS `RecoveryQRSection`, `RecoveryBackupViews.swift:90-154`; setup-backup §8.5):
 * the words joined by single spaces, for moving the wallet to another device of one's own. It is a complete copy of the
 * secret, so it starts concealed behind its own session ([session], separate from the words'), revealing it does not
 * count as having seen the words, and it is never offered for saving, sharing, printing or copying (no long-press, no
 * share action). The plate is always white with black modules (`QrCodeView`, error correction M up to 600 bytes).
 */
@Composable
internal fun RecoveryQrSection(
    words: RecoveryWords,
    hasPassphrase: Boolean,
    session: RecoveryPrivacySession,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    RecoveryPrivacyEffect(session, active)
    val revealed by session.isRevealed.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Callout(stringResource(L10n.string.this_qr_code_contains_your_complete_recovery_phrase_in))
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
            label = "recoveryQr",
        ) { shown ->
            if (shown) {
                AccessibilitySensitive(active = true)
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    QrCodeView(frames = listOf(words.joined()), maxSide = QR_SIDE, modifier = Modifier.testTag("backup.qr"))
                    LinkButton(
                        text = stringResource(L10n.string.hide_qr_code),
                        leadingIcon = SignerIcons.VisibilityOff,
                        onClick = session::hide,
                        modifier = Modifier.testTag("backup.qr.hide"),
                    )
                }
            } else {
                ConcealedQrCode(onReveal = { session.reveal() })
            }
        }
        BodyText(
            text = stringResource(L10n.string.this_qr_code_is_your_recovery_phrase_anyone_who_scans),
            style = typography.footnote.medium(),
            // Small text: the darker danger variant keeps it legible on the page in both themes.
            color = colors.dangerOnTint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasPassphrase) {
            Callout(stringResource(L10n.string.it_contains_the_x_words_only_your_bip39_passphrase_is, words.size), tone = Tone.Warning)
        }
    }
}

/**
 * A QR-shaped placeholder drawn from the `qrcode` glyph, containing no recovery material (iOS `ConcealedQRCode`): the
 * white plate (always light), the blurred glyph at 55 % black and the reveal prompt. One button: "Show recovery phrase
 * QR code", `backup.qr.reveal`; overlay touches are refused.
 */
@Composable
private fun ConcealedQrCode(onReveal: () -> Unit) {
    val description = concealedDescription(L10n.string.show_recovery_phrase_qr_code)
    QrCardFrame(
        maxSide = QR_SIDE,
        modifier = Modifier
            .obscuredTouchGuard(LocalOverlayBlocked.current)
            .clickable(role = Role.Button, indication = ripple(bounded = false), interactionSource = null, onClick = onReveal)
            .semantics { contentDescription = description }
            .testTag("backup.qr.reveal"),
    ) {
        Icon(
            imageVector = SignerIcons.QrCode,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxSize().padding(8.dp).blur(10.dp).clearAndSetSemantics {},
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { RevealPrompt() }
    }
}

private val QR_SIDE = 250.dp
