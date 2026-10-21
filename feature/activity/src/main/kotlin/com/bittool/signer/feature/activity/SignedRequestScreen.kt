package com.bittool.signer.feature.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bittool.signer.core.designsystem.component.Tone
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.review.ReviewPresentation
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.model.signing.SigningReceipt
import com.bittool.signer.core.ui.review.ContractNotice
import com.bittool.signer.core.ui.review.ReviewDetailsCard
import com.bittool.signer.core.ui.review.ReviewSummaryCard
import com.bittool.signer.core.ui.review.ReviewWarnings
import com.bittool.signer.core.ui.text.Callout
import com.bittool.signer.core.ui.text.rememberReviewText

/**
 * "Signed Request" (iOS `SignedRequestView`, `ActivityTab.swift:280-304`): the stored request reviewed again, laid out
 * exactly as the review screen lays it out — summary, contract notice, warnings, every decoded field. Nothing here can
 * sign. [viewModel] is the detail page's (scoped to its back-stack entry), so no second engine call runs.
 */
@Composable
fun SignedRequestScreen(
    viewModel: ActivityDetailViewModel,
    onBack: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.detailEvents.collect { event ->
                when (event) {
                    ActivityDetailEvent.Close -> onClose()
                }
            }
        }
    }
    val back = rememberResumedAction<Unit> { onBack() }
    SignedRequestContent(decode = state.decode, onBack = { back(Unit) }, modifier = modifier)
}

/**
 * Stateless Signed Request page. A record that kept no request (reached only through a restored back stack that lost
 * the Signature page, so nothing was decoded on this visit) says why there is nothing to show instead of waiting.
 */
@Composable
internal fun SignedRequestContent(decode: DecodeState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    ActivityPage(title = stringResource(R.string.signed_request), onBack = onBack, modifier = modifier) { insets ->
        when (decode) {
            is DecodeState.Decoded -> SignedRequestBody(decode.review, insets)
            is DecodeState.Failed -> Box(Modifier.fillMaxSize().padding(insets).padding(SignerMetrics.pagePadding)) {
                Callout(text = decode.message, tone = Tone.Warning)
            }
            DecodeState.NotStored -> Box(Modifier.fillMaxSize().padding(insets).padding(top = 8.dp)) {
                ListFootnote(stringResource(R.string.the_request_and_signature_are_kept_for_the_x_most, SigningReceipt.PAYLOAD_RETENTION))
            }
            DecodeState.Decoding -> Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = SignerTheme.colors.secondaryLabel, strokeWidth = 2.5.dp)
            }
        }
    }
}

@Composable
private fun SignedRequestBody(review: TransactionReview, insets: androidx.compose.foundation.layout.PaddingValues) {
    val reviewText = rememberReviewText()
    val direction = LocalLayoutDirection.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = insets.calculateTopPadding())
            .verticalScroll(rememberScrollState())
            .padding(
                start = insets.calculateStartPadding(direction),
                end = insets.calculateEndPadding(direction),
                bottom = insets.calculateBottomPadding() + 20.dp,
            )
            .readableWidth()
            .padding(horizontal = SignerMetrics.pagePadding)
            .padding(top = 8.dp)
            .testTag("activity.signedRequest"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReviewSummaryCard(review, reviewText)
        if (ReviewPresentation.showsNoticeCard(review)) review.contract?.let { ContractNotice(it) }
        // iOS keeps the older severity rule here: any warning starting with "HIGH" is danger (ActivityTab.swift:292).
        ReviewWarnings(review.warnings, isSevere = ReviewPresentation::isSevereStoredWarning)
        ReviewDetailsCard(review.fields, reviewText)
    }
}
