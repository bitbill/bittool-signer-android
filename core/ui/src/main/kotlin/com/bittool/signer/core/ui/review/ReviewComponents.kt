package com.bittool.signer.core.ui.review

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.bittool.signer.core.designsystem.component.Callout
import com.bittool.signer.core.designsystem.component.FirstLineIcon
import com.bittool.signer.core.designsystem.component.RaisedBox
import com.bittool.signer.core.designsystem.component.SignerCard
import com.bittool.signer.core.designsystem.component.StatusPill
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.icon.SignerIcons
import com.bittool.signer.core.designsystem.theme.SignerColors
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerMotion
import com.bittool.signer.core.designsystem.theme.SignerRadius
import com.bittool.signer.core.designsystem.theme.SignerShapes
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.bold
import com.bittool.signer.core.designsystem.theme.ltr
import com.bittool.signer.core.designsystem.theme.monospaced
import com.bittool.signer.core.designsystem.theme.rounded
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.Chain
import com.bittool.signer.core.model.review.ContractNoticeKind
import com.bittool.signer.core.model.review.DecodeStatus
import com.bittool.signer.core.model.review.ReviewContract
import com.bittool.signer.core.model.review.ReviewDisplay
import com.bittool.signer.core.model.review.ReviewField
import com.bittool.signer.core.model.review.ReviewFlowSection
import com.bittool.signer.core.model.review.ReviewPresentation
import com.bittool.signer.core.model.review.ReviewText
import com.bittool.signer.core.model.review.RiskLevel
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.ui.address.AddressText
import com.bittool.signer.core.ui.address.CopyableAddress
import com.bittool.signer.core.ui.logo.ChainLogo
import com.bittool.signer.core.ui.logo.GenericNetworkLogo
import com.bittool.signer.core.ui.logo.NetworkLogo
import com.bittool.signer.core.ui.logo.TokenIcon
import com.bittool.signer.core.ui.text.assetContractCaption
import com.bittool.signer.core.ui.text.flowLine
import com.bittool.signer.core.ui.text.reviewActionLine

// ---------------------------------------------------------------------------------------------------------------------
// Summary card (scan-sign.md §11.3, iOS ReviewSummaryCard, Features/Sign/RequestReviewView.swift:221-461)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The headline of a decoded request, everything produced by the engine from the exact bytes to be signed (iOS
 * `ReviewSummaryCard`). Card radius 26, padding 18, sections 14 apart, in this order:
 *
 * 1. the network badge and the decode badge, side by side when they fit, else stacked (never truncated);
 * 2. the icon at 60: the registry token, else the NFT glyph, else the network logo, else the neutral logo for EVM;
 * 3. the action line, the amount (rounded large title, shrinking to half on two lines; **NFT amounts wrap without a
 *    limit**: an order priced in 78 raw digits stays whole), the secondary headline, or the text headline (title3,
 *    up to 8 lines, unlimited for NFTs) — test tag `review.headline`;
 * 4. the asset box (contract and full token ID), 5. the marketplace flow, 6. the counterparty with the compact
 *    known-contract notice when it is the authorized address, 7. the engine note, 8. the fee ceiling, 9. "High risk".
 *
 * Engine English is drawn through [reviewText]; warnings and values are verbatim.
 */
