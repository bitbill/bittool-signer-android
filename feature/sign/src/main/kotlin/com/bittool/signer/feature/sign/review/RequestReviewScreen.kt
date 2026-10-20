package com.bittool.signer.feature.sign.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bittool.signer.core.designsystem.component.BottomActionBar
import com.bittool.signer.core.designsystem.component.DialogAction
import com.bittool.signer.core.designsystem.component.NavIcon
import com.bittool.signer.core.designsystem.component.SignerAlertDialog
import com.bittool.signer.core.designsystem.component.SignerTopAppBar
import com.bittool.signer.core.designsystem.component.StatusToastHost
import com.bittool.signer.core.designsystem.component.TextAction
import com.bittool.signer.core.designsystem.component.rememberStatusToastState
import com.bittool.signer.core.designsystem.modifier.readableWidth
import com.bittool.signer.core.designsystem.theme.SignerMetrics
import com.bittool.signer.core.designsystem.theme.SignerTheme
import com.bittool.signer.core.designsystem.theme.semibold
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.review.ReviewPresentation
import com.bittool.signer.core.model.review.ReviewText
import com.bittool.signer.core.model.review.TransactionReview
import com.bittool.signer.core.ui.address.HashText
import com.bittool.signer.core.ui.review.ContractNotice
import com.bittool.signer.core.ui.review.ReviewDetailsCard
import com.bittool.signer.core.ui.review.ReviewSummaryCard
import com.bittool.signer.core.ui.review.ReviewWarnings
import com.bittool.signer.core.ui.text.rememberReviewText
import com.bittool.signer.feature.sign.common.SignTags
import com.bittool.signer.feature.sign.common.rememberLatest
import com.bittool.signer.feature.sign.common.rememberOverlayBlocked
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * The request review (inventory rows 51–53): [RequestReviewContent] plus its password sheet and the "Discard partial
 * signatures?" confirmation, all driven by [RequestReviewViewModel].
 */
@Composable
fun RequestReviewScreen(
    modifier: Modifier = Modifier,
    scrollToTop: Flow<Unit> = emptyFlow(),
    viewModel: RequestReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RequestReviewContent(
        state = state,
        onReject = viewModel::reject,
        onToggleRisk = viewModel::toggleRisk,
        onRetry = viewModel::retryMatching,
        onAuthorize = viewModel::openAuthorize,
        modifier = modifier,
        scrollToTop = scrollToTop,
    )
    state.authorize?.let { authorize ->
        AuthorizeSheet(
            state = authorize,
            onSign = viewModel::sign,
            onCancel = viewModel::cancelAuthorize,
            onDismissed = viewModel::onAuthorizeDismissed,
        )
    }
    if (state.confirmReject) {
        SignerAlertDialog(
            title = stringResource(R.string.discard_partial_signatures),
            text = stringResource(R.string.signatures_already_created_for_this_request_will_be),
            confirm = DialogAction(text = stringResource(R.string.reject_request), destructive = true, onClick = viewModel::confirmReject),
            dismiss = DialogAction(text = stringResource(R.string.cancel), onClick = viewModel::dismissReject),
            onDismissRequest = viewModel::dismissReject,
        )
    }
}

/**
 * The review page (iOS `RequestReviewView`, `Features/Sign/RequestReviewView.swift:5-217`; scan-sign.md §11): "Review
 * Request" with "Reject"; the summary, the known-contract card when the summary does not carry it, the engine's
 * warnings (verbatim), the signing wallets, every decoded field and the request fingerprint; the acknowledgement and
 * Sign pinned at the bottom. While the page leaves (Reject, the last signature) it keeps showing the last request
 * instead of going blank.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RequestReviewContent(
    state: RequestReviewUiState,
    onReject: () -> Unit,
    onToggleRisk: () -> Unit,
    onRetry: () -> Unit,
    onAuthorize: () -> Unit,
    modifier: Modifier = Modifier,
    scrollToTop: Flow<Unit> = emptyFlow(),
    listState: LazyListState = rememberLazyListState(),
) {
    val shown = rememberLatest(state) { it.review != null }
    val review = shown.review ?: return
    val reviewText = rememberReviewText()
    val toast = rememberStatusToastState()
    val blocked = rememberOverlayBlocked(toast)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    LaunchedEffect(scrollToTop, listState) { scrollToTop.collect { listState.animateScrollToItem(0) } }
    Box(modifier.testTag(SignTags.REVIEW)) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = SignerTheme.colors.page,
            topBar = {
                SignerTopAppBar(
                    title = stringResource(R.string.review_request),
                    navigation = NavIcon.None,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        TextAction(
                            text = stringResource(R.string.reject),
                            onClick = onReject,
                            destructive = true,
                            modifier = Modifier.testTag(SignTags.REJECT),
                        )
                    },
                )
            },
            bottomBar = {
                BottomActionBar(canScrollUnder = listState.canScrollForward) {
                    SignBar(shown, onToggleRisk = onToggleRisk, onAuthorize = onAuthorize, onObscuredTouch = blocked)
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                reviewSections(shown, review, reviewText, onRetry)
            }
        }
        StatusToastHost(toast)
    }
}

private fun LazyListScope.reviewSections(
    state: RequestReviewUiState,
    review: TransactionReview,
    reviewText: ReviewText,
    onRetry: () -> Unit,
) {
    val page = Modifier.readableWidth().padding(horizontal = SignerMetrics.pagePadding)
    item(key = "summary", contentType = "summary") { ReviewSummaryCard(review, reviewText, page) }
    // The summary already carries the notice when the authorized address is its counterparty; a token transfer
    // authorizes the token contract instead, a different address with its own card.
    val contract = review.contract
    if (contract != null && ReviewPresentation.showsNoticeCard(review)) {
        item(key = "contract", contentType = "contract") { ContractNotice(contract, page) }
    }
    if (review.warnings.isNotEmpty()) {
        item(key = "warnings", contentType = "warnings") { ReviewWarnings(review.warnings, page) }
    }
    item(key = "wallets", contentType = "wallets") { SigningWalletsSection(state, review, onRetry, page) }
    item(key = "details", contentType = "details") { ReviewDetailsCard(review.fields, reviewText, page) }
    item(key = "fingerprint", contentType = "fingerprint") { RequestFingerprint(review.requestHash, page) }
}

/** "Request fingerprint" and the hash in groups of 8 (`RequestReviewView.swift:28-33`). */
@Composable
private fun RequestFingerprint(hash: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.request_fingerprint),
            style = SignerTheme.typography.caption.semibold(),
            color = SignerTheme.colors.secondaryLabel,
        )
        HashText(hash)
    }
}
