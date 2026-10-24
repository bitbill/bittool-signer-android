package com.bittool.signer.feature.sign.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.bittool.signer.core.designsystem.component.PrimaryButton
import com.bittool.signer.core.designsystem.component.SecondaryButton
import com.bittool.signer.core.designsystem.haptics.rememberSignerHaptics
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.security.obscuredTouchGuard
import com.bittool.signer.core.designsystem.security.strictObscuredTouchGuard
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
 *
 * While the acknowledgement scrolls with the page and is not ticked, the disabled Sign would not say why (backlog B32):
 * the bar then says where the box is, and [onShowAcknowledgement] scrolls the page to it.
 */
@Composable
internal fun ColumnScope.SignBar(
    state: RequestReviewUiState,
    onToggleRisk: () -> Unit,
    onAuthorize: () -> Unit,
    onObscuredTouch: () -> Unit,
    acknowledgementInBar: Boolean = true,
    onShowAcknowledgement: () -> Unit = {},
) {
    val pending = state.pending
    when {
        pending != null && state.matchingIssue == null -> {
            if (state.showsAcknowledgement) {
                if (acknowledgementInBar) {
                    RiskAcknowledgement(
                        checked = state.riskAccepted,
                        fullyDecoded = state.review?.decodeStatus == DecodeStatus.FullyDecoded,
                        onToggle = onToggleRisk,
                        onObscuredTouch = onObscuredTouch,
                    )
                } else if (!state.riskAccepted) {
                    AcknowledgementHint(onShowAcknowledgement)
                }
            }
            SignActions(state, onAuthorize, onObscuredTouch)
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
 * The part of the bar under the acknowledgement: "Signature n of m" for several wallets and "Sign with …". The page
 * also measures it, unplaced, to decide where the acknowledgement goes ([interactive] false: never busy, so its height
 * does not change while signing).
 */
@Composable
internal fun SignActions(
    state: RequestReviewUiState,
    onAuthorize: () -> Unit,
    onObscuredTouch: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val pending = state.pending ?: return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SignerMetrics.barGap)) {
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
            busy = interactive && state.busy,
            enabled = interactive && !state.blocked && !state.busy,
            modifier = if (interactive) Modifier.strictObscuredTouchGuard(onObscuredTouch).testTag(SignTags.AUTHORIZE) else Modifier,
        )
    }
}

/**
 * "Check the box at the end of the review to sign." ending in a downward chevron, in the bar above the disabled Sign
 * while the acknowledgement scrolls with the page (`signing.riskHint`, Android-only). One button, read as its sentence:
 * it scrolls the page to the box.
 */
@Composable
private fun AcknowledgementHint(onClick: () -> Unit) {
    val colors = SignerTheme.colors
    val hint = stringResource(R.string.sign_acknowledgement_hint)
    // The chevron follows the last word on its line (a no-break space keeps it from wrapping alone), wherever the
    // sentence breaks in the language and at the text size.
    val text = remember(hint) {
        buildAnnotatedString {
            append(hint)
            append(' ')
            appendInlineContent(HINT_CHEVRON, "⌄")
        }
    }
    val chevron = remember(colors.accent) {
        mapOf(
            HINT_CHEVRON to InlineTextContent(Placeholder(1.2.em, 1.2.em, PlaceholderVerticalAlign.TextCenter)) {
                Icon(SignerIcons.ExpandMore, contentDescription = null, tint = colors.accent, modifier = Modifier.fillMaxSize())
            },
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SignerMetrics.minTouchTarget)
            .clip(SignerShapes.callout)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .testTag(SignTags.RISK_HINT)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = SignerTheme.typography.footnote.semibold(),
            color = colors.accent,
            textAlign = TextAlign.Center,
            inlineContent = chevron,
            // Read as the sentence alone, without the chevron's stand-in character.
            modifier = Modifier.clearAndSetSemantics { this.text = AnnotatedString(hint) },
        )
    }
}

private const val HINT_CHEVRON = "chevron"

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
    val haptics = rememberSignerHaptics()
    val state = stringResource(if (checked) R.string.checked else R.string.not_checked)
    AcknowledgementLayout(
        checked = checked,
        fullyDecoded = fullyDecoded,
        modifier = modifier
            .clip(SignerShapes.callout)
            .strictObscuredTouchGuard(onObscuredTouch)
            .toggleable(value = checked, role = Role.Checkbox) {
                haptics.selection()
                onToggle()
            }
            .semantics { stateDescription = state }
            .testTag(SignTags.RISK),
    )
}

/**
 * How the acknowledgement looks, without its behaviour: the page measures this, unplaced, to decide where the real one
 * goes ([animated] false: the box is drawn without its replace animation).
 */
@Composable
internal fun AcknowledgementLayout(checked: Boolean, fullyDecoded: Boolean, modifier: Modifier = Modifier, animated: Boolean = true) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(SignerShapes.callout)
            .background(colors.warning.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val box: @Composable (Boolean) -> Unit = { ticked ->
            Icon(
                imageVector = if (ticked) SignerIcons.CheckBoxFilled else SignerIcons.CheckBoxBlank,
                contentDescription = null,
                tint = if (ticked) colors.warning else colors.secondaryLabel,
                modifier = Modifier.size(symbolSize(24.dp, typography.title3)),
            )
        }
        if (animated) {
            AnimatedContent(
                targetState = checked,
                transitionSpec = {
                    val millis = SignerMotion.SYMBOL_REPLACE_MILLIS
                    (scaleIn(tween(millis), initialScale = 0.8f) + fadeIn(tween(millis))) togetherWith
                        (scaleOut(tween(millis), targetScale = 0.8f) + fadeOut(tween(millis)))
                },
                label = "riskCheckbox",
            ) { ticked -> box(ticked) }
        } else {
            box(checked)
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