@Composable
fun ReviewSummaryCard(review: TransactionReview, reviewText: ReviewText, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val resources = LocalContext.current.resources
    val display = review.display
    SignerCard(
        modifier = modifier,
        padding = PaddingValues(SignerMetrics.cardPaddingLarge),
        radius = SignerRadius.summary,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BadgesRow(networkBadge = { NetworkBadge(review) }, decodeBadge = { DecodeBadge(review.decodeStatus) })
            Box(Modifier.padding(top = 4.dp)) { SummaryIcon(review) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = reviewActionLine(review, reviewText, resources),
                    style = typography.subheadline.semibold(),
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
                ReviewPresentation.amountText(review)?.let { amount -> AmountLine(amount, display) }
                ReviewPresentation.secondaryHeadline(review)?.let { secondary ->
                    Text(reviewText.headline(secondary), style = typography.subheadline, color = colors.secondaryLabel, textAlign = TextAlign.Center)
                }
                ReviewPresentation.textHeadline(review)?.let { headline ->
                    val limit = ReviewPresentation.headlineLineLimit(display)
                    SelectionContainer {
                        Text(
                            text = reviewText.headline(headline),
                            style = typography.title3.semibold(),
                            color = colors.label,
                            textAlign = TextAlign.Center,
                            maxLines = limit ?: Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(HEADLINE_TAG),
                        )
                    }
                }
            }
            if (display != null && display.showsAssetBox) AssetBox(display)
            display?.flowSections?.takeIf { it.isNotEmpty() }?.let { FlowBox(it) }
            ReviewPresentation.counterparty(review)?.let { counterparty ->
                RaisedBox(Modifier.semantics(mergeDescendants = true) {}) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(reviewText.phrase(counterparty.label), style = typography.caption.semibold(), color = colors.secondaryLabel, textAlign = TextAlign.Center)
                        AddressText(counterparty.value, style = typography.callout, textAlign = TextAlign.Center)
                        val contract = review.contract
                        if (contract != null && ReviewPresentation.showsNoticeInSummary(review)) ContractNotice(contract, compact = true)
                    }
                }
            }
            display?.note?.let { note ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FirstLineIcon(SignerIcons.ShieldCheck, colors.success, typography.footnote.semibold(), size = 16.dp)
                    Text(note, style = typography.footnote.semibold(), color = colors.successOnTint)
                }
            }
            ReviewPresentation.feeLine(display)?.let { fee ->
                Text(
                    text = stringResource(
                        if (fee.energyBudget) R.string.energy_budget_up_to_x_total_fee_not_known_offline else R.string.network_fee_up_to_x,
                        fee.value,
                    ),
                    style = typography.footnote,
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
            if (review.riskLevel == RiskLevel.High) {
                StatusPill(stringResource(R.string.high_risk), SignerIcons.ShieldAlert, colors.danger)
            }
        }
    }
}

/** Test tag of the summary's amount or text headline (iOS `review.headline`). */
const val HEADLINE_TAG = "review.headline"

@Composable
private fun AmountLine(amount: String, display: ReviewDisplay?) {
    val colors = SignerTheme.colors
    val style = SignerTheme.typography.largeTitle.rounded().bold().copy(color = colors.label, textAlign = TextAlign.Center)
    val nft = display?.isNFT == true
    SelectionContainer(Modifier.fillMaxWidth()) {
        if (nft) {
            // An NFT order price can be 78 raw digits: it wraps in full instead of shrinking or being cut off.
            BasicText(amount, style = style.ltr(), modifier = Modifier.fillMaxWidth().testTag(HEADLINE_TAG))
        } else {
            BasicText(
                text = amount,
                style = style,
                maxLines = ReviewPresentation.amountLineLimit(display) ?: Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * 0.5f, maxFontSize = style.fontSize),
                modifier = Modifier.fillMaxWidth().testTag(HEADLINE_TAG),
            )
        }
    }
}

/** iOS `ViewThatFits`: both badges on one line when they fit (8 dp apart at least), else stacked leading, 8 apart. */
@Composable
private fun BadgesRow(networkBadge: @Composable () -> Unit, decodeBadge: @Composable () -> Unit) {
    Layout(content = { networkBadge(); decodeBadge() }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val width = constraints.maxWidth
        val first = measurables[0]
        val second = measurables[1]
        val fits = first.maxIntrinsicWidth(Constraints.Infinity) + gap + second.maxIntrinsicWidth(Constraints.Infinity) <= width
        val loose = Constraints(maxWidth = width)
        val a = first.measure(loose)
        val b = second.measure(loose)
        if (fits) {
            val height = maxOf(a.height, b.height)
            layout(width, height) {
                a.placeRelative(0, (height - a.height) / 2)
                b.placeRelative(width - b.width, (height - b.height) / 2)
            }
        } else {
            layout(width, a.height + gap + b.height) {
                a.placeRelative(0, 0)
                b.placeRelative(0, a.height + gap)
            }
        }
    }
}

