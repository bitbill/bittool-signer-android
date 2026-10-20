package com.bittool.signer.feature.sign.review

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.DetailLine
import com.bittool.signer.core.designsystem.component.FirstLineIcon
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.component.StatusPill
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.contentDirection
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.review.ReviewPresentation
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.signing.MatchingIssue
import com.bittool.signer.core.ui.address.AddressText
import com.bittool.signer.core.ui.text.asString
import com.bittool.signer.core.ui.wallet.WalletAvatar
import com.bittool.signer.core.ui.wallet.isolate
import com.bittool.signer.feature.sign.common.SignTags
import com.bittool.signer.feature.sign.common.message
import com.bittool.signer.feature.sign.common.res

/**
 * The "Signing Wallet(s)" section of the review (iOS `signingWallets`, `Features/Sign/RequestReviewView.swift:84-132`;
 * scan-sign.md §11.7): the matching issue with its explanation and, for an interrupted or changed match, "Match Again";
 * or one card per task; or "Finding the matching wallet…" while matching runs.
 */
@Composable
internal fun SigningWalletsSection(state: RequestReviewUiState, review: TransactionReview, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Column(
        modifier = modifier.fillMaxWidth().animateContentSize(SignerMotion.smooth(0.3)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(if (state.taskCount > 1) R.string.signing_wallets else R.string.signing_wallet),
            style = typography.headline,
            color = colors.label,
            modifier = Modifier.semantics { heading() },
        )
        val issue = state.matchingIssue
        val tasks = state.tasks
        when {
            issue != null -> MatchingIssueBox(issue, state, onRetry)
            tasks != null -> {
                val showsAddress = ReviewPresentation.taskShowsAddress(review)
                val showsPath = ReviewPresentation.taskShowsPath(review)
                tasks.forEach { item -> TaskCard(item, showsAddress, showsPath) }
                if (tasks.size > 1) {
                    Text(
                        text = stringResource(R.string.each_wallet_signs_with_its_own_password_the_final),
                        style = typography.footnote,
                        color = colors.secondaryLabel,
                    )
                }
            }
            else -> MatchingProgress()
        }
    }
}

@Composable
private fun MatchingIssueBox(issue: MatchingIssue, state: RequestReviewUiState, onRetry: () -> Unit) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.danger.copy(alpha = 0.1f), SignerShapes.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val messageStyle = typography.subheadline.semibold()
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(SignTags.MATCH_ERROR),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FirstLineIcon(SignerIcons.DangerousFilled, colors.danger, messageStyle)
            // Engine English stays in its own direction inside a right-to-left interface.
            Text(issue.message(), style = messageStyle.contentDirection(), color = colors.dangerOnTint)
        }
        Text(stringResource(issue.explanation.res), style = typography.footnote, color = colors.secondaryLabel)
        if (state.canRetry) {
            Row(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clickable(enabled = !state.retrying, role = Role.Button, onClick = onRetry)
                    .testTag(SignTags.RETRY_MATCH),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (state.retrying) colors.tertiaryLabel else colors.accent
                Icon(SignerIcons.Refresh, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                Text(
                    text = stringResource(if (state.retrying) R.string.matching else R.string.match_again),
                    style = typography.subheadline.semibold(),
                    color = tint,
                )
            }
        }
        state.retryError?.let { Text(it.asString(), style = typography.footnote, color = colors.dangerOnTint) }
    }
}

@Composable
private fun MatchingProgress() {
    val colors = SignerTheme.colors
    SignerCard(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), color = colors.secondaryLabel, strokeWidth = 2.dp)
            Text(stringResource(R.string.finding_the_matching_wallet), style = SignerTheme.typography.subheadline, color = colors.secondaryLabel)
        }
    }
}

/**
 * One wallet's share of the request (iOS `TaskCard`, `RequestReviewView.swift:768-817`): avatar, name and fingerprint,
 * "Signed" or the padlock ("Awaiting password"); the signing addresses and derivation paths unless Details already
 * list them; Bitcoin inputs and the Solana signer position.
 */
@Composable
internal fun TaskCard(item: TaskItem, showsAddress: Boolean, showsPath: Boolean, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val name = item.wallet.name ?: stringResource(R.string.wallet)
    val fingerprint = item.wallet.fingerprint ?: stringResource(R.string.ui_setup_required)
    val task = item.task
    SignerCard(modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WalletAvatar(identity = item.wallet.avatarIdentity, name = name, size = 42.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = typography.headline, color = colors.label)
                    Text(
                        text = stringResource(R.string.fingerprint_x, isolate(fingerprint)),
                        style = typography.caption.monospaced(),
                        color = colors.secondaryLabel,
                    )
                }
                Spacer(Modifier.width(8.dp))
                if (item.done) {
                    StatusPill(stringResource(R.string.signed), SignerIcons.Check, colors.success)
                } else {
                    val awaiting = stringResource(R.string.awaiting_password)
                    Icon(
                        imageVector = SignerIcons.Lock,
                        contentDescription = null,
                        tint = colors.secondaryLabel,
                        modifier = Modifier.size(20.dp).semantics { contentDescription = awaiting },
                    )
                }
            }
            if (showsAddress) {
                task.displayAddresses.forEach { address ->
                    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.signing_address), style = typography.caption, color = colors.secondaryLabel)
                        AddressText(address, style = typography.footnote)
                    }
                }
            }
            if (showsPath && task.displayPaths.isNotEmpty()) {
                Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(if (task.displayPaths.size > 1) R.string.derivation_paths else R.string.derivation_path),
                        style = typography.caption,
                        color = colors.secondaryLabel,
                    )
                    Text(task.displayPaths.joinToString("\n"), style = typography.footnote.monospaced().ltr(), color = colors.label)
                }
            }
            ReviewPresentation.inputsSigned(task)?.let {
                DetailLine(stringResource(R.string.inputs_signed), it, monospaced = false)
            }
            ReviewPresentation.signerPosition(task)?.let {
                DetailLine(stringResource(R.string.signer_position), it, monospaced = false)
            }
        }
    }
}
