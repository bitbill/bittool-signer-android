package com.bittool.signer.feature.sign.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SecondaryButton
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.review.DecodeStatus
import com.bittool.signer.feature.sign.common.SignTags
import com.bittool.signer.feature.sign.common.symbolSize

/** Whether the review has a pinned bar: a task to sign, or "Cannot Sign"; nothing while the wallet is being matched. */
internal val RequestReviewUiState.hasSignBar: Boolean
    get() = matchingIssue != null || pending != null

/** Whether the risk acknowledgement is asked for: a task to sign whose request is not fully decoded or is high risk. */
internal val RequestReviewUiState.showsAcknowledgement: Boolean
    get() = pending != null && matchingIssue == null && requiresAcknowledgement

/**
 * The pinned bar of the review (iOS `signBar`, `Features/Sign/RequestReviewView.swift:186-206`): with a task to sign,
 * the risk acknowledgement when the request needs it and [acknowledgementInBar] (otherwise the page scrolls it as the
 * last item above the bar), "Signature n of m" for several wallets and "Sign with …" (disabled until acknowledged and
 * while signing); "Cannot Sign" when the request cannot be matched. The page composes no bar at all while matching
 * ([hasSignBar]). Both decisive controls refuse touches through another app's overlay ([onObscuredTouch]).
 * [onAcknowledgementHeight] and [onActionsHeight] report the measured heights of the acknowledgement and of the part
 * under it (the page decides where the acknowledgement goes from them).
 */
@Composable
internal fun ColumnScope.SignBar(
    state: RequestReviewUiState,
    onToggleRisk: () -> Unit,
    onAuthorize: () -> Unit,
    onObscuredTouch: () -> Unit,
    acknowledgementInBar: Boolean = true,
    onAcknowledgementHeight: (Int) -> Unit = {},
    onActionsHeight: (Int) -> Unit = {},
) {
    val pending = state.pending
    when {
        pending != null && state.matchingIssue == null -> {
            if (state.showsAcknowledgement && acknowledgementInBar) {
                RiskAcknowledgement(
                    checked = state.riskAccepted,
                    fullyDecoded = state.review?.decodeStatus == DecodeStatus.FullyDecoded,
                    onToggle = onToggleRisk,
                    onObscuredTouch = onObscuredTouch,
                    modifier = Modifier.onSizeChanged { onAcknowledgementHeight(it.height) },
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().onSizeChanged { onActionsHeight(it.height) },
                verticalArrangement = Arrangement.spacedBy(SignerMetrics.barGap),
            ) {
                if (state.taskCount > 1) {
                    Text(
                        text = stringResource(R.string.signature_x_of_x, state.completedCount + 1, state.taskCount),
                        style = SignerTheme.typography.footnote.semibold(),
                        color = SignerTheme.colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
                val name = pending.wallet.name ?: stringResource(R.string.wallet)
                PrimaryButton(
                    text = stringResource(R.string.sign_with_x, name),
                    onClick = onAuthorize,
                    icon = SignerIcons.Signature,
                    busy = state.busy,
                    enabled = !state.blocked && !state.busy,
                    modifier = Modifier.obscuredTouchGuard(onObscuredTouch).testTag(SignTags.AUTHORIZE),
                )
            }
        }
        state.matchingIssue != null -> SecondaryButton(
            text = stringResource(R.string.cannot_sign),
            onClick = {},
            icon = SignerIcons.CancelCircle,
            enabled = false,
            modifier = Modifier.testTag(SignTags.AUTHORIZE),
        )
    }
}

/**
 * Compact per-request consent directly above Sign (iOS `acknowledgement`, `RequestReviewView.swift:156-184`): the
 * whole row is one checkbox (`signing.risk`, "Checked" / "Not checked"), amber at 12 %, with a selection haptic. The
 * wording depends on whether the request is fully decoded (a high-risk request) or not.
 */
@Composable
internal fun RiskAcknowledgement(
    checked: Boolean,
    fullyDecoded: Boolean,
    onToggle: () -> Unit,
    onObscuredTouch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val haptics = rememberSignerHaptics()
    val state = stringResource(if (checked) R.string.checked else R.string.not_checked)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(SignerShapes.callout)
            .background(colors.warning.copy(alpha = 0.12f))
            .obscuredTouchGuard(onObscuredTouch)
            .toggleable(value = checked, role = Role.Checkbox) {
                haptics.selection()
                onToggle()
            }
            .semantics { stateDescription = state }
            .testTag(SignTags.RISK)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AnimatedContent(
            targetState = checked,
            transitionSpec = {
                val millis = SignerMotion.SYMBOL_REPLACE_MILLIS
                (scaleIn(tween(millis), initialScale = 0.8f) + fadeIn(tween(millis))) togetherWith
                    (scaleOut(tween(millis), targetScale = 0.8f) + fadeOut(tween(millis)))
            },
            label = "riskCheckbox",
        ) { ticked ->
            Icon(
                imageVector = if (ticked) SignerIcons.CheckBoxFilled else SignerIcons.CheckBoxBlank,
                contentDescription = null,
                tint = if (ticked) colors.warning else colors.secondaryLabel,
                modifier = Modifier.size(symbolSize(24.dp, typography.title3)),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(
                    if (fullyDecoded) R.string.i_understand_the_risks_of_this_request else R.string.i_understand_this_request_cant_be_fully_verified,
                ),
                style = typography.subheadline.semibold(),
                color = colors.label,
            )
            Text(
                text = stringResource(
                    if (fullyDecoded) R.string.read_every_warning_above_before_signing else R.string.some_effects_couldnt_be_decoded_on_this_device,
                ),
                style = typography.caption,
                color = colors.secondaryLabel,
            )
        }
    }
}