@Composable
private fun NetworkBadge(review: TransactionReview) {
    val colors = SignerTheme.colors
    Row(
        modifier = Modifier
            .background(colors.raised, SignerShapes.capsule)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val brand = review.network?.brand
        // The network name follows, so the logo is not read on its own.
        Box(Modifier.clearAndSetSemantics {}) {
            when {
                brand != null -> NetworkLogo(brand, size = 18.dp)
                review.chain != Chain.Evm -> ChainLogo(review.chain, size = 18.dp)
                else -> GenericNetworkLogo(size = 18.dp)
            }
        }
        Text(review.networkName, style = SignerTheme.typography.caption.semibold(), color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DecodeBadge(status: DecodeStatus) {
    val colors = SignerTheme.colors
    when (status) {
        DecodeStatus.FullyDecoded -> StatusPill(stringResource(R.string.decoded_on_device), SignerIcons.VerifiedFilled, colors.success)
        DecodeStatus.PartiallyDecoded -> StatusPill(stringResource(R.string.partially_decoded), SignerIcons.WarningFilled, colors.warning)
        DecodeStatus.Opaque -> StatusPill(stringResource(R.string.not_decoded), SignerIcons.HelpFilled, colors.danger)
    }
}

@Composable
private fun SummaryIcon(review: TransactionReview) {
    val asset = review.asset
    val kind = review.display?.assetKind
    val brand = review.network?.brand ?: if (review.chain == Chain.Evm) null else review.chain.primaryNetwork
    when {
        asset != null -> TokenIcon(asset.icon, asset.symbol, size = 60.dp)
        kind != null -> NftGlyph(kind, size = 60.dp)
        brand != null -> NetworkLogo(brand, size = 60.dp)
        review.chain == Chain.Evm -> GenericNetworkLogo(size = 60.dp)
    }
}

@Composable
private fun AssetBox(display: ReviewDisplay) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val resources = LocalContext.current.resources
    RaisedBox {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val contract = display.assetContract
            if (contract != null) {
                Column(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("review.assetContract"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(assetContractCaption(display, resources), style = typography.caption.semibold(), color = colors.secondaryLabel, textAlign = TextAlign.Center)
                    AddressText(contract, style = typography.callout, textAlign = TextAlign.Center)
                }
            }
            val tokenId = display.summaryTokenId
            if (tokenId != null) {
                if (contract != null) Hairline()
                val spoken = ReviewDisplay.spokenTokenId(tokenId)
                Column(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("review.tokenId"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(if (display.tokenIdIsAmbiguous) R.string.token_id_or_raw_amount else R.string.token_id),
                        style = typography.caption.semibold(),
                        color = colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                    )
                    SelectionContainer {
                        Text(
                            text = tokenId,
                            style = typography.callout.monospaced().ltr(),
                            color = colors.label,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { contentDescription = spoken },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowBox(sections: List<ReviewFlowSection>) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val resources = LocalContext.current.resources
    RaisedBox {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sections.forEachIndexed { index, section ->
                if (index > 0) Hairline()
                Column(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("review.flow.${section.id}"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FirstLineIcon(section.icon, section.tint(colors), typography.caption.semibold(), size = 14.dp)
                        Text(stringResource(section.title), style = typography.caption.semibold(), color = colors.secondaryLabel)
                    }
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            section.lines.forEach { line -> Text(flowLine(line, resources), style = typography.callout, color = colors.label) }
                        }
                    }
                }
            }
        }
    }
}

private val ReviewFlowSection.title: Int
    get() = when (kind) {
        ReviewFlowSection.Kind.Give -> R.string.you_give
        ReviewFlowSection.Kind.Receive -> R.string.you_receive
        ReviewFlowSection.Kind.Others -> R.string.also_paid_out
    }

private val ReviewFlowSection.icon: ImageVector
    get() = when (kind) {
        ReviewFlowSection.Kind.Give -> SignerIcons.ArrowOutward
        ReviewFlowSection.Kind.Receive -> SignerIcons.ArrowInward
        ReviewFlowSection.Kind.Others -> SignerIcons.Branch
    }

private fun ReviewFlowSection.tint(colors: SignerColors): Color = when (kind) {
    ReviewFlowSection.Kind.Give -> colors.warning
    ReviewFlowSection.Kind.Receive -> colors.accent
    ReviewFlowSection.Kind.Others -> colors.secondaryLabel
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(SignerTheme.colors.opaqueSeparator))
}

// ---------------------------------------------------------------------------------------------------------------------
// Known-contract notice (scan-sign.md §11.5, iOS ContractNotice, RequestReviewView.swift:547-657)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * What the bundled known-contract library says about the address a request authorizes (iOS `ContractNotice`). A
 * listing identifies a deployment and never endorses a request, so a hit is **amber, never green**; a registry token is
 * accent; an unlisted spender or operator is danger (it is a risk); an unlisted plain contract is neutral.
 *
 * [compact]: one centred line under an address that is already shown (inside the summary's counterparty box); else a
 * tinted card with the title, the identification, the role caption, the address and the every-chain note. One
 * accessibility element, test tag `review.contractNotice`.
 */
@Composable
fun ContractNotice(contract: ReviewContract, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    val kind = contract.noticeKind
    val color = kind.color(colors)
    val title = stringResource(kind.title(contract.role))
    val detail = contract.noticeDetail
    if (compact) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .semantics(mergeDescendants = true) {}
                .testTag(CONTRACT_NOTICE_TAG),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                FirstLineIcon(kind.icon, color, typography.caption.semibold(), size = 14.dp)
                Text(title, style = typography.caption.semibold(), color = colors.onTint(color), textAlign = TextAlign.Center)
            }
            if (detail != null) Text(detail, style = typography.caption, color = colors.secondaryLabel, textAlign = TextAlign.Center)
            if (contract.everyChain) EveryChainNote(TextAlign.Center)
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), SignerShapes.callout)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(CONTRACT_NOTICE_TAG),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FirstLineIcon(kind.icon, color, typography.subheadline.semibold())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = typography.subheadline.semibold(), color = colors.label)
            if (detail != null) Text(detail, style = typography.subheadline, color = colors.secondaryLabel)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(contract.role.addressCaption), style = typography.caption2.semibold(), color = colors.secondaryLabel)
                AddressText(contract.address, style = typography.footnote)
            }
            if (contract.everyChain) EveryChainNote(TextAlign.Start)
        }
    }
}

/** Test tag of the known-contract notice (iOS `review.contractNotice`). */
const val CONTRACT_NOTICE_TAG = "review.contractNotice"

@Composable
private fun EveryChainNote(align: TextAlign) {
    Text(
        stringResource(R.string.same_address_on_every_evm_network),
        style = SignerTheme.typography.caption2,
        color = SignerTheme.colors.secondaryLabel,
        textAlign = align,
    )
}

private fun ContractNoticeKind.color(colors: SignerColors): Color = when (this) {
    ContractNoticeKind.Known -> colors.warning
    ContractNoticeKind.Token -> colors.accent
    ContractNoticeKind.UnlistedSpender -> colors.danger
    ContractNoticeKind.Unlisted -> colors.secondaryLabel
}

private val ContractNoticeKind.icon: ImageVector
    get() = when (this) {
        ContractNoticeKind.Known -> SignerIcons.VerifiedFilled
        ContractNoticeKind.Token -> SignerIcons.InfoFilled
        ContractNoticeKind.UnlistedSpender -> SignerIcons.WarningFilled
        ContractNoticeKind.Unlisted -> SignerIcons.HelpFilled
    }

private fun ContractNoticeKind.title(role: ReviewContract.Role): Int = when (this) {
    ContractNoticeKind.Known -> when (role) {
        ReviewContract.Role.Spender -> R.string.known_public_contract_spender
        ReviewContract.Role.Operator -> R.string.known_public_contract_operator
        ReviewContract.Role.Contract -> R.string.known_public_contract
    }
    ContractNoticeKind.Token -> R.string.recognized_token_contract
    ContractNoticeKind.UnlistedSpender, ContractNoticeKind.Unlisted -> when (role) {
        ReviewContract.Role.Spender -> R.string.spender_is_not_a_known_contract
        ReviewContract.Role.Operator -> R.string.operator_is_not_a_known_contract
        ReviewContract.Role.Contract -> R.string.contract_is_not_in_the_known_list
    }
}

private val ReviewContract.Role.addressCaption: Int
    get() = when (this) {
        ReviewContract.Role.Spender -> R.string.spender
        ReviewContract.Role.Operator -> R.string.operator
        ReviewContract.Role.Contract -> R.string.contract
    }

// ---------------------------------------------------------------------------------------------------------------------
// Warnings and details
// ---------------------------------------------------------------------------------------------------------------------

/**
 * The engine's warnings, one verbatim `Callout` each (never translated; iOS `RequestReviewView.swift:71-82`): danger
 * when [isSevere], warning otherwise. The review passes the default rule (HIGH RISK / UNLIMITED / HIGH FEE); Activity's
 * Signed Request passes `ReviewPresentation::isSevereStoredWarning` (any "HIGH" prefix, `ActivityTab.swift:292`).
 */
@Composable
fun ReviewWarnings(
    warnings: List<String>,
    modifier: Modifier = Modifier,
    isSevere: (String) -> Boolean = ReviewPresentation::isSevereWarning,
) {
    if (warnings.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        warnings.forEach { warning -> Callout(text = warning, tone = if (isSevere(warning)) Tone.Danger else Tone.Warning) }
    }
}

/**
 * Every decoded field (iOS `ReviewDetailsCard`, `RequestReviewView.swift:694-764`; scan-sign §11.9): the heading
 * "Details" and one card with the rows separated by hairlines, each label (caption semibold, secondary, through
 * [reviewText]) above its value drawn by `ReviewPresentation.rowStyle`:
 * - **address** — `CopyableAddress` in footnote;
 * - **identifier** (token IDs, quantities, item lists) — the whole value, monospaced, selectable, never collapsed and
 *   never styled as an address (a uint256 ID can pass the Bitcoin pattern);
 * - **collapsible** (over 280 characters or 6 lines) — the first 280 characters and "…" until "Show All";
 * - **text** — monospaced when it looks technical (`0x…`, `m/…`, long hex, several lines), else subheadline.
 *
 * Values are verbatim and left to right when monospaced. Expansion state survives configuration changes per field.
 */
@Composable
fun ReviewDetailsCard(fields: List<ReviewField>, reviewText: ReviewText, modifier: Modifier = Modifier) {
    val colors = SignerTheme.colors
    Column(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.details),
            style = SignerTheme.typography.headline,
            color = colors.label,
            modifier = Modifier.padding(bottom = 10.dp).semantics { heading() },
        )
        SignerCard {
            fields.forEachIndexed { index, field ->
                if (index > 0) {
                    Box(Modifier.padding(vertical = 10.dp)) { Hairline() }
                }
                DetailRow(field, reviewText)
            }
        }
    }
}

@Composable
private fun DetailRow(field: ReviewField, reviewText: ReviewText) {
    val colors = SignerTheme.colors
    val typography = SignerTheme.typography
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(reviewText.label(field.label), style = typography.caption.semibold(), color = colors.secondaryLabel)
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
                    style = if (technical) typography.footnote.monospaced().ltr() else typography.subheadline,
                    color = colors.label,
                )
            }
        }
    }
}
